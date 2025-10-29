package com.hoppinzq.red95.socket;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.*;

public class SocketPool {
    private static final int MAX_POOL_SIZE = 10;
    private final BlockingQueue<Socket> pool = new LinkedBlockingQueue<>(MAX_POOL_SIZE);
    private final String host;
    private final int port;
    private ScheduledExecutorService heartbeatExecutor;

    public SocketPool(String host, int port) {
        this.host = host;
        this.port = port;
        initializePool();
    }

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            pool.forEach(socket -> {
                try {
                    socket.getOutputStream().write("心跳\n".getBytes());
                } catch (IOException ioException) {
                    reconnect(socket);
                }
            });
        }, 30, 30, TimeUnit.SECONDS); // 每30秒发送一次心跳
    }

    private void initializePool() {
        for (int i = 0; i < MAX_POOL_SIZE; i++) {
            pool.add(createNewSocket());
        }
    }

    private Socket createNewSocket() {
        try {
            Socket socket= new Socket(host, port);
            socket.setKeepAlive(true);    // 启用Keep-Alive
            socket.setSoTimeout(10000);
            return socket;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create socket", e);
        }
    }

    public Socket borrowSocket() throws InterruptedException {
        if(pool.size()==0){
            initializePool();
        }
        return pool.take();
    }

    public void returnSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            pool.offer(socket);
        }
    }

    private void reconnect(Socket socket) {
        close(socket);
        createNewSocket();
    }

    public void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public void closeAll() {
        pool.forEach(socket -> {
            close(socket);
        });
        pool.clear();
    }
}
