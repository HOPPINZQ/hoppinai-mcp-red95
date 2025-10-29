package com.hoppinzq.red95.socket;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.stereotype.Component;

//@Component
@Slf4j
public class SocketService {

    @Resource
    private ApplicationContext applicationContext;

    @PostConstruct
    public void init() {
        GlobalSocketManager.initialize("localhost", 7445, new GlobalSocketManager.SocketStateListener() {
            @Override
            public void onConnected() {
                log.info("Socket连接成功");
                // 可以发布Spring事件
                applicationContext.publishEvent(new SocketConnectedEvent(this));
            }
            
            @Override
            public void onDisconnected() {
                log.warn("Socket连接断开");
                applicationContext.publishEvent(new SocketDisconnectedEvent(this));
            }
            
            @Override
            public void onError(Exception e) {
                log.error("Socket错误", e);
            }
            
            @Override
            public void onMessageReceived(String message) {
                log.info("收到消息: {}", message);
                // 处理业务逻辑
                processMessage(message);
            }
        });
    }
    
    @PreDestroy
    public void destroy() {
        GlobalSocketManager.shutdown();
    }
    
    public void sendMessage(String message) {
        GlobalSocketManager.sendMessage(message);
    }
    
    public boolean isConnected() {
        return GlobalSocketManager.isConnected();
    }
    
    private void processMessage(String message) {
        // 处理接收到的消息
    }
}

// Spring事件类
class SocketConnectedEvent extends ApplicationEvent {
    public SocketConnectedEvent(Object source) {
        super(source);
    }
}

class SocketDisconnectedEvent extends ApplicationEvent {
    public SocketDisconnectedEvent(Object source) {
        super(source);
    }
}