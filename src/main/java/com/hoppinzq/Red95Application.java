package com.hoppinzq;

import com.hoppinzq.red95.service.CmdTools;
import com.hoppinzq.red95.service.Red95Tools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 使用请参考readme.md，有问题请微信：zhangqiff19
 */
@SpringBootApplication
public class Red95Application {

    public static void main(String[] args) {
        SpringApplication.run(Red95Application.class, args);
    }

    @Bean
    public List<ToolCallback> zqTools(Red95Tools red95Tools, CmdTools cmdTools) {
        return List.of(ToolCallbacks.from(red95Tools,cmdTools));
    }
}
