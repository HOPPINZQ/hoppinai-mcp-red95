package com.hoppinzq.red95.model;

import lombok.Builder;
import lombok.Data;

import java.util.Objects;

@Data
@Builder
public class Actor {
    private final int actorId;  // 单位ID，使用final确保不可变
    private String type;  // 单位类型
    private String faction;  // 阵营
    private Location position;  // 单位位置
    private Integer hpPercent;  // 生命值百分比

    // 构造函数
    public Actor(int actorId) {
        this.actorId = actorId;
    }

    public Actor(int actorId, String type, String faction, Location position, Integer hpPercent) {
        this.actorId = actorId;
        this.type = type;
        this.faction = faction;
        this.position = position;
        this.hpPercent = hpPercent;
    }

    // 更新单位的详细信息
    public void updateDetails(String type, String faction, Location position, Integer hpPercent) {
        this.type = type;
        this.faction = faction;
        this.position = position;
        this.hpPercent = hpPercent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Actor actor = (Actor) o;
        return actorId == actor.actorId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(actorId);
    }

    @Override
    public String toString() {
        return "Actor{" +
                "actorId=" + actorId +
                ", type='" + type + '\'' +
                ", faction='" + faction + '\'' +
                ", position=" + position +
                ", hpPercent=" + hpPercent +
                '}';
    }
}
