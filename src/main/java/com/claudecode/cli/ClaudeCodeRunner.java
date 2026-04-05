package com.claudecode.cli;

import com.claudecode.repl.ReplSession;
import com.claudecode.server.DirectConnectServer;
import com.claudecode.tui.JinkReplSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动编排器 —— 对应 claude-code/src/main.tsx 的初始化逻辑。
 * <p>
 * 支持三种模式：
 * <ul>
 *   <li>Server 模式 (--server) —— 不启动 TUI，WebSocket 服务器由 Spring 自动配置</li>
 *   <li>Jink TUI 模式（默认）—— 全屏终端 UI</li>
 *   <li>Legacy REPL 模式（降级或 CLAUDE_CODE_TUI=legacy）</li>
 * </ul>
 */
@Component
public class ClaudeCodeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeRunner.class);

    private final JinkReplSession jinkReplSession;
    private final ReplSession replSession;

    public ClaudeCodeRunner(JinkReplSession jinkReplSession, ReplSession replSession) {
        this.jinkReplSession = jinkReplSession;
        this.replSession = replSession;
    }

    @Override
    public void run(String... args) {
        // Server Mode: 不启动 TUI，WebSocket 服务器已由 ServerModeAutoConfiguration 启动
        if (DirectConnectServer.isServerMode(args)) {
            log.info("Server Mode active — TUI disabled, WebSocket server running");
            // 阻塞主线程，等待 Ctrl+C 或 SIGTERM
            waitForShutdown();
            return;
        }

        log.info("Claude Code (Java) starting...");

        // 检查是否强制使用旧模式
        String tuiMode = System.getenv("CLAUDE_CODE_TUI");
        if ("legacy".equalsIgnoreCase(tuiMode)) {
            log.info("Legacy TUI mode requested via CLAUDE_CODE_TUI=legacy");
            replSession.start();
            return;
        }

        // 优先使用 jink TUI
        try {
            jinkReplSession.start();
        } catch (Exception e) {
            log.warn("Jink TUI failed, falling back to legacy mode: {}", e.getMessage());
            replSession.start();
        }
    }

    /**
     * 在 Server Mode 下阻塞主线程直到收到 shutdown 信号。
     */
    private void waitForShutdown() {
        Thread shutdownHook = new Thread(() -> {
            log.info("Shutdown signal received");
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            // 阻塞直到中断
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            log.info("Server mode interrupted");
            Thread.currentThread().interrupt();
        }
    }
}
