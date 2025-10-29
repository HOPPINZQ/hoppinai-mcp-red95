package com.hoppinzq.red95.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoppinzq.red95.model.*;
import com.hoppinzq.red95.socket.SocketPool;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 游戏socket接口类，提供与游戏服务器的通信功能。
 * 包括发送命令、获取屏幕信息、获取单位列表等。
 * 描述红警两个单位的距离使用曼哈顿距离：d(A,B)=∣x1 − x2∣ + ∣y1 − y2∣
 * 例如，点(1,1)到(5,4)的曼哈顿距离为|5-1| + |4-1| = 7
 */
@Slf4j
public class GameSocketAPI {
    private static final String API_VERSION = "1.0";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 50000; // 50秒
    private static final ObjectMapper objectMapper = new ObjectMapper();
    // 类成员变量 - 建筑依赖关系，前者能建造的前提是后者已经建造完成
    public static final Map<String, List<String>> BUILDING_DEPENDENCIES = Map.of(
            "电厂", List.of(),
            "兵营", List.of("电厂"),
            "矿场", List.of("电厂"),
            "车间", List.of("矿场"),
            "雷达", List.of("矿场"),
            "维修中心", List.of("车间"),
            "核电", List.of("雷达"),
            "科技中心", List.of("车间", "雷达"),
            "机场", List.of("雷达")
    );
    // 类成员变量 - 单位依赖关系，前者能建造的前提是后者已经建造完成
    public static final Map<String, List<String>> UNIT_DEPENDENCIES = Map.of(
            "步兵", List.of("兵营"),
            "火箭兵", List.of("兵营"),
            "工程师", List.of("兵营"),
            "手雷兵", List.of("兵营"),
            "矿车", List.of("车间"),
            "防空车", List.of("车间"),
            "装甲车", List.of("车间"),
            "重坦", List.of("车间", "维修中心"),
            "v2", List.of("车间", "雷达"),
            "猛犸坦克", List.of("车间", "维修中心", "科技中心")
    );

    private String host;
    private int port;
    private String language;

    private static SocketPool pool = null;

    public GameSocketAPI(String host, int port, String language) {
        this.host = host;
        this.port = port;
        this.language = language;
    }

    public void init() {
        pool = new SocketPool(host, port);
    }

    public boolean isServerRunning() {
        try {
            Map<String, Object> response = sendRequest("ping", new HashMap<>());
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "检查服务是否运行失败");
            return result.getOrDefault("status", 0).equals(1) && response.containsKey("data");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("SERVER_NOT_RUNNING",
                    "检查服务是否运行时异常: " + e.getMessage());
        }
    }

    private Map<String, Object> sendRequest(String command, Map<String, Object> params) {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("apiVersion", API_VERSION);
        requestData.put("requestId", requestId);
        requestData.put("command", command);
        requestData.put("params", params);
        requestData.put("language", this.language);

        int retries = 0;
        Socket socket = null;
        InputStream in = null;
        OutputStream out = null;
        while (retries < MAX_RETRIES) {
            try {
                socket = pool.borrowSocket();

                // 发送请求
                out = socket.getOutputStream();
                out.write(objectMapper.writeValueAsBytes(requestData));
                out.flush();

                // 接收响应
                in = socket.getInputStream();
                String responseData = receiveData(socket);

                Map<String, Object> response = objectMapper.readValue(responseData,
                        new TypeReference<Map<String, Object>>() {
                        }
                );

                // 验证响应格式
                if (response == null) {
                    throw new GameAPIError("INVALID_RESPONSE", "服务器返回的响应格式无效");
                }

                // 检查请求ID匹配
                if (!requestId.equals(response.get("requestId"))) {
                    throw new GameAPIError("REQUEST_ID_MISMATCH", "响应的请求ID不匹配");
                }

                // 处理错误响应
                if (((Number) response.getOrDefault("status", 0)).intValue() < 0) {
                    Map<String, Object> error = (Map<String, Object>) response.get("error");
                    throw new GameAPIError(
                            (String) error.getOrDefault("code", "UNKNOWN_ERROR"),
                            (String) error.getOrDefault("message", "未知错误"),
                            (Map<String, Object>) error.get("details")
                    );
                }

                return response;
            } catch (SocketTimeoutException | ConnectException e) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    throw new GameAPIError("CONNECTION_ERROR", "连接服务器失败: " + e.getMessage());
                }
            } catch (JsonProcessingException e) {
                throw new GameAPIError("INVALID_JSON", "服务器返回的不是有效的JSON格式");
            } catch (GameAPIError e) {
                throw e;
            } catch (Exception e) {
                throw new GameAPIError("UNEXPECTED_ERROR", "发生未预期的错误: " + e.getMessage());
            } finally {
                try {
                    if (in != null) in.close();
                } catch (IOException ignored) {
                }
                try {
                    if (out != null) out.close();
                } catch (IOException ignored) {
                }
                if (socket != null) {
                    pool.returnSocket(socket);
                }
            }
        }
        throw new GameAPIError("CONNECTION_ERROR", "连接服务器失败");
    }

    private String receiveData(Socket socket) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        byte[] data = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private Object handleResponse(Map<String, Object> response, String errorMsg) {
        if (response == null) {
            throw new GameAPIError("NO_RESPONSE", errorMsg);
        }
        return response.containsKey("data") ? response.get("data") : response;
    }

    public void moveCameraByLocation(Location location) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("location", location.toMap());

            Map<String, Object> response = sendRequest("camera_move", params);
            handleResponse(response, "移动相机失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("CAMERA_MOVE_ERROR", "移动相机时发生错误: " + e.getMessage());
        }
    }

    /**
     * 向某个方向移动相机
     *
     * @param direction 移动的方向，必须在 {ALL_DIRECTIONS} 中
     * @param distance  移动的距离
     * @throws GameAPIError 当移动相机失败时
     */
    public void moveCameraByDirection(String direction, int distance) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("direction", direction);
            params.put("distance", distance);

            Map<String, Object> response = sendRequest("camera_move", params);
            handleResponse(response, "移动相机失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("CAMERA_MOVE_ERROR",
                    "移动相机时发生错误: " + e.getMessage());
        }
    }

    /**
     * 检查是否可以生产指定类型的Actor
     *
     * @param unitType Actor类型，必须在 {ALL_UNITS} 中
     * @return 是否可以生产
     * @throws GameAPIError 当查询生产能力失败时
     */
    public boolean canProduce(String unitType) {
        try {
            // 构建请求参数
            Map<String, Object> unitParam = new HashMap<>();
            unitParam.put("unit_type", unitType);

            Map<String, Object> params = new HashMap<>();
            params.put("units", Collections.singletonList(unitParam));

            // 发送请求并处理响应
            Map<String, Object> response = sendRequest("query_can_produce", params);
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询生产能力失败");

            // 返回结果，默认false
            return (boolean) result.getOrDefault("canProduce", false);
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("PRODUCE_QUERY_ERROR",
                    "查询生产能力时发生错误: " + e.getMessage());
        }
    }

    /**
     * 生产指定数量的Actor
     *
     * @param unitType          Actor类型
     * @param quantity          生产数量
     * @param autoPlaceBuilding 是否在生产完成后使用随机位置自动放置建筑，仅对建筑类型有效
     * @return 生产任务的waitId，如果任务创建失败则返回null
     * @throws GameAPIError 当生产命令执行失败时
     */
    public Integer produce(String unitType, int quantity, boolean autoPlaceBuilding) {
        try {
            // 构建单位生产参数
            Map<String, Object> unitParam = new HashMap<>();
            unitParam.put("unit_type", unitType);
            unitParam.put("quantity", quantity);

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("units", Collections.singletonList(unitParam));
            params.put("autoPlaceBuilding", autoPlaceBuilding);

            // 发送请求并处理响应
            Map<String, Object> response = sendRequest("start_production", params);
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "生产命令执行失败");

            // 返回waitId，可能为null
            return (Integer) result.get("waitId");
        } catch (GameAPIError e) {
            if ("COMMAND_EXECUTION_ERROR".equals(e.getCode())) {
                return null;
            }
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("PRODUCTION_ERROR",
                    "执行生产命令时发生错误: " + e.getMessage());
        }
    }

    // 方法重载，提供默认参数
    public Integer produce(String unitType, int quantity) {
        return produce(unitType, quantity, false);
    }

    /**
     * 生产指定数量的Actor并等待生产完成
     *
     * @param unitType          Actor类型
     * @param quantity          生产数量
     * @param autoPlaceBuilding 是否在生产完成后使用随机位置自动放置建筑，仅对建筑类型有效
     * @throws GameAPIError 当生产或等待过程中发生错误时
     */
    public void produceWait(String unitType, int quantity, boolean autoPlaceBuilding) {
        try {
            Integer waitId = produce(unitType, quantity, autoPlaceBuilding);
            if (waitId != null) {
                wait(waitId, (double) (20 * quantity));
            } else {
                throw new GameAPIError("PRODUCTION_FAILED", "生产任务创建失败");
            }
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("PRODUCTION_WAIT_ERROR",
                    "生产并等待过程中发生错误: " + e.getMessage());
        }
    }

    // 方法重载，提供默认参数
    public void produceWait(String unitType, int quantity) {
        produceWait(unitType, quantity, true);
    }

    /**
     * 检查生产任务是否完成
     *
     * @param waitId 生产任务的ID
     * @return 是否完成
     * @throws GameAPIError 当查询任务状态失败时
     */
    public boolean isReady(int waitId) {
        try {
            Map<String, Object> response = sendRequest("query_wait_info",
                    Collections.singletonMap("waitId", waitId));
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询任务状态失败");
            return (boolean) result.getOrDefault("status", false);
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("WAIT_STATUS_ERROR",
                    "查询任务状态时发生错误: " + e.getMessage());
        }
    }

    /**
     * 等待生产任务完成
     *
     * @param waitId      生产任务的ID
     * @param maxWaitTime 最大等待时间（秒）
     * @return 是否成功完成等待（false表示超时）
     * @throws GameAPIError 当等待过程中发生错误时
     */
    public boolean wait(int waitId, double maxWaitTime) {
        try {
            log.info("waitId:{},maxWaitTime:{}",waitId,maxWaitTime);
            double waitTime = 0.0;
            double stepTime = 0.1;

            while (true) {
                Map<String, Object> response = sendRequest("query_wait_info",
                        Collections.singletonMap("waitId", waitId));
                Map<String, Object> result = (Map<String, Object>) handleResponse(response, "等待任务完成失败");
                log.info("result:{}",result.get("waitStatus"));
                if ("success".equals(result.get("waitStatus"))) {
                    return true;
                }

                try {
                    Thread.sleep((long) (stepTime * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new GameAPIError("WAIT_INTERRUPTED", "等待被中断");
                }

                waitTime += stepTime;
                log.info("waitTime:{},maxWaitTime:{},stepTime:{}",waitTime,maxWaitTime,stepTime);
                if (waitTime > maxWaitTime) {
                    return false;
                }
            }
        } catch (GameAPIError e) {
            if ("COMMAND_EXECUTION_ERROR".equals(e.getCode())) {
                return true;  // 特殊情况：如果命令执行错误，可能是任务已完成
            }
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("WAIT_ERROR",
                    "等待任务完成时发生错误: " + e.getMessage());
        }
    }

    // 方法重载，提供默认参数
    public boolean wait(int waitId) {
        return wait(waitId, 20.0);
    }

    /**
     * 移动单位到指定位置
     *
     * @param actors     要移动的Actor列表
     * @param location   目标位置
     * @param attackMove 是否为攻击性移动
     * @throws GameAPIError 当移动命令执行失败时
     */
    public void moveUnitsByLocation(List<Actor> actors, Location location, boolean attackMove) {
        try {
            // 收集所有Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));
            params.put("location", location.toMap());
            params.put("isAttackMove", attackMove ? 1 : 0);

            // 发送请求
            Map<String, Object> response = sendRequest("move_actor", params);
            handleResponse(response, "移动单位失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("MOVE_UNITS_ERROR",
                    "移动单位时发生错误: " + e.getMessage());
        }
    }

    // 方法重载，默认非攻击移动
    public void moveUnitsByLocation(List<Actor> actors, Location location) {
        moveUnitsByLocation(actors, location, false);
    }

    /**
     * 向指定方向移动单位
     *
     * @param actors    要移动的Actor列表
     * @param direction 移动方向
     * @param distance  移动距离
     * @throws GameAPIError 当移动命令执行失败时
     */
    public void moveUnitsByDirection(List<Actor> actors, String direction, int distance) {
        try {
            // 收集所有Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));
            params.put("direction", direction);
            params.put("distance", distance);

            // 发送请求
            Map<String, Object> response = sendRequest("move_actor", params);
            handleResponse(response, "移动单位失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("MOVE_UNITS_ERROR",
                    "移动单位时发生错误: " + e.getMessage());
        }
    }

    /**
     * 沿路径移动单位
     *
     * @param actors 要移动的Actor列表
     * @param path   移动路径
     * @throws GameAPIError 当移动命令执行失败时
     */
    public void moveUnitsByPath(List<Actor> actors, List<Location> path) {
        if (path == null || path.isEmpty()) {
            return;
        }

        try {
            // 收集所有Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 转换路径点为Map列表
            List<Map<String, Object>> pathPoints = new ArrayList<>();
            for (Location point : path) {
                pathPoints.add(point.toMap());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));
            params.put("path", pathPoints);

            // 发送请求
            Map<String, Object> response = sendRequest("move_actor", params);
            handleResponse(response, "移动单位失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("MOVE_UNITS_ERROR",
                    "移动单位时发生错误: " + e.getMessage());
        }
    }

    /**
     * 选中符合条件的Actor（游戏中的选中操作）
     *
     * @param queryParams 查询参数
     * @throws GameAPIError 当选择单位失败时
     */
    public void selectUnits(TargetsQueryParam queryParams) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("targets", queryParams.toMap());

            Map<String, Object> response = sendRequest("select_unit", params);
            handleResponse(response, "选择单位失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("SELECT_UNITS_ERROR",
                    "选择单位时发生错误: " + e.getMessage());
        }
    }

    /**
     * 将Actor编成编组
     *
     * @param actors  要分组的Actor列表
     * @param groupId 群组ID
     * @throws GameAPIError 当编组失败时
     */
    public void formGroup(List<Actor> actors, int groupId) {
        try {
            // 收集所有Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));
            params.put("groupId", groupId);

            // 发送请求
            Map<String, Object> response = sendRequest("form_group", params);
            handleResponse(response, "编组失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("FORM_GROUP_ERROR",
                    "编组时发生错误: " + e.getMessage());
        }
    }

    public List<Actor> queryActor(TargetsQueryParam queryParams) {
        try {
            Map<String, Object> response = sendRequest("query_actor",
                    Collections.singletonMap("targets", queryParams.toMap()));
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询Actor失败");

            List<Map<String, Object>> actorsData = (List<Map<String, Object>>) result.get("actors");
            List<Actor> actors = new ArrayList<>();

            for (Map<String, Object> data : actorsData) {
                try {
                    Actor actor = new Actor(((Number) data.get("id")).intValue());
                    Map<String, Object> positionData = (Map<String, Object>) data.get("position");
                    Location position = new Location(
                            ((Number) positionData.get("x")).intValue(),
                            ((Number) positionData.get("y")).intValue()
                    );

                    int hp = ((Number) data.get("hp")).intValue();
                    int maxHp = ((Number) data.get("maxHp")).intValue();
                    int hpPercent = maxHp > 0 ? hp * 100 / maxHp : -1;

                    actor.updateDetails(
                            (String) data.get("type"),
                            (String) data.get("faction"),
                            position,
                            hpPercent
                    );
                    actors.add(actor);
                } catch (Exception e) {
                    throw new GameAPIError("INVALID_ACTOR_DATA", "Actor数据格式无效: " + e.getMessage());
                }
            }

            return actors;
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("QUERY_ACTOR_ERROR", "查询Actor时发生错误: " + e.getMessage());
        }
    }

    /**
     * 为Actor找到到目标的路径
     *
     * @param actors      要移动的Actor列表
     * @param destination 目标位置
     * @param method      寻路方法，必须在 {"最短路","左路","右路"} 中
     * @return 路径点列表，第0个是目标点，最后一个是Actor当前位置
     * @throws GameAPIError 当寻路失败时
     */
    public List<Location> findPath(List<Actor> actors, Location destination, String method) {
        try {
            // 收集所有Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));
            params.put("destination", destination.toMap());
            params.put("method", method);

            // 发送请求
            Map<String, Object> response = sendRequest("query_path", params);
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "寻路失败");

            // 解析路径数据
            try {
                List<Map<String, Object>> pathData = (List<Map<String, Object>>) result.get("path");
                List<Location> path = new ArrayList<>();
                for (Map<String, Object> step : pathData) {
                    int x = ((Number) step.get("x")).intValue();
                    int y = ((Number) step.get("y")).intValue();
                    path.add(new Location(x, y));
                }
                return path;
            } catch (NullPointerException | ClassCastException e) {
                throw new GameAPIError("INVALID_PATH_DATA", "路径数据格式无效: " + e.getMessage());
            }
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("FIND_PATH_ERROR", "寻路时发生错误: " + e.getMessage());
        }
    }

    /**
     * 获取指定ID的Actor
     *
     * @param actorId Actor ID
     * @return 对应的Actor，如果不存在则返回null
     * @throws GameAPIError 当获取Actor失败时
     */
    public Actor getActorById(int actorId) {
        Actor actor = new Actor(actorId);
        try {
            if (updateActor(actor)) {
                return actor;
            }
            return null;
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("GET_ACTOR_ERROR", "获取Actor时发生错误: " + e.getMessage());
        }
    }

    /**
     * 更新Actor信息
     *
     * @param actor 要更新的Actor
     * @return 如果Actor已死会返回false，否则返回true
     * @throws GameAPIError 当更新Actor信息失败时
     */
    public boolean updateActor(Actor actor) {
        try {
            Map<String, Object> response = sendRequest("query_actor",
                    Collections.singletonMap("targets",
                            Collections.singletonMap("actorId",
                                    Collections.singletonList(actor.getActorId()))));

            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "更新Actor信息失败");

            try {
                List<Map<String, Object>> actorsData = (List<Map<String, Object>>) result.get("actors");
                Map<String, Object> actorData = actorsData.get(0);

                Map<String, Object> positionData = (Map<String, Object>) actorData.get("position");
                Location position = new Location(
                        ((Number) positionData.get("x")).intValue(),
                        ((Number) positionData.get("y")).intValue()
                );

                int hp = ((Number) actorData.get("hp")).intValue();
                int maxHp = ((Number) actorData.get("maxHp")).intValue();
                int hpPercent = maxHp > 0 ? hp * 100 / maxHp : -1;

                actor.updateDetails(
                        (String) actorData.get("type"),
                        (String) actorData.get("faction"),
                        position,
                        hpPercent
                );
                return true;
            } catch (IndexOutOfBoundsException | NullPointerException | ClassCastException e) {
                return false;
            }
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("UPDATE_ACTOR_ERROR", "更新Actor信息时发生错误: " + e.getMessage());
        }
    }

    /**
     * 部署/展开Actor
     *
     * @param actors 要部署/展开的Actor列表
     * @throws GameAPIError 当部署单位失败时
     */
    public void deployUnits(List<Actor> actors) throws GameAPIError {
        try {
            // 收集所有Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));

            // 发送请求
            Map<String, Object> response = sendRequest("deploy", params);
            handleResponse(response, "部署单位失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("DEPLOY_UNITS_ERROR",
                    "部署单位时发生错误: " + e.getMessage());
        }
    }

    /**
     * 将相机移动到指定Actor位置
     *
     * @param actor 目标Actor
     * @throws GameAPIError 当移动相机失败时
     */
    public void moveCameraTo(Actor actor) throws GameAPIError {
        try {
            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("actorId", actor.getActorId());

            // 发送请求
            Map<String, Object> response = sendRequest("view", params);
            handleResponse(response, "移动相机失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("CAMERA_MOVE_ERROR",
                    "移动相机时发生错误: " + e.getMessage());
        }
    }

    /**
     * 占领目标
     *
     * @param occupiers 执行占领的Actor列表
     * @param targets   被占领的目标列表
     * @throws GameAPIError 当占领行动失败时
     */
    public void occupyUnits(List<Actor> occupiers, List<Actor> targets) throws GameAPIError {
        try {
            // 收集占领者和目标Actor ID
            List<Integer> occupierIds = new ArrayList<>();
            for (Actor actor : occupiers) {
                occupierIds.add(actor.getActorId());
            }

            List<Integer> targetIds = new ArrayList<>();
            for (Actor actor : targets) {
                targetIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("occupiers", Collections.singletonMap("actorId", occupierIds));
            params.put("targets", Collections.singletonMap("actorId", targetIds));

            // 发送请求
            Map<String, Object> response = sendRequest("occupy", params);
            handleResponse(response, "占领行动失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("OCCUPY_ERROR",
                    "占领行动时发生错误: " + e.getMessage());
        }
    }

    /**
     * 攻击指定目标
     *
     * @param attacker 发起攻击的Actor
     * @param target   被攻击的目标
     * @return 是否成功发起攻击(如果目标不可见 ， 或者不可达 ， 或者攻击者已经死亡 ， 都会返回false)
     * @throws GameAPIError 当攻击命令执行失败时
     */
    public boolean attackTarget(Actor attacker, Actor target) throws GameAPIError {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("attackers", Collections.singletonMap("actorId",
                    Collections.singletonList(attacker.getActorId())));
            params.put("targets", Collections.singletonMap("actorId",
                    Collections.singletonList(target.getActorId())));

            Map<String, Object> response = sendRequest("attack", params);
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "攻击命令执行失败");
            return ((Number) result.getOrDefault("status", 0)).intValue() > 0;
        } catch (GameAPIError e) {
            if ("COMMAND_EXECUTION_ERROR".equals(e.getCode())) {
                return false;
            }
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("ATTACK_ERROR",
                    "攻击命令执行时发生错误: " + e.getMessage());
        }
    }

    /**
     * 检查是否可以攻击目标
     *
     * @param attacker 攻击者
     * @param target   目标
     * @return 是否可以攻击
     * @throws GameAPIError 当检查攻击能力失败时
     */
    public boolean canAttackTarget(Actor attacker, Actor target) throws GameAPIError {
        try {
            Map<String, Object> restrain = new HashMap<>();
            restrain.put("visible", true);

            Map<String, Object> params = new HashMap<>();
            params.put("targets", Map.of(
                    "actorId", Collections.singletonList(target.getActorId()),
                    "restrain", Collections.singletonList(restrain)
            ));

            Map<String, Object> response = sendRequest("query_actor", params);
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "检查攻击能力失败");

            List<?> actors = (List<?>) result.getOrDefault("actors", Collections.emptyList());
            return !actors.isEmpty();
        } catch (GameAPIError e) {
            return false;
        } catch (Exception e) {
            throw new GameAPIError("CHECK_ATTACK_ERROR",
                    "检查攻击能力时发生错误: " + e.getMessage());
        }
    }

    /**
     * 修复Actor
     *
     * @param actors 要修复的Actor列表，可以是载具或者建筑，修理载具需要修建修理中心
     * @throws GameAPIError 当修复命令执行失败时
     */
    public void repairUnits(List<Actor> actors) throws GameAPIError {
        try {
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));

            Map<String, Object> response = sendRequest("repair", params);
            handleResponse(response, "修复命令执行失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("REPAIR_ERROR",
                    "修复命令执行时发生错误: " + e.getMessage());
        }
    }

    /**
     * 停止Actor当前行动
     *
     * @param actors 要停止的Actor列表
     * @throws GameAPIError 当停止命令执行失败时
     */
    public void stop(List<Actor> actors) throws GameAPIError {
        try {
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));

            Map<String, Object> response = sendRequest("stop", params);
            handleResponse(response, "停止命令执行失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("STOP_ERROR",
                    "停止命令执行时发生错误: " + e.getMessage());
        }
    }

    /**
     * 查询位置是否可见
     *
     * @param location 要查询的位置
     * @return 是否可见
     * @throws GameAPIError 当查询可见性失败时
     */
    public boolean visibleQuery(Location location) throws GameAPIError {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("pos", location.toMap());

            Map<String, Object> response = sendRequest("fog_query", params);
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询可见性失败");
            return (boolean) result.getOrDefault("IsVisible", false);
        } catch (GameAPIError e) {
            return false;
        } catch (Exception e) {
            throw new GameAPIError("VISIBILITY_QUERY_ERROR",
                    "查询可见性时发生错误: " + e.getMessage());
        }
    }

    /**
     * 查询位置是否已探索
     *
     * @param location 要查询的位置
     * @return 是否已探索
     * @throws GameAPIError 当查询探索状态失败时
     */
    public boolean explorerQuery(Location location) throws GameAPIError {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("pos", location.toMap());

            Map<String, Object> response = sendRequest("fog_query", params);
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询探索状态失败");
            return (boolean) result.getOrDefault("IsExplored", false);
        } catch (GameAPIError e) {
            return false;
        } catch (Exception e) {
            throw new GameAPIError("EXPLORER_QUERY_ERROR",
                    "查询探索状态时发生错误: " + e.getMessage());
        }
    }

    /**
     * 查询指定类型的生产队列
     *
     * @param queueType 队列类型，必须是以下值之一：Building, Defense, Infantry, Vehicle, Aircraft, Naval
     * @return 包含队列信息的Map
     * @throws GameAPIError 当查询生产队列失败时
     */
    public Map<String, Object> queryProductionQueue(String queueType) throws GameAPIError {
        // 验证队列类型
        Set<String> validTypes = Set.of("Building", "Defense", "Infantry", "Vehicle", "Aircraft", "Naval");
        if (!validTypes.contains(queueType)) {
            throw new GameAPIError("INVALID_QUEUE_TYPE",
                    "队列类型必须是以下值之一: 'Building', 'Defense', 'Infantry', 'Vehicle', 'Aircraft', 'Naval'");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("queueType", queueType);

            Map<String, Object> response = sendRequest("query_production_queue", params);
            return (Map<String, Object>) handleResponse(response, "查询生产队列失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("PRODUCTION_QUEUE_QUERY_ERROR",
                    "查询生产队列时发生错误: " + e.getMessage());
        }
    }

    /**
     * 放置建造队列顶端已就绪的建筑
     *
     * @param queueType 队列类型，可选值：Building, Defense, Infantry, Vehicle, Aircraft, Naval
     * @param location  放置建筑的位置，如果不指定则使用自动选择的位置
     * @throws GameAPIError 当放置建筑失败时
     */
    public void placeBuilding(String queueType, Location location) throws GameAPIError {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("queueType", queueType);

            if (location != null) {
                params.put("location", location.toMap());
            }

            Map<String, Object> response = sendRequest("place_building", params);
            handleResponse(response, "放置建筑失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("PLACE_BUILDING_ERROR",
                    "放置建筑时发生错误: " + e.getMessage());
        }
    }

    // 方法重载，提供默认参数
    public void placeBuilding(String queueType) throws GameAPIError {
        placeBuilding(queueType, null);
    }

    /**
     * 管理生产队列中的项目（暂停/取消/继续）
     *
     * @param queueType 队列类型，可选值：'Building', 'Defense', 'Infantry', 'Vehicle', 'Aircraft', 'Naval'
     * @param action    操作类型，必须是 'pause', 'cancel', 或 'resume'
     * @throws GameAPIError 当管理生产队列失败时
     */
    public void manageProduction(String queueType, String action) throws GameAPIError {
        // 验证操作类型
        Set<String> validActions = Set.of("pause", "cancel", "resume");
        if (!validActions.contains(action)) {
            throw new GameAPIError("INVALID_ACTION",
                    "action参数必须是 'pause', 'cancel', 或 'resume'");
        }

        try {
            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("queueType", queueType);
            params.put("action", action);

            // 发送请求并处理响应
            Map<String, Object> response = sendRequest("manage_production", params);
            handleResponse(response, "管理生产队列失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("MANAGE_PRODUCTION_ERROR",
                    "管理生产队列时发生错误: " + e.getMessage());
        }
    }

    /**
     * 展开自己的基地车并等待一小会
     *
     * @param waitTime 展开后的等待时间(秒)，默认为1秒，已经够了，一般不用改
     * @throws GameAPIError 当操作失败时
     */
    public void deployMcvAndWait(double waitTime) throws GameAPIError {
        // 查询自己的基地车
        TargetsQueryParam queryParams = new TargetsQueryParam(
                Collections.singletonList("mcv"),
                "自己",
                null, null, null, null, null);

        List<Actor> mcv = queryActor(queryParams);

        // 如果没有找到基地车则直接返回
        if (mcv == null || mcv.isEmpty()) {
            return;
        }

        // 部署基地车
        deployUnits(mcv);

        // 等待指定时间
        try {
            Thread.sleep((long) (waitTime * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameAPIError("DEPLOY_INTERRUPTED", "部署过程被中断");
        }
    }

    // 方法重载，提供默认参数
    public void deployMcvAndWait() throws GameAPIError {
        deployMcvAndWait(1.0);
    }

    /**
     * 确保能生产某个建筑，如果不能会尝试生产所有前置建筑，并等待生产完成
     *
     * @param buildingName 建筑名称(中文)
     * @return 是否已经拥有该建筑或成功生产
     * @throws GameAPIError 当操作失败时
     */
    public boolean ensureCanBuildWait(String buildingName) throws GameAPIError {
        // 检查是否已有该建筑
        TargetsQueryParam queryParams = new TargetsQueryParam(
                List.of(buildingName), "自己", null, null, null, null, null);
        List<Actor> buildingExists = queryActor(queryParams);

        if (!buildingExists.isEmpty()) {
            return true;
        }

        // 检查并生产依赖建筑
//        List<String> deps = BUILDING_DEPENDENCIES.getOrDefault(buildingName, List.of());
//        for (String dep : deps) {
//            if (!ensureBuildingWaitBuildSelf(dep)) {
//                return false;
//            }
//        }

        return ensureBuildingWaitBuildSelf(buildingName);
    }


    /**
     * 内部方法 - 确保建筑存在（递归处理依赖）
     *
     * @param buildingName 建筑名称
     * @return 是否成功建造
     * @throws GameAPIError 当操作失败时
     */
    private boolean ensureBuildingWaitBuildSelf(String buildingName) throws GameAPIError {
        // 检查是否已有该建筑
//        TargetsQueryParam queryParams = new TargetsQueryParam(
//                List.of(buildingName), "自己", null, null, null, null, null);
//        List<Actor> buildingExists = queryActor(queryParams);
//
//        if (!buildingExists.isEmpty()) {
//            return true;
//        }
//
//        // 递归处理所有依赖
//        List<String> deps = BUILDING_DEPENDENCIES.getOrDefault(buildingName, List.of());
//        for (String dep : deps) {
//            ensureBuildingWaitBuildSelf(dep);
//        }

        // 尝试生产该建筑
        if (canProduce(buildingName)) {
            Integer waitId = produce(buildingName, 1, true);
            log.info("waitId:{}",waitId);
            if (waitId != null) {
                wait(waitId, 20.0); // 默认等待20秒
                return true;
            }
        }
        return false;
    }

    /**
     * 确保能生产某个单位（会自动生产其所需建筑并等待完成）
     *
     * @param unitName 单位名称(中文)
     * @return 是否成功准备好生产该单位
     * @throws GameAPIError 当操作失败时
     */
    public boolean ensureCanProduceUnit(String unitName) throws GameAPIError {
        // 如果可以直接生产则返回true
        if (canProduce(unitName)) {
            return true;
        }

        // 生产依赖建筑
        List<String> neededBuildings = UNIT_DEPENDENCIES.getOrDefault(unitName, List.of());
        for (String building : neededBuildings) {
            ensureBuildingWaitBuildSelf(building);
        }

        // 如果还是不能生产，等待1秒再检查
        if (!canProduce(unitName)) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GameAPIError("PRODUCE_INTERRUPTED", "生产准备过程被中断");
            }
        }

        return canProduce(unitName);
    }

    /**
     * 获取当前位置附近尚未探索的坐标列表
     *
     * @param mapQueryResult 地图信息
     * @param currentPos     当前Actor的位置
     * @param maxDistance    距离范围(曼哈顿)
     * @return 未探索位置列表
     */
    public List<Location> getUnexploredNearbyPositions(MapQueryResult mapQueryResult,
                                                       Location currentPos,
                                                       int maxDistance) {
        List<Location> neighbors = new ArrayList<>();

        for (int dx = -maxDistance; dx <= maxDistance; dx++) {
            for (int dy = -maxDistance; dy <= maxDistance; dy++) {
                // 检查曼哈顿距离
                if (Math.abs(dx) + Math.abs(dy) > maxDistance) {
                    continue;
                }
                // 跳过当前位置
                if (dx == 0 && dy == 0) {
                    continue;
                }

                int x = currentPos.getX() + dx;
                int y = currentPos.getY() + dy;

                // 检查边界
                if (x >= 0 && x < mapQueryResult.getMapWidth() &&
                        y >= 0 && y < mapQueryResult.getMapHeight()) {
                    // 检查是否未探索
                    if (!mapQueryResult.getIsExplored().get(x).get(y)) {
                        neighbors.add(new Location(x, y));
                    }
                }
            }
        }
        return neighbors;
    }

    /**
     * 移动一批Actor到指定位置，并等待(或直到超时)
     *
     * @param actors       要移动的Actor列表
     * @param location     目标位置
     * @param maxWaitTime  最大等待时间(秒)
     * @param toleranceDis 容忍的距离误差
     * @return 是否在maxWaitTime内到达(若中途卡住或超时则false)
     * @throws GameAPIError 当移动失败时
     */
    public boolean moveUnitsByLocationAndWait(List<Actor> actors,
                                              Location location,
                                              double maxWaitTime,
                                              int toleranceDis) throws GameAPIError {
        // 先移动单位
        moveUnitsByLocation(actors, location);

        long startTime = System.currentTimeMillis();

        while ((System.currentTimeMillis() - startTime) / 1000.0 < maxWaitTime) {
            boolean allArrived = true;

            // 检查所有单位是否到达
            for (Actor actor : actors) {
                updateActor(actor);
                if (actor.getPosition().manhattanDistance(location) > toleranceDis) {
                    allArrived = false;
                    break;
                }
            }

            if (allArrived) {
                return true;
            }

            // 等待0.3秒
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GameAPIError("MOVE_INTERRUPTED", "移动等待过程被中断");
            }
        }

        return false;
    }

    // 方法重载，提供默认参数
    public boolean moveUnitsByLocationAndWait(List<Actor> actors, Location location)
            throws GameAPIError {
        return moveUnitsByLocationAndWait(actors, location, 10.0, 1);
    }

    /**
     * 查询Actor的属性和攻击范围内目标
     *
     * @param actors 要查询的Actor列表
     * @return Actor属性信息，包括攻击范围内的目标
     * @throws GameAPIError 当查询失败时
     */
    public Map<String, Object> unitAttributeQuery(List<Actor> actors) throws GameAPIError {
        try {
            // 收集Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));

            // 发送请求
            Map<String, Object> response = sendRequest("unit_attribute_query", params);
            return (Map<String, Object>) handleResponse(response, "查询Actor属性失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("ATTRIBUTE_QUERY_ERROR",
                    "查询Actor属性时发生错误: " + e.getMessage());
        }
    }

    /**
     * 获取这些传入Actor攻击范围内的所有Target (已弃用，请使用unitAttributeQuery)
     *
     * @param actors 要查询的Actor列表
     * @return 攻击范围内的目标ID列表
     */
    @Deprecated
    public List<Integer> unitRangeQuery(List<Actor> actors) {
        try {
            Map<String, Object> result = unitAttributeQuery(actors);

            // 提取所有目标ID
            List<Integer> targets = new ArrayList<>();
            List<Map<String, Object>> attributes = (List<Map<String, Object>>)
                    result.getOrDefault("attributes", List.of());

            for (Map<String, Object> attr : attributes) {
                List<Integer> attrTargets = (List<Integer>)
                        attr.getOrDefault("targets", List.of());
                targets.addAll(attrTargets);
            }

            return targets;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 查询地图信息
     *
     * @return 地图查询结果
     * @throws GameAPIError 当查询地图信息失败时
     */
    public MapQueryResult mapQuery() throws GameAPIError {
        try {
            Map<String, Object> response = sendRequest("map_query", new HashMap<>());
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询地图信息失败");

            return new MapQueryResult(
                    ((Number) result.getOrDefault("MapWidth", 0)).intValue(),
                    ((Number) result.getOrDefault("MapHeight", 0)).intValue(),
                    (List<List<Integer>>) result.getOrDefault("Height", new ArrayList<>()),
                    (List<List<Boolean>>) result.getOrDefault("IsVisible", new ArrayList<>()),
                    (List<List<Boolean>>) result.getOrDefault("IsExplored", new ArrayList<>()),
                    (List<List<String>>) result.getOrDefault("Terrain", new ArrayList<>()),
                    (List<List<String>>) result.getOrDefault("ResourcesType", new ArrayList<>()),
                    (List<List<Integer>>) result.getOrDefault("Resources", new ArrayList<>())
            );
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("MAP_QUERY_ERROR",
                    "查询地图信息时发生错误: " + e.getMessage());
        }
    }

    /**
     * 查询玩家基地信息
     *
     * @return 玩家基地信息
     * @throws GameAPIError 当查询玩家基地信息失败时
     */
    public PlayerBaseInfo playerBaseInfoQuery() throws GameAPIError {
        try {
            Map<String, Object> response = sendRequest("player_baseinfo_query", new HashMap<>());
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询玩家基地信息失败");

            return new PlayerBaseInfo(
                    ((Number) result.getOrDefault("Cash", 0)).intValue(),
                    ((Number) result.getOrDefault("Resources", 0)).intValue(),
                    ((Number) result.getOrDefault("Power", 0)).intValue(),
                    ((Number) result.getOrDefault("PowerDrained", 0)).intValue(),
                    ((Number) result.getOrDefault("PowerProvided", 0)).intValue()
            );
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("BASE_INFO_QUERY_ERROR",
                    "查询玩家基地信息时发生错误: " + e.getMessage());
        }
    }

    /**
     * 查询当前玩家看到的屏幕信息
     *
     * @return 屏幕信息查询结果
     * @throws GameAPIError 当查询屏幕信息失败时
     */
    public ScreenInfoResult screenInfoQuery() throws GameAPIError {
        try {
            Map<String, Object> response = sendRequest("screen_info_query", new HashMap<>());
            Map<String, Object> result = (Map<String, Object>) handleResponse(response, "查询屏幕信息失败");

            // 解析ScreenMin
            Map<String, Object> screenMin = (Map<String, Object>) result.get("ScreenMin");
            Location screenMinLoc = new Location(
                    ((Number) screenMin.get("X")).intValue(),
                    ((Number) screenMin.get("Y")).intValue()
            );

            // 解析ScreenMax
            Map<String, Object> screenMax = (Map<String, Object>) result.get("ScreenMax");
            Location screenMaxLoc = new Location(
                    ((Number) screenMax.get("X")).intValue(),
                    ((Number) screenMax.get("Y")).intValue()
            );

            // 解析MousePosition
            Map<String, Object> mousePos = (Map<String, Object>) result.get("MousePosition");
            Location mousePosition = new Location(
                    ((Number) mousePos.get("X")).intValue(),
                    ((Number) mousePos.get("Y")).intValue()
            );

            return new ScreenInfoResult(
                    screenMinLoc,
                    screenMaxLoc,
                    (Boolean) result.getOrDefault("IsMouseOnScreen", false),
                    mousePosition
            );
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("SCREEN_INFO_QUERY_ERROR",
                    "查询屏幕信息时发生错误: " + e.getMessage());
        }
    }

    /**
     * 设置建筑的集结点
     *
     * @param actors         要设置集结点的建筑列表
     * @param targetLocation 集结点目标位置
     * @throws GameAPIError 当设置集结点失败时
     */
    public void setRallyPoint(List<Actor> actors, Location targetLocation) throws GameAPIError {
        try {
            // 收集Actor ID
            List<Integer> actorIds = new ArrayList<>();
            for (Actor actor : actors) {
                actorIds.add(actor.getActorId());
            }

            // 构建请求参数
            Map<String, Object> params = new HashMap<>();
            params.put("targets", Collections.singletonMap("actorId", actorIds));
            params.put("location", targetLocation.toMap());

            // 发送请求
            Map<String, Object> response = sendRequest("set_rally_point", params);
            handleResponse(response, "设置集结点失败");
        } catch (GameAPIError e) {
            throw e;
        } catch (Exception e) {
            throw new GameAPIError("SET_RALLY_POINT_ERROR",
                    "设置集结点时发生错误: " + e.getMessage());
        }
    }

    public static class GameAPIError extends RuntimeException {
        private final String code;
        private final String message;
        private final Map<String, Object> details;

        public GameAPIError(String code, String message) {
            this(code, message, null);
        }

        public GameAPIError(String code, String message, Map<String, Object> details) {
            super(code + ": " + message);
            this.code = code;
            this.message = message;
            this.details = details;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }
}
