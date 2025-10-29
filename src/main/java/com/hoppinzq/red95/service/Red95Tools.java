package com.hoppinzq.red95.service;

import com.hoppinzq.red95.model.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class Red95Tools {

    private GameSocketAPI gameSocketAPI;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    //@Tool(name = "init_game",description = "初始化游戏和socket连接，连接到OpenRA游戏的Socket服务")
    public String init(){
        gameSocketAPI = new GameSocketAPI("localhost", 7445, "zh");
        try{
            gameSocketAPI.init();
        }catch (Exception exception){
            exception.printStackTrace();
        }
        return "ok";
    }

    @Tool(name = "is_game_run",description = "检查游戏服务是否在运行，通过心跳检测判断连接状态")
    public Boolean isGameRun(){
        init();
        return gameSocketAPI.isServerRunning();
    }

    @Tool(name = "get_game_state",description = "获取当前游戏状态，包括玩家资源、电力信息和屏幕内可见单位列表")
    public GameState getGameState(){
        init();
        PlayerBaseInfo playerBaseInfo = gameSocketAPI.playerBaseInfoQuery();
        List<Actor> actors = gameSocketAPI.queryActor(TargetsQueryParam.builder()
                        .type(Collections.emptyList())
                        .faction("任意")
                        .range("screen")
                        .restrain(Collections.singletonList(Map.of("visible",true)))
                .build());
        return GameState.builder()
                .actorList(actors).playerBaseInfo(playerBaseInfo)
                .build();
    }

    @Tool(name = "query_screen_info",description = "查询当前游戏屏幕信息，包括屏幕边界坐标、鼠标位置等")
    public ScreenInfoResult queryScreenInfo(){
        init();
        return gameSocketAPI.screenInfoQuery();
    }

    @Tool(name = "query_map_info",description = "查询地图的完整信息，包括地图尺寸、地形、资源分布、可见性等")
    public MapQueryResult queryMapInfo(){
        init();
        return gameSocketAPI.mapQuery();
    }


    @Tool(name = "query_player_info",description = "查询玩家基地的基础信息，包括金钱、资源、电力等")
    public PlayerBaseInfo queryPlayerInfo(){
        init();
        return gameSocketAPI.playerBaseInfoQuery();
    }

    @Tool(name = "deploy_mcv",description = "部署基地车，使其展开为基地建筑")
    public String deployMcv(){
        init();
        gameSocketAPI.deployMcvAndWait();
        return "ok";
    }

    /**
     * 注意：该方法将购买和构建建筑物，构建建筑物由AI随机在周围选择（一定能构建成功的区域），因为让AI传坐标很可能创建不了
     * 20s内会一直在轮询购买状态
     * 当钱不够且超过20s时，该方法将阻塞。当建筑物就绪时，必须显式要求AI使用placeBuild放置队列顶端已就绪的建筑物
     * @param building
     * @return
     */
    @Tool(name = "try_buy_building_and_build",description = "尝试购买并构建指定建筑物，会自动处理依赖建筑并等待完成，若资金不足则阻塞等待")
    public Boolean tryBuyBuild(@ToolParam(description = "建筑类型：支持的值包括'电厂'、'兵营'、'矿场'、'车间'、'雷达'、'维修中心'、'核电'、'科技中心'、'机场','喷火碉堡','特斯拉线圈','防空炮塔'") String building){
        init();
        gameSocketAPI.ensureCanBuildWait(building);
        return true;
    }


    @Tool(name = "place_building",description = "放置建造队列顶端已就绪的建筑，AI会自动选择合适位置进行放置")
    public String placeBuild(@ToolParam(description = "建筑类型：可选值为'Building'(建筑)、'Defense'(防御)、'Infantry'(步兵)、'Vehicle'(载具)、'Aircraft'(飞机)、'Naval'(船)") String type){
        init();
        gameSocketAPI.placeBuilding(type);
        return "ok";
    }


    @Tool(name = "try_buy_produce_unit", description = "确保能够生产指定单位，自动检查并建造所需的前置依赖建筑")
    public Boolean ensureCanProduceUnit(@ToolParam(description = "单位名称：游戏中单位的中文名称") String unitName) {
        init();
        return gameSocketAPI.ensureCanProduceUnit(unitName);
    }

    @Tool(name = "visible_units", description = "根据指定条件查询视野范围内可见的单位列表")
    public List<Map<String, Object>> visibleUnits(@ToolParam(description = "单位类型列表：要查询的单位中文名称列表，如['步兵','坦克']，可选值：士兵: 表示所有士兵，载具: 表示所有载具，坦克: 表示所有坦克，战斗单位: 表示所有除了采矿车和基地车以外的单位，建筑: 所有建筑") List<String> type,
                                               @ToolParam(description = "阵营名称：可选值为'己方'、'敌方'、'中立'、'任意'，默认传己方") String faction,
                                               @ToolParam(description = "筛选范围：可选值为'all'(全部)、'screen'(屏幕内)、'selected'(已选中)") String range,
                                               @ToolParam(description = "约束条件列表：如[{\"relativeDirection\":\"左上\",\"maxNum\":5}]表示左上方向最多5个单位",required = false) List<Map<String, Object>> restrain) {
        init();
        // 修复参数类型问题
        if (type == null) {
            type = Collections.emptyList();
        }
        if (restrain == null) {
            restrain = Collections.emptyList();
        }
        
        TargetsQueryParam params = TargetsQueryParam.builder()
                .type(type)
                .faction(faction)
                .range(range)
                .restrain(restrain)
                .build();
        
        List<Actor> units = gameSocketAPI.queryActor(params);
        return units.stream().map(u -> Map.of(
                "actor_id", u.getActorId(),
                "type", u.getType(),
                "faction", u.getFaction(),
                "position", Map.of("x", u.getPosition().getX(), "y", u.getPosition().getY()),
                "hpPercent", u.getHpPercent()
        )).toList();
    }

    @Tool(name = "produce", description = "在生产队列中添加指定类型和数量的单位生产任务，并返回生产任务ID")
    public int produce(@ToolParam(description = "单位类型：要生产的单位中文名称，可选值有：'步兵'，'火箭兵'，'工程师'，'手雷兵'，'矿车'，'防空车'，'基地车'，'重坦'，'v2'，'猛犸坦克'，'雅克战机'，'米格战机'") String unitType,
                       @ToolParam(description = "生产数量：要生产的单位数量，必须为正整数，默认为1") int quantity) {
        init();
        Integer waitId = gameSocketAPI.produce(unitType, quantity, true);
        return waitId != null ? waitId : -1;
    }

    @Tool(name = "move_units", description = "移动指定的单位列表到目标坐标位置")
    public String moveUnits(@ToolParam(description = "单位ID列表：要移动的单位ID集合") List<Integer> actorIds, 
                           @ToolParam(description = "目标X坐标：地图X轴坐标") int x, 
                           @ToolParam(description = "目标Y坐标：地图Y轴坐标") int y, 
                           @ToolParam(description = "是否攻击移动：true表示单位会在移动过程中自动攻击敌人，false为普通移动，默认是false") boolean attackMove) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        Location location = new Location(x, y);
        gameSocketAPI.moveUnitsByLocation(actors, location, attackMove);
        return "ok";
    }

    @Tool(name = "camera_move_to", description = "将游戏镜头直接移动到指定的地图坐标位置")
    public String cameraMoveTo(@ToolParam(description = "目标X坐标：地图X轴坐标") int x, 
                              @ToolParam(description = "目标Y坐标：地图Y轴坐标") int y) {
        init();
        gameSocketAPI.moveCameraByLocation(new Location(x, y));
        return "ok";
    }

    @Tool(name = "camera_move_dir", description = "按照指定方向和距离移动游戏镜头")
    public String cameraMoveDir(@ToolParam(description = "移动方向：可选值为'北'/'上'、'东北'/'右上'、'东'/'右'、'东南'/'右下'、'南'/'下'、'西南'/'左下'、'西'/'左'、'西北'/'左上'") String direction, 
                               @ToolParam(description = "移动距离：镜头移动的格子数，正整数") int distance) {
        init();
        gameSocketAPI.moveCameraByDirection(direction, distance);
        return "ok";
    }

    @Tool(name = "can_produce", description = "检查当前是否具备生产指定单位的条件（包括前置建筑和资源）")
    public boolean canProduce(@ToolParam(description = "单位类型：要检查的单位中文名称") String unitType) {
        init();
        return gameSocketAPI.canProduce(unitType);
    }

    @Tool(name = "move_units_by_location", description = "将指定单位列表移动到指定的地图坐标位置")
    public String moveUnitsByLocation(@ToolParam(description = "单位ID列表：要移动的单位ID集合") List<Integer> actorIds, 
                                    @ToolParam(description = "目标X坐标：地图X轴坐标") int x, 
                                    @ToolParam(description = "目标Y坐标：地图Y轴坐标") int y, 
                                    @ToolParam(description = "是否攻击移动：true表示单位会在移动过程中自动攻击敌人，false为普通移动，默认是false") boolean attackMove) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        gameSocketAPI.moveUnitsByLocation(actors, new Location(x, y), attackMove);
        return "ok";
    }

    @Tool(name = "move_units_by_direction", description = "按照指定方向和距离移动单位列表")
    public String moveUnitsByDirection(@ToolParam(description = "单位ID列表：要移动的单位ID集合") List<Integer> actorIds, 
                                     @ToolParam(description = "移动方向：可选值为'北'/'上'、'东北'/'右上'、'东'/'右'、'东南'/'右下'、'南'/'下'、'西南'/'左下'、'西'/'左'、'西北'/'左上'") String direction, 
                                     @ToolParam(description = "移动距离：单位移动的格子数，正整数") int distance) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        gameSocketAPI.moveUnitsByDirection(actors, direction, distance);
        return "ok";
    }

    @Tool(name = "move_units_by_path", description = "控制单位沿指定的路径点序列移动")
    public String moveUnitsByPath(@ToolParam(description = "单位ID列表：要移动的单位ID集合") List<Integer> actorIds, 
                                @ToolParam(description = "路径坐标点列表：路径点坐标数组，格式为[{\"x\":10,\"y\":20},{\"x\":15,\"y\":25}]，单位将依次经过每个坐标点") List<Map<String, Integer>> path) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        List<Location> locations = path.stream()
                .map(p -> new Location(p.get("x"), p.get("y")))
                .toList();
        gameSocketAPI.moveUnitsByPath(actors, locations);
        return "ok";
    }

    @Tool(name = "select_units", description = "在游戏中选中符合指定条件的单位")
    public String selectUnits(@ToolParam(description = "单位类型列表：要选择的单位中文名称列表，可选值：士兵: 表示所有士兵，载具: 表示所有载具，坦克: 表示所有坦克，战斗单位: 表示所有除了采矿车和基地车以外的单位，建筑: 所有建筑") List<String> type,
                            @ToolParam(description = "阵营名称：可选值为'己方'、'敌方'、'中立'、'任意'") String faction,
                            @ToolParam(description = "筛选范围：可选值为'all'(全部)、'screen'(屏幕内)、'selected'(已选中)") String range,
                            @ToolParam(description = "约束条件列表：如[{\"relativeDirection\":\"左上\",\"maxNum\":5}]表示左上方向最多5个单位",required = false) List<Map<String, Object>> restrain) {
        init();
        TargetsQueryParam params = TargetsQueryParam.builder()
                .type(type != null ? type : Collections.emptyList())
                .faction(faction)
                .range(range)
                .restrain(restrain != null ? restrain : Collections.emptyList())
                .build();
        gameSocketAPI.selectUnits(params);
        return "ok";
    }

    @Tool(name = "form_group", description = "将指定的单位列表编入指定的游戏编队")
    public String formGroup(@ToolParam(description = "单位ID列表：要编组的单位ID集合") List<Integer> actorIds, 
                           @ToolParam(description = "组ID：游戏中的编队编号，建议在1-10范围内") int groupId) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        gameSocketAPI.formGroup(actors, groupId);
        return "ok";
    }

//    @Tool(name = "query_actor", description = "查询符合指定条件的单位列表及其详细信息")
//    public List<Map<String, Object>> queryActor(@ToolParam(description = "单位类型列表：要查询的单位中文名称列表，可选值：士兵: 表示所有士兵，载具: 表示所有载具，坦克: 表示所有坦克，战斗单位: 表示所有除了采矿车和基地车以外的单位，建筑: 所有建筑") List<String> type,
//                                              @ToolParam(description = "阵营名称：可选值为'己方'、'敌方'、'中立'、'任意'") String faction,
//                                              @ToolParam(description = "筛选范围：可选值为'all'(全部)、'screen'(屏幕内)、'selected'(已选中)") String range,
//                                              @ToolParam(description = "约束条件列表：如[{\"relativeDirection\":\"左上\",\"maxNum\":5}]表示左上方向最多5个单位",required = false) List<Map<String, Object>> restrain) {
//        init();
//        TargetsQueryParam params = TargetsQueryParam.builder()
//                .type(type != null ? type : Collections.emptyList())
//                .faction(faction)
//                .range(range)
//                .restrain(restrain != null ? restrain : Collections.emptyList())
//                .build();
//
//        List<Actor> actors = gameSocketAPI.queryActor(params);
//        return actors.stream().map(u -> Map.of(
//                "actor_id", u.getActorId(),
//                "type", u.getType(),
//                "faction", u.getFaction(),
//                "position", Map.of("x", u.getPosition().getX(), "y", u.getPosition().getY()),
//                "hpPercent", u.getHpPercent()
//        )).toList();
//    }

    @Tool(name = "attack", description = "命令指定攻击者单位攻击目标单位")
    public boolean attack(@ToolParam(description = "攻击者单位ID：执行攻击的单位ID") int attackerId, 
                         @ToolParam(description = "目标单位ID：被攻击的单位ID") int targetId) {
        init();
        Actor attacker = new Actor(attackerId);
        Actor target = new Actor(targetId);
        return gameSocketAPI.attackTarget(attacker, target);
    }

    @Tool(name = "occupy", description = "命令占领者单位占领指定的目标建筑")
    public String occupy(@ToolParam(description = "占领者单位ID列表：执行占领操作的单位ID集合，通常是工程师等特殊单位") List<Integer> occupierIds, 
                        @ToolParam(description = "目标单位ID列表：要占领的建筑单位ID集合") List<Integer> targetIds) {
        init();
        List<Actor> occupiers = occupierIds.stream().map(Actor::new).toList();
        List<Actor> targets = targetIds.stream().map(Actor::new).toList();
        gameSocketAPI.occupyUnits(occupiers, targets);
        return "ok";
    }

    @Tool(name = "find_path", description = "计算从单位当前位置到目标位置的路径")
    public List<Map<String, Integer>> findPath(@ToolParam(description = "单位ID列表：要寻路的单位ID集合") List<Integer> actorIds, 
                                             @ToolParam(description = "目标X坐标：路径终点的X坐标") int destX, 
                                             @ToolParam(description = "目标Y坐标：路径终点的Y坐标") int destY, 
                                             @ToolParam(description = "寻路方法：可选值为'shortest'(最短路径)、'Left'(左路)、'Right'(右路)") String method) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        List<Location> path = gameSocketAPI.findPath(actors, new Location(destX, destY), method);
        return path.stream().map(p -> Map.of("x", p.getX(), "y", p.getY())).toList();
    }

    @Tool(name = "get_actor_by_id", description = "根据单位ID获取单个单位的详细信息")
    public Map<String, Object> getActorById(@ToolParam(description = "单位ID：要查询的单位唯一标识") int actorId) {
        init();
        Actor actor = gameSocketAPI.getActorById(actorId);
        if (actor == null) {
            return null;
        }
        
        return Map.of(
                "actor_id", actor.getActorId(),
                "type", actor.getType(),
                "faction", actor.getFaction(),
                "position", Map.of("x", actor.getPosition().getX(), "y", actor.getPosition().getY()),
                "hpPercent", actor.getHpPercent()
        );
    }

    @Tool(name = "update_actor", description = "更新指定单位的信息并返回其最新状态")
    public Map<String, Object> updateActor(@ToolParam(description = "单位ID：要更新的单位唯一标识") int actorId) {
        init();
        Actor actor = new Actor(actorId);
        boolean success = gameSocketAPI.updateActor(actor);
        if (!success) {
            return null;
        }
        
        return Map.of(
                "actor_id", actor.getActorId(),
                "type", actor.getType(),
                "faction", actor.getFaction(),
                "position", Map.of("x", actor.getPosition().getX(), "y", actor.getPosition().getY()),
                "hpPercent", actor.getHpPercent()
        );
    }

    @Tool(name = "deploy_units", description = "部署或展开指定的单位列表（如基地车、战斗要塞等可变形单位）")
    public String deployUnits(@ToolParam(description = "单位ID列表：要部署的单位ID集合") List<Integer> actorIds) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        gameSocketAPI.deployUnits(actors);
        return "ok";
    }

    @Tool(name = "move_camera_to_actor", description = "将游戏镜头移动到指定单位的当前位置")
    public String moveCameraToActor(@ToolParam(description = "单位ID：目标单位的唯一标识") int actorId) {
        init();
        gameSocketAPI.moveCameraTo(new Actor(actorId));
        return "ok";
    }

    @Tool(name = "occupy_units", description = "命令占领者单位占领指定的目标建筑单位")
    public String occupyUnits(@ToolParam(description = "占领者单位ID列表：执行占领操作的单位ID集合，通常是工程师等特殊单位") List<Integer> occupierIds, 
                            @ToolParam(description = "目标单位ID列表：要占领的建筑单位ID集合") List<Integer> targetIds) {
        init();
        List<Actor> occupiers = occupierIds.stream().map(Actor::new).toList();
        List<Actor> targets = targetIds.stream().map(Actor::new).toList();
        gameSocketAPI.occupyUnits(occupiers, targets);
        return "ok";
    }

    @Tool(name = "attack_target", description = "命令指定的攻击者单位对目标单位发起攻击")
    public boolean attackTarget(@ToolParam(description = "攻击者单位ID：执行攻击的单位ID") int attackerId, 
                               @ToolParam(description = "目标单位ID：被攻击的单位ID") int targetId) {
        init();
        Actor attacker = new Actor(attackerId);
        Actor target = new Actor(targetId);
        return gameSocketAPI.attackTarget(attacker, target);
    }

//    @Tool(name = "can_attack_target", description = "检查指定的攻击者单位是否能够攻击目标单位（基于攻击范围和类型兼容性）")
//    public boolean canAttackTarget(@ToolParam(description = "攻击者单位ID：潜在攻击者的单位ID") int attackerId,
//                                  @ToolParam(description = "目标单位ID：潜在被攻击目标的单位ID") int targetId) {
//        init();
//        Actor attacker = new Actor(attackerId);
//        Actor target = new Actor(targetId);
//        return gameSocketAPI.canAttackTarget(attacker, target);
//    }

    @Tool(name = "repair_units", description = "命令维修单位或开始自动修复指定的受损单位/建筑")
    public String repairUnits(@ToolParam(description = "单位ID列表：需要修复的单位或建筑ID集合") List<Integer> actorIds) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        gameSocketAPI.repairUnits(actors);
        return "ok";
    }

    @Tool(name = "stop_units", description = "停止指定单位的当前所有行动")
    public String stopUnits(@ToolParam(description = "单位ID列表：要停止行动的单位ID集合") List<Integer> actorIds) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        gameSocketAPI.stop(actors);
        return "ok";
    }

    @Tool(name = "visible_query", description = "检查指定地图坐标是否在玩家当前视野范围内")
    public boolean visibleQuery(@ToolParam(description = "查询X坐标：要检查的地图X轴坐标") int x, 
                               @ToolParam(description = "查询Y坐标：要检查的地图Y轴坐标") int y) {
        init();
        return gameSocketAPI.visibleQuery(new Location(x, y));
    }

    @Tool(name = "explorer_query", description = "检查指定地图坐标是否已经被玩家探索过（即使当前不在视野中）")
    public boolean explorerQuery(@ToolParam(description = "查询X坐标：要检查的地图X轴坐标") int x, 
                                @ToolParam(description = "查询Y坐标：要检查的地图Y轴坐标") int y) {
        init();
        return gameSocketAPI.explorerQuery(new Location(x, y));
    }

    @Tool(name = "query_production_queue", description = "查询指定类型生产队列的当前状态和所有生产项目")
    public Map<String, Object> queryProductionQueue(@ToolParam(description = "队列类型：可选值为'Building'(建筑)、'Defense'(防御建筑)、'Infantry'(步兵)、'Vehicle'(载具)、'Aircraft'(飞机)、'Naval'(船)") String queueType) {
        init();
        return gameSocketAPI.queryProductionQueue(queueType);
    }

    @Tool(name = "manage_production", description = "管理指定生产队列，对队列中的项目执行暂停、继续或取消操作")
    public String manageProduction(@ToolParam(description = "队列类型：可选值为'Building'(建筑)、'Defense'(防御建筑)、'Infantry'(步兵)、'Vehicle'(载具)、'Aircraft'(飞机)、'Naval'(船)") String queueType,
                                   @ToolParam(description = "操作类型：可选值为'pause'(暂停)、'resume'(继续)、'cancel'(取消)") String action) {
        init();
        gameSocketAPI.manageProduction(queueType, action);
        return "ok";
    }

    @Tool(name = "get_unexplored_nearby_positions", description = "获取指定位置附近尚未被探索的地图坐标列表，用于探路决策")
    public List<Map<String, Integer>> getUnexploredNearbyPositions(@ToolParam(description = "地图查询结果：通过query_map_info获取的完整地图信息") Map<String, Object> mapResult,
                                                                 @ToolParam(description = "当前X坐标：搜索中心点的X坐标") int currentX,
                                                                 @ToolParam(description = "当前Y坐标：搜索中心点的Y坐标") int currentY,
                                                                 @ToolParam(description = "最大搜索距离：从中心点向外搜索的最大距离（格子数）") int maxDistance) {
        init();
        MapQueryResult mapQueryResult= MapQueryResult.builder()
                .mapWidth((int) mapResult.get("width"))
                .mapHeight((int) mapResult.get("height"))
                .height((List<List<Integer>>) mapResult.get("heightMap"))
                .isVisible((List<List<Boolean>>) mapResult.get("visible"))
                .isExplored((List<List<Boolean>>) mapResult.get("explored"))
                .terrain((List<List<String>>) mapResult.get("terrain"))
                .resourcesType((List<List<String>>) mapResult.get("resourcesType"))
                .resources((List<List<Integer>>) mapResult.get("resources"))
                .build();

        List<Location> locations = gameSocketAPI.getUnexploredNearbyPositions(
                mapQueryResult, new Location(currentX, currentY), maxDistance
        );
        
        return locations.stream().map(loc -> Map.of("x", loc.getX(), "y", loc.getY())).toList();
    }

    @Tool(name = "move_units_and_wait", description = "移动单位到目标位置并阻塞等待，直到单位到达目标位置或超过最大等待时间")
    public boolean moveUnitsAndWait(@ToolParam(description = "单位ID列表：要移动的单位ID集合") List<Integer> actorIds,
                                   @ToolParam(description = "目标X坐标：目标位置的X坐标") int x,
                                   @ToolParam(description = "目标Y坐标：目标位置的Y坐标") int y,
                                   @ToolParam(description = "最大等待时间（秒）：等待单位到达的最长时间") double maxWaitTime,
                                   @ToolParam(description = "容差距离：单位到达离目标位置多远时视为已到达") int toleranceDis) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        return gameSocketAPI.moveUnitsByLocationAndWait(actors, new Location(x, y), maxWaitTime, toleranceDis);
    }

//    @Tool(name = "unit_attribute_query", description = "查询指定单位的详细属性信息，包括速度、攻击范围和可攻击目标等")
//    public Map<String, Object> unitAttributeQuery(@ToolParam(description = "单位ID列表：要查询属性的单位ID集合") List<Integer> actorIds) {
//        init();
//        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
//        return gameSocketAPI.unitAttributeQuery(actors);
//    }

    @Tool(name = "set_rally_point", description = "为指定的生产建筑设置集结点，生产出的单位将自动移动到该位置")
    public String setRallyPoint(@ToolParam(description = "建筑单位ID列表：要设置集结点的生产建筑ID集合") List<Integer> actorIds,
                                @ToolParam(description = "集结点X坐标：集结点的X坐标") int x,
                                @ToolParam(description = "集结点Y坐标：集结点的Y坐标") int y) {
        init();
        List<Actor> actors = actorIds.stream().map(Actor::new).toList();
        gameSocketAPI.setRallyPoint(actors, new Location(x, y));
        return "ok";
    }

    /**
     * 延迟执行任务，测试用，如：展开基地车，然后延迟1s购买电厂。因为基地车展开要一段时间
     * @param task
     * @param delaySeconds
     */
    private static void executeWithDelay(Runnable task, Long delaySeconds) {
        scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
    }

}

