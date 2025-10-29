package com.hoppinzq.red95.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlayerBaseInfo {
    //玩家持有的现金
    private int cash;
    //玩家持有的资源
    private int resources;
    //玩家当前剩余电力
    private int power;
    //玩家消耗的电力
    private int powerDrained;
    //玩家提供的电力
    private int powerProvided;
}

