package com.hoppinzq.red95.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Location {
    private int x;  // x 是地图中的水平偏移量
    private int y;  // y 是地图中的垂直偏移量

    // 两个位置相加，返回新位置
    public Location add(Location other) {
        if (other == null) {
            return null;
        }
        return new Location(this.x + other.x, this.y + other.y);
    }

    // 将位置的坐标按整数除法缩小，返回新位置
    public Location divide(int divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("0 不能作为除数");
        }
        return new Location(this.x / divisor, this.y / divisor);
    }

    // 将位置转换为Map表示
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("x", x);
        map.put("y", y);
        return map;
    }

    // 计算曼哈顿距离
    public int manhattanDistance(Location other) {
        if (other == null) {
            throw new IllegalArgumentException("位置不为空");
        }
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    // 计算欧几里得距离
    public double euclideanDistance(Location other) {
        if (other == null) {
            throw new IllegalArgumentException("位置不为空");
        }
        return Math.sqrt(Math.pow(this.x - other.x, 2) + Math.pow(this.y - other.y, 2));
    }

    @Override
    public String toString() {
        return "Location{" +
                "x=" + x +
                ", y=" + y +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Location location = (Location) o;
        return x == location.x && y == location.y;
    }

    @Override
    public int hashCode() {
        return 31 * x + y;
    }
}
