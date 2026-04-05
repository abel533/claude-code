package com.claudecode.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.socket.config.annotation.EnableWebSocket;

import java.util.UUID;

/**
 * Server Mode 生命周期管理 —— 对应 claude-code/src/server 的服务端核心。
 * <p>
 * 管理服务端状态、认证 Token 生成、会话限制等。
 * <p>
 * 启动流程：
 * <ol>
 *   <li>ClaudeCodeApplication 检测 --server 参数</li>
 *   <li>Spring Boot 启用 WebSocket 模式</li>
 *   <li>DirectConnectServer 初始化，生成 auth token</li>
 *   <li>WebSocket 端点开始接受连接</li>
 * </ol>
 *
 * 客户端连接方式：
 * <ul>
 *   <li>WebSocket: ws://localhost:{port}/ws?token={auth_token}</li>
 *   <li>HTTP Header: Authorization: Bearer {auth_token}</li>
 * </ul>
 */
public class DirectConnectServer {

    private static final Logger log = LoggerFactory.getLogger(DirectConnectServer.class);

    /** 默认端口 */
    public static final int DEFAULT_PORT = 12321;

    /** 默认最大会话数 */
    public static final int DEFAULT_MAX_SESSIONS = 5;

    private final int port;
    private final String authToken;
    private final int maxSessions;
    private final DirectConnectWebSocketHandler handler;

    private volatile boolean running = false;

    public DirectConnectServer(int port, String authToken, int maxSessions,
                                DirectConnectWebSocketHandler handler) {
        this.port = port;
        this.authToken = authToken;
        this.maxSessions = maxSessions;
        this.handler = handler;
    }

    /**
     * 生成随机认证 Token。
     */
    public static String generateAuthToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 标记服务器已启动。打印连接信息。
     */
    public void onServerStarted() {
        running = true;
        printConnectionInfo();
    }

    /**
     * 打印连接信息到控制台。
     */
    public void printConnectionInfo() {
        String separator = "═".repeat(60);
        System.out.println();
        System.out.println("╔" + separator + "╗");
        System.out.println("║  Claude Code Java — Server Mode                          ║");
        System.out.println("╠" + separator + "╣");
        System.out.printf("║  WebSocket: ws://localhost:%d/ws%-24s║%n", port, "");
        System.out.printf("║  Port:      %-48s║%n", port);
        if (authToken != null && !authToken.isBlank()) {
            System.out.printf("║  Token:     %-48s║%n", authToken);
        } else {
            System.out.printf("║  Auth:      %-48s║%n", "disabled (no token)");
        }
        System.out.printf("║  Max Sess:  %-48s║%n", maxSessions);
        System.out.println("╠" + separator + "╣");
        System.out.println("║  Connect with:                                            ║");
        if (authToken != null && !authToken.isBlank()) {
            System.out.printf("║    ws://localhost:%d/ws?token=%s  ║%n", port, authToken.substring(0, Math.min(12, authToken.length())) + "...");
        } else {
            System.out.printf("║    ws://localhost:%d/ws%-38s║%n", port, "");
        }
        System.out.println("╚" + separator + "╝");
        System.out.println();
    }

    /**
     * 停止服务器，关闭所有会话。
     */
    public void shutdown() {
        if (!running) return;
        running = false;
        log.info("Shutting down server...");
        handler.closeAllSessions();
        log.info("Server stopped. All sessions closed.");
    }

    // ==================== 静态工具方法 ====================

    /**
     * 检查命令行参数是否包含 --server 标志。
     */
    public static boolean isServerMode(String[] args) {
        if (args == null) return false;
        for (String arg : args) {
            if ("--server".equals(arg) || arg.startsWith("--server=")) {
                return true;
            }
        }
        // 也支持环境变量
        String envMode = System.getenv("CLAUDE_CODE_SERVER_MODE");
        return "true".equalsIgnoreCase(envMode) || "1".equals(envMode);
    }

    /**
     * 从命令行参数解析端口号。
     */
    public static int parsePort(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--server-port=")) {
                    try {
                        return Integer.parseInt(arg.substring("--server-port=".length()));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid port number: {}", arg);
                    }
                }
                if (arg.startsWith("--server=")) {
                    try {
                        return Integer.parseInt(arg.substring("--server=".length()));
                    } catch (NumberFormatException e) {
                        // --server=true 等非数字参数忽略
                    }
                }
            }
        }
        // 环境变量
        String envPort = System.getenv("CLAUDE_CODE_SERVER_PORT");
        if (envPort != null) {
            try {
                return Integer.parseInt(envPort);
            } catch (NumberFormatException e) {
                log.warn("Invalid CLAUDE_CODE_SERVER_PORT: {}", envPort);
            }
        }
        return DEFAULT_PORT;
    }

    /**
     * 从命令行参数或环境变量获取认证 Token。
     * 如果未指定，生成随机 Token。
     */
    public static String parseAuthToken(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--server-token=")) {
                    return arg.substring("--server-token=".length());
                }
            }
        }
        String envToken = System.getenv("CLAUDE_CODE_SERVER_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            return envToken;
        }
        // 未指定则生成随机 token
        return generateAuthToken();
    }

    /**
     * 从命令行参数解析最大会话数。
     */
    public static int parseMaxSessions(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--max-sessions=")) {
                    try {
                        return Integer.parseInt(arg.substring("--max-sessions=".length()));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid max sessions: {}", arg);
                    }
                }
            }
        }
        String envMax = System.getenv("CLAUDE_CODE_MAX_SESSIONS");
        if (envMax != null) {
            try {
                return Integer.parseInt(envMax);
            } catch (NumberFormatException e) {
                log.warn("Invalid CLAUDE_CODE_MAX_SESSIONS: {}", envMax);
            }
        }
        return DEFAULT_MAX_SESSIONS;
    }

    // ==================== Getters ====================

    public int getPort() {
        return port;
    }

    public String getAuthToken() {
        return authToken;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveSessionCount() {
        return handler.getActiveSessionCount();
    }
}
