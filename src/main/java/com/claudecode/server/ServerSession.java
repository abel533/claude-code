package com.claudecode.server;

import com.claudecode.core.AgentLoop;
import com.claudecode.permission.PermissionTypes.PermissionChoice;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 WebSocket 客户端的会话管理 —— 对应 TS 端 DirectConnectSessionManager 的服务端对应。
 * <p>
 * 每个 ServerSession 包装一个 AgentLoop 实例，
 * 将 WebSocket 消息转化为 AgentLoop 的调用，并将结果回传。
 * <p>
 * 权限请求通过 WebSocket 转发给客户端：
 * <ol>
 *   <li>AgentLoop 触发 onPermissionRequest 回调</li>
 *   <li>ServerSession 发送 control_request 消息给客户端</li>
 *   <li>客户端回复 control_response 消息</li>
 *   <li>ServerSession 将结果返回给 AgentLoop</li>
 * </ol>
 */
public class ServerSession {

    private static final Logger log = LoggerFactory.getLogger(ServerSession.class);

    private final String sessionId;
    private final AgentLoop agentLoop;
    private final WebSocketSession wsSession;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    /** 权限请求的异步等待队列：requestId → CompletableFuture<PermissionChoice> */
    private final ConcurrentHashMap<String, CompletableFuture<PermissionChoice>> pendingPermissions
            = new ConcurrentHashMap<>();

    /** 会话线程池（处理用户消息，每个会话一个线程） */
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("server-session-", 0).factory()
    );

    /** Keep-alive 调度器 */
    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("keep-alive-", 0).factory()
    );

    private ScheduledFuture<?> keepAliveTask;

    public ServerSession(String sessionId, AgentLoop agentLoop, WebSocketSession wsSession) {
        this.sessionId = sessionId;
        this.agentLoop = agentLoop;
        this.wsSession = wsSession;

        // 注册 AgentLoop 回调：将事件转发到 WebSocket
        setupAgentCallbacks();
        startKeepAlive();
    }

    private void setupAgentCallbacks() {
        // 助手文本回调 → 发送 assistant 消息
        agentLoop.setOnAssistantMessage(text -> {
            try {
                sendMessage(ServerMessage.assistantMessage(sessionId, text, false));
            } catch (Exception e) {
                log.error("[{}] Failed to send assistant message", sessionId, e);
            }
        });

        // 流式输出回调 → 发送流式 assistant 消息（使用 onStreamStart）
        agentLoop.setOnStreamStart(() -> {
            // 不需要特殊处理，流式 token 通过 streaming consumer 发送
        });

        // 工具事件回调 → 发送 tool_use 消息
        agentLoop.setOnToolEvent(event -> {
            try {
                var toolInfo = new ServerMessage.ToolCallInfo(
                        null, event.toolName(), event.arguments(), event.result(),
                        switch (event.phase()) {
                            case START -> "running";
                            case END -> "completed";
                            case PROGRESS -> "running";
                        }
                );
                sendMessage(ServerMessage.assistantToolUse(sessionId, java.util.List.of(toolInfo)));
            } catch (Exception e) {
                log.error("[{}] Failed to send tool event", sessionId, e);
            }
        });

        // 权限请求回调 → 转发到 WebSocket 客户端
        agentLoop.setOnPermissionRequest(req -> {
            try {
                return forwardPermissionRequest(req);
            } catch (Exception e) {
                log.error("[{}] Permission request failed", sessionId, e);
                return PermissionChoice.DENY_ONCE;
            }
        });

        // Thinking 内容 → 发送系统消息
        agentLoop.setOnThinkingContent(thinking -> {
            try {
                sendMessage(ServerMessage.systemEvent(sessionId, "thinking", null, thinking));
            } catch (Exception e) {
                log.debug("[{}] Failed to send thinking content", sessionId, e);
            }
        });
    }

    /**
     * 将权限请求转发到 WebSocket 客户端，同步等待响应。
     */
    private PermissionChoice forwardPermissionRequest(AgentLoop.PermissionRequest req) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<PermissionChoice> future = new CompletableFuture<>();
        pendingPermissions.put(requestId, future);

        try {
            // 发送 control_request
            String msg = ServerMessage.controlRequest(
                    sessionId, req.toolName(), req.arguments(), req.activityDescription());
            sendMessage(msg);

            // 等待客户端响应（超时 60 秒）
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[{}] Permission request timed out for {}", sessionId, req.toolName());
            return PermissionChoice.DENY_ONCE;
        } catch (Exception e) {
            log.error("[{}] Permission request error", sessionId, e);
            return PermissionChoice.DENY_ONCE;
        } finally {
            pendingPermissions.remove(requestId);
        }
    }

    /**
     * 处理客户端发来的消息。
     */
    public void handleMessage(String rawJson) {
        try {
            String type = ServerMessage.getType(rawJson);
            if (type == null) {
                sendMessage(ServerMessage.error(sessionId, "invalid_message", "Missing message type"));
                return;
            }

            switch (type) {
                case ServerMessage.TYPE_USER -> handleUserMessage(rawJson);
                case ServerMessage.TYPE_CONTROL_RESPONSE -> handleControlResponse(rawJson);
                case ServerMessage.TYPE_INTERRUPT -> handleInterrupt();
                case ServerMessage.TYPE_KEEP_ALIVE -> {} // 忽略
                default -> sendMessage(ServerMessage.error(sessionId, "unknown_type",
                        "Unknown message type: " + type));
            }
        } catch (Exception e) {
            log.error("[{}] Message handling error", sessionId, e);
            try {
                sendMessage(ServerMessage.error(sessionId, "internal_error", e.getMessage()));
            } catch (Exception ignored) {}
        }
    }

    private void handleUserMessage(String rawJson) throws Exception {
        if (processing.get()) {
            sendMessage(ServerMessage.error(sessionId, "busy",
                    "Session is currently processing a message. Send interrupt first."));
            return;
        }

        JsonNode payload = ServerMessage.getPayload(rawJson);
        if (payload == null || !payload.has("content")) {
            sendMessage(ServerMessage.error(sessionId, "invalid_payload", "Missing content in user message"));
            return;
        }

        String content = payload.get("content").asText();
        processing.set(true);

        // 在虚拟线程中异步执行 AgentLoop
        sessionExecutor.submit(() -> {
            try {
                // 使用流式模式，将每个 token 实时转发
                String result = agentLoop.runStreaming(content, token -> {
                    try {
                        sendMessage(ServerMessage.assistantMessage(sessionId, token, true));
                    } catch (Exception e) {
                        log.error("[{}] Failed to stream token", sessionId, e);
                    }
                });

                // 发送最终结果
                var tracker = agentLoop.getTokenTracker();
                sendMessage(ServerMessage.resultMessage(
                        sessionId, result, 0,
                        tracker.getInputTokens(), tracker.getOutputTokens()));
            } catch (Exception e) {
                log.error("[{}] Agent loop execution error", sessionId, e);
                try {
                    sendMessage(ServerMessage.error(sessionId, "execution_error", e.getMessage()));
                } catch (Exception ignored) {}
            } finally {
                processing.set(false);
            }
        });
    }

    private void handleControlResponse(String rawJson) throws Exception {
        JsonNode payload = ServerMessage.getPayload(rawJson);
        if (payload == null || !payload.has("request_id") || !payload.has("behavior")) {
            sendMessage(ServerMessage.error(sessionId, "invalid_payload",
                    "Missing request_id or behavior in control_response"));
            return;
        }

        String requestId = payload.get("request_id").asText();
        String behavior = payload.get("behavior").asText();

        CompletableFuture<PermissionChoice> future = pendingPermissions.get(requestId);
        if (future != null) {
            PermissionChoice choice = "allow".equals(behavior)
                    ? PermissionChoice.ALLOW_ONCE
                    : PermissionChoice.DENY_ONCE;
            future.complete(choice);
        } else {
            log.warn("[{}] Unknown permission request_id: {}", sessionId, requestId);
        }
    }

    private void handleInterrupt() {
        log.info("[{}] Interrupt signal received", sessionId);
        agentLoop.cancel();
        // 取消所有挂起的权限请求
        pendingPermissions.values().forEach(f -> f.complete(PermissionChoice.DENY_ONCE));
        pendingPermissions.clear();
    }

    private void startKeepAlive() {
        keepAliveTask = keepAliveScheduler.scheduleAtFixedRate(() -> {
            try {
                if (wsSession.isOpen()) {
                    sendMessage(ServerMessage.keepAlive(sessionId));
                }
            } catch (Exception e) {
                log.debug("[{}] Keep-alive failed", sessionId, e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * 发送 WebSocket 消息（线程安全）。
     */
    private synchronized void sendMessage(String json) throws IOException {
        if (wsSession.isOpen()) {
            wsSession.sendMessage(new TextMessage(json));
        }
    }

    /**
     * 发送会话初始化消息。
     */
    public void sendInitMessage(String model) throws IOException {
        sendMessage(ServerMessage.systemEvent(sessionId, "init", model, "Session ready"));
    }

    /**
     * 关闭会话，清理资源。
     */
    public void close() {
        log.info("[{}] Closing server session", sessionId);
        if (keepAliveTask != null) {
            keepAliveTask.cancel(true);
        }
        keepAliveScheduler.shutdownNow();
        agentLoop.cancel();
        pendingPermissions.values().forEach(f -> f.complete(PermissionChoice.DENY_ONCE));
        pendingPermissions.clear();
        sessionExecutor.shutdownNow();
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isProcessing() {
        return processing.get();
    }

    public AgentLoop getAgentLoop() {
        return agentLoop;
    }
}
