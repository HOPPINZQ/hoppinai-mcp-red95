package com.hoppinzq.red95.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TargetsQueryParam {
    private List<String> type;  // 目标类型
    private String faction;  // 阵营
    private List<Integer> groupId;  // 组ID
    private List<Map<String, Object>> restrain;  // 约束条件
    private Location location;  // 位置
    private String direction;  // 方向
    private String range;  // 筛选范围

    // 将查询参数转换为Map表示
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type);
        map.put("faction", faction);
        map.put("groupId", groupId);
        map.put("restrain", restrain);
        map.put("location", location != null ? location.toMap() : null);
        map.put("direction", direction);
        map.put("range", range);
        return map;
    }

    @Override
    public String toString() {
        return "TargetsQueryParam{" +
                "type=" + type +
                ", faction='" + faction + '\'' +
                ", groupId=" + groupId +
                ", restrain=" + restrain +
                ", location=" + location +
                ", direction='" + direction + '\'' +
                ", range='" + range + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TargetsQueryParam that = (TargetsQueryParam) o;
        return Objects.equals(type, that.type) &&
                Objects.equals(faction, that.faction) &&
                Objects.equals(groupId, that.groupId) &&
                Objects.equals(restrain, that.restrain) &&
                Objects.equals(location, that.location) &&
                Objects.equals(direction, that.direction) &&
                Objects.equals(range, that.range);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, faction, groupId, restrain, location, direction, range);
    }
}
