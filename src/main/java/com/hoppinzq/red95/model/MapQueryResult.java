package com.hoppinzq.red95.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 地图信息查询返回结构体
 * IsVisible 是当前视野可见的部分为 True
 * IsExplored 是探索过的格子为 True
 */
@Data
@AllArgsConstructor
@Builder
public class MapQueryResult {
    private final int mapWidth;  // 地图宽度
    private final int mapHeight;  // 地图高度
    private final List<List<Integer>> height;  // 每个格子的高度
    private final List<List<Boolean>> isVisible;  // 每个格子是否可见
    private final List<List<Boolean>> isExplored;  // 每个格子是否已探索
    private final List<List<String>> terrain;  // 每个格子的地形类型
    private final List<List<String>> resourcesType;  // 每个格子的资源类型
    private final List<List<Integer>> resources;  // 每个格子的资源数量

    /**
     * 根据位置获取指定网格中的值
     *
     * @param gridName 网格名称
     * @param location 位置
     * @return 网格中的值
     * @throws IllegalArgumentException 如果网格不存在或位置超出范围
     */
    public Object getValueAtLocation(String gridName, Location location) {
        List<?> grid;
        switch (gridName) {
            case "height":
                grid = height;
                break;
            case "isVisible":
                grid = isVisible;
                break;
            case "isExplored":
                grid = isExplored;
                break;
            case "terrain":
                grid = terrain;
                break;
            case "resourcesType":
                grid = resourcesType;
                break;
            case "resources":
                grid = resources;
                break;
            default:
                throw new IllegalArgumentException("网格 '" + gridName + "' 不存在。");
        }

        if (location.getX() < 0 || location.getX() >= grid.size() ||
                location.getY() < 0 || location.getY() >= ((List<?>) grid.get(0)).size()) {
            throw new IllegalArgumentException("位置超出范围。");
        }

        return ((List<?>) grid.get(location.getX())).get(location.getY());
    }

    @Override
    public String toString() {
        return "MapQueryResult{" +
                "mapWidth=" + mapWidth +
                ", mapHeight=" + mapHeight +
                ", height=" + height +
                ", isVisible=" + isVisible +
                ", isExplored=" + isExplored +
                ", terrain=" + terrain +
                ", resourcesType=" + resourcesType +
                ", resources=" + resources +
                '}';
    }
}
