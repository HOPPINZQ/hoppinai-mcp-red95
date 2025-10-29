package com.hoppinzq.red95.socket;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class GlobalSocketManager {
    private static volatile Socket socket;
    private static volatile PrintWriter out;
    private static volatile BufferedReader in;
    private static final ReentrantLock lock = new ReentrantLock();
    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private static final AtomicBoolean isRunning = new AtomicBoolean(true);
    
    // 配置参数
    private static String host = "localhost";
    private static int port = 7445;
    private static long reconnectInterval = 5000; // 重连间隔(毫秒)
    private static int maxReconnectAttempts = 10; // 最大重连次数
    
    // 状态监听器
    private static SocketStateListener stateListener;
    
    public interface SocketStateListener {
        void onConnected();
        void onDisconnected();
        void onError(Exception e);
        void onMessageReceived(String message);
    }
    
    // 初始化Socket管理器
    public static void initialize(String host, int port, SocketStateListener listener) {
        GlobalSocketManager.host = host;
        GlobalSocketManager.port = port;
        GlobalSocketManager.stateListener = listener;
        
        // 启动连接
        connect();
        
        // 启动心跳检测线程
        startHeartbeat();
        
        // 启动消息监听线程
        startMessageListener();
    }
    
    // 建立连接
    private static void connect() {
        if (isConnecting.get()) {
            return;
        }
        
        lock.lock();
        try {
            isConnecting.set(true);
            
            // 关闭旧连接
            closeResources();
            
            // 建立新连接
            socket = new Socket(host, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            isConnecting.set(false);
            
            // 通知连接成功
            if (stateListener != null) {
                stateListener.onConnected();
            }
            
            System.out.println("Socket连接成功: " + host + ":" + port);
            
        } catch (Exception e) {
            isConnecting.set(false);
            if (stateListener != null) {
                stateListener.onError(e);
            }
            System.err.println("Socket连接失败: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }
    
    // 发送消息
    public static boolean sendMessage(String message) {
        if (out == null || socket == null || !socket.isConnected() || socket.isClosed()) {
            System.err.println("Socket未连接，无法发送消息");
            return false;
        }
        
        lock.lock();
        try {
            out.println(message);
            return true;
        } catch (Exception e) {
            System.err.println("发送消息失败: " + e.getMessage());
            // 发送失败时尝试重连
            scheduleReconnect();
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    // 启动消息监听
    private static void startMessageListener() {
        Thread listenerThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    if (in != null && socket != null && socket.isConnected() && !socket.isClosed()) {
                        String message = in.readLine();
                        if (message != null) {
                            if (stateListener != null) {
                                stateListener.onMessageReceived(message);
                            }
                        } else {
                            // 读到null说明连接已断开
                            System.err.println("连接已断开，准备重连...");
                            scheduleReconnect();
                        }
                    } else {
                        Thread.sleep(1000);
                    }
                } catch (IOException e) {
                    System.err.println("读取消息异常: " + e.getMessage());
                    scheduleReconnect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Socket-Message-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }
    
    // 启动心跳检测
    private static void startHeartbeat() {
        Thread heartbeatThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    Thread.sleep(30000); // 30秒发送一次心跳
                    
                    if (!sendMessage("PING")) {
                        System.err.println("心跳发送失败，连接可能已断开");
                        scheduleReconnect();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Socket-Heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }
    
    // 安排重连
    private static void scheduleReconnect() {
        if (!isRunning.get()) {
            return;
        }
        
        new Thread(() -> {
            for (int attempt = 1; attempt <= maxReconnectAttempts && isRunning.get(); attempt++) {
                try {
                    System.out.println("尝试重连 (" + attempt + "/" + maxReconnectAttempts + ")...");
                    
                    if (stateListener != null) {
                        stateListener.onDisconnected();
                    }
                    
                    connect();
                    
                    if (isConnected()) {
                        System.out.println("重连成功");
                        break;
                    }
                    
                    Thread.sleep(reconnectInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Socket-Reconnector").start();
    }
    
    // 检查连接状态
    public static boolean isConnected() {
        return socket != null && 
               socket.isConnected() && 
               !socket.isClosed() && 
               !socket.isInputShutdown() && 
               !socket.isOutputShutdown();
    }
    
    // 关闭资源
    private static void closeResources() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.err.println("关闭资源时出错: " + e.getMessage());
        }
    }
    
    // 关闭Socket管理器
    public static void shutdown() {
        isRunning.set(false);
        closeResources();
        System.out.println("Socket管理器已关闭");
    }
    
    // 配置方法
    public static void setReconnectInterval(long interval) {
        reconnectInterval = interval;
    }
    
    public static void setMaxReconnectAttempts(int maxAttempts) {
        maxReconnectAttempts = maxAttempts;
    }
}