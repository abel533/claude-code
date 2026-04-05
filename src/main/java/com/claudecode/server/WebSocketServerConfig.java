package com.claudecode.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Server Mode 的 WebSocket 配置 —— 仅在 --server 模式下激活。
 * <p>
 * 注册 {@link DirectConnectWebSocketHandler} 到 /ws 端点。
 * <p>
 * 对应 claude-code 的 Server Mode 功能：
 * <ul>
 *   <li>WebSocket 端点: ws://localhost:{port}/ws</li>
 *   <li>允许所有来源连接（本地开发用，生产环境应限制）</li>
 *   <li>最大消息 1MB</li>
 * </ul>
 */
public class WebSocketServerConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServerConfig.class);

    private final DirectConnectWebSocketHandler handler;

    public WebSocketServerConfig(DirectConnectWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .setAllowedOrigins("*");

        log.info("WebSocket handler registered at /ws");
    }

    /**
     * 配置 WebSocket 容器参数。
     */
    public static ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);  // 1MB
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        container.setMaxSessionIdleTimeout(300_000L);  // 5 分钟空闲超时
        return container;
    }
}
