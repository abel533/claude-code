package com.claudecode.cli;

import com.claudecode.repl.ReplSession;
import com.claudecode.tui.JinkReplSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动编排器 —— 对应 claude-code/src/main.tsx 的初始化逻辑。
 * <p>
 * 优先使用 jink TUI 模式，失败时降级到传统 JLine REPL。
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
}
