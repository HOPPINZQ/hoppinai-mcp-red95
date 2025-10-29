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
public class ScreenInfoResult {
    private Location ScreenMin;  // 屏幕左上角的位置
    private Location ScreenMax;  // 屏幕右下角的位置
    private boolean IsMouseOnScreen;  // 鼠标是否在屏幕上
    private Location MousePosition;  // 鼠标当前位置

    // 将对象转换为Map表示
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("ScreenMin", ScreenMin != null ? ScreenMin.toMap() : null);
        map.put("ScreenMax", ScreenMax != null ? ScreenMax.toMap() : null);
        map.put("IsMouseOnScreen", IsMouseOnScreen);
        map.put("MousePosition", MousePosition != null ? MousePosition.toMap() : null);
        return map;
    }

    @Override
    public String toString() {
        return "ScreenInfoResult{" +
                "ScreenMin=" + ScreenMin +
                ", ScreenMax=" + ScreenMax +
                ", IsMouseOnScreen=" + IsMouseOnScreen +
                ", MousePosition=" + MousePosition +
                '}';
    }
}
