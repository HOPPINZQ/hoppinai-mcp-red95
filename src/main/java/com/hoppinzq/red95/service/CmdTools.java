package com.hoppinzq.red95.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class CmdTools {
    private static final String OPENRA_PATH="D:\\myProject\\github\\OpenRA";

    @Tool(name = "start_game",description = "开启游戏，仅当用户要求开启游戏时，才会使用该工具，注意：开启完游戏后，不要执行任何工具！！！")
    public String startGame(){
        try {
            String cmd = "cmd /c cd "+OPENRA_PATH +"&& .\\launch-game.cmd Game.Mod=copilot";
            Runtime runtime = Runtime.getRuntime();
            runtime.exec(cmd);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "ok";
    }
}
