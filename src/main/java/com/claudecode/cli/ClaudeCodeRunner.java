package com.claudecode.cli;

import com.claudecode.repl.ReplSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动编排器 —— 对应 claude-code/src/main.tsx 的初始化逻辑。
 * <p>
 * 在 Spring Boot 启动完成后执行，初始化并启动 REPL 会话。
 */
@Component
public class ClaudeCodeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeRunner.class);

    private final ReplSession replSession;

    public ClaudeCodeRunner(ReplSession replSession) {
        this.replSession = replSession;
    }

    @Override
    public void run(String... args) {
        log.info("Claude Code (Java) 启动中...");
        replSession.start();
    }
}
