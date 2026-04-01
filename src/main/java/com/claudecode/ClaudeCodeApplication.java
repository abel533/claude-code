package com.claudecode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Claude Code Java 版主入口。
 * <p>
 * 对应 claude-code/src/entrypoints/cli.tsx
 * 以 Spring Boot 应用启动，但关闭 Web 服务器（纯 CLI 模式）。
 */
@SpringBootApplication
public class ClaudeCodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaudeCodeApplication.class, args);
    }
}
