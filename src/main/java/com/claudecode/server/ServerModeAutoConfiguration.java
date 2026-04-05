package com.claudecode.server;

import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Server Mode 的自动配置 —— 仅在 claude-code.server-mode=true 时激活。
 * <p>
 * 由 {@link com.claudecode.ClaudeCodeApplication#main(String[])} 在检测到 --server 参数时
 * 设置 {@code claude-code.server-mode=true}，触发此配置类加载。
 * <p>
 * 注册的 Bean:
 * <ul>
 *   <li>{@link DirectConnectWebSocketHandler} — WebSocket 消息处理</li>
 *   <li>{@link DirectConnectServer} — 服务器生命周期管理</li>
 *   <li>{@link WebSocketConfigurer} — WebSocket 端点注册</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "claude-code.server-mode", havingValue = "true")
@EnableWebSocket
public class ServerModeAutoConfiguration implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ServerModeAutoConfiguration.class);

    @Autowired @Lazy
    private DirectConnectWebSocketHandler wsHandler;

    @Bean
    public DirectConnectWebSocketHandler directConnectWebSocketHandler(
            ChatModel activeChatModel,
            ToolRegistry toolRegistry,
            ToolContext toolContext,
            String systemPrompt) {

        String[] args = getApplicationArgs();
        String authToken = DirectConnectServer.parseAuthToken(args);
        int maxSessions = DirectConnectServer.parseMaxSessions(args);
        String model = System.getenv("AI_MODEL") != null
                ? System.getenv("AI_MODEL") : "claude-sonnet-4-20250514";

        log.info("Creating DirectConnect WebSocket handler (maxSessions={}, auth={})",
                maxSessions, authToken != null && !authToken.isBlank() ? "enabled" : "disabled");

        return new DirectConnectWebSocketHandler(
                activeChatModel, toolRegistry, toolContext,
                systemPrompt, authToken, maxSessions, model);
    }

    @Bean
    public DirectConnectServer directConnectServer(DirectConnectWebSocketHandler handler) {
        String[] args = getApplicationArgs();
        int port = DirectConnectServer.parsePort(args);
        String authToken = DirectConnectServer.parseAuthToken(args);
        int maxSessions = DirectConnectServer.parseMaxSessions(args);

        DirectConnectServer server = new DirectConnectServer(port, authToken, maxSessions, handler);
        server.onServerStarted();
        return server;
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        return WebSocketServerConfig.createWebSocketContainer();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws")
                .setAllowedOrigins("*");
        log.info("WebSocket handler registered at /ws endpoint");
    }

    private String[] getApplicationArgs() {
        String argsStr = System.getProperty("claude-code.server-args");
        if (argsStr != null && !argsStr.isBlank()) {
            return argsStr.split("\\s+");
        }
        return new String[0];
    }
}
