package com.claudecode.server;

import com.claudecode.core.AgentLoop;
import com.claudecode.core.TokenTracker;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket Handler —— Server Mode 的核心连接处理器。
 * <p>
 * 对应 claude-code/src/server/directConnectManager.ts 的服务端实现。
 * <p>
 * 每个 WebSocket 连接创建一个 {@link ServerSession}，包含独立的 AgentLoop。
 * 支持 Bearer Token 认证和多会话管理。
 */
public class DirectConnectWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DirectConnectWebSocketHandler.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final String systemPrompt;
    private final String authToken;
    private final int maxSessions;
    private final String model;

    /** 活跃会话：WebSocket sessionId → ServerSession */
    private final ConcurrentHashMap<String, ServerSession> sessions = new ConcurrentHashMap<>();

    public DirectConnectWebSocketHandler(ChatModel chatModel, ToolRegistry toolRegistry,
                                         ToolContext toolContext, String systemPrompt,
                                         String authToken, int maxSessions, String model) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.systemPrompt = systemPrompt;
        this.authToken = authToken;
        this.maxSessions = maxSessions;
        this.model = model;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        log.info("WebSocket connection attempt from: {}", wsSession.getRemoteAddress());

        // Bearer Token 认证
        if (authToken != null && !authToken.isBlank()) {
            if (!validateAuth(wsSession)) {
                wsSession.close(new CloseStatus(4001, "Unauthorized"));
                return;
            }
        }

        // 会话数限制
        if (maxSessions > 0 && sessions.size() >= maxSessions) {
            String errorMsg = ServerMessage.error(null, "max_sessions",
                    "Maximum sessions (" + maxSessions + ") reached");
            wsSession.sendMessage(new TextMessage(errorMsg));
            wsSession.close(new CloseStatus(4002, "Max sessions reached"));
            return;
        }

        // 创建新的 AgentLoop（每个连接独立实例）
        String sessionId = UUID.randomUUID().toString();
        AgentLoop agentLoop = new AgentLoop(chatModel, toolRegistry, toolContext, systemPrompt, new TokenTracker());

        // 创建 ServerSession
        ServerSession serverSession = new ServerSession(sessionId, agentLoop, wsSession);
        sessions.put(wsSession.getId(), serverSession);

        // 发送初始化消息
        serverSession.sendInitMessage(model);

        log.info("Server session created: {} (ws: {}, total: {})",
                sessionId, wsSession.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        ServerSession session = sessions.get(wsSession.getId());
        if (session == null) {
            log.warn("Message from unknown session: {}", wsSession.getId());
            wsSession.close(new CloseStatus(4003, "Unknown session"));
            return;
        }

        session.handleMessage(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) throws Exception {
        ServerSession session = sessions.remove(wsSession.getId());
        if (session != null) {
            session.close();
            log.info("Session closed: {} (status: {}, remaining: {})",
                    session.getSessionId(), status, sessions.size());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) throws Exception {
        log.error("WebSocket transport error for {}: {}", wsSession.getId(), exception.getMessage());
        ServerSession session = sessions.get(wsSession.getId());
        if (session != null) {
            try {
                String errorMsg = ServerMessage.error(session.getSessionId(),
                        "transport_error", exception.getMessage());
                wsSession.sendMessage(new TextMessage(errorMsg));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 验证 WebSocket 连接的 Bearer Token。
     * <p>
     * 支持两种方式：
     * <ul>
     *   <li>HTTP Header: {@code Authorization: Bearer <token>}</li>
     *   <li>Query Parameter: {@code ?token=<token>}</li>
     * </ul>
     */
    private boolean validateAuth(WebSocketSession wsSession) {
        // 方式1: 从 HTTP Header 获取
        var headers = wsSession.getHandshakeHeaders();
        String authHeader = headers.getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (authToken.equals(token)) {
                return true;
            }
        }

        // 方式2: 从 Query Parameter 获取
        URI uri = wsSession.getUri();
        if (uri != null && uri.getQuery() != null) {
            String query = uri.getQuery();
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String token = param.substring(6);
                    if (authToken.equals(token)) {
                        return true;
                    }
                }
            }
        }

        log.warn("Authentication failed for connection from: {}", wsSession.getRemoteAddress());
        return false;
    }

    /**
     * 关闭所有会话。
     */
    public void closeAllSessions() {
        sessions.values().forEach(ServerSession::close);
        sessions.clear();
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    public Map<String, ServerSession> getSessions() {
        return Map.copyOf(sessions);
    }
}
