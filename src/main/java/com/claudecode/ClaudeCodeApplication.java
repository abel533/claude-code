package com.claudecode;

import com.claudecode.server.DirectConnectServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.*;

/**
 * Claude Code Java 版主入口。
 * <p>
 * 对应 claude-code/src/entrypoints/cli.tsx
 * <p>
 * 支持两种启动模式：
 * <ul>
 *   <li>CLI 模式（默认）—— 关闭 Web 服务器，启动 TUI 交互</li>
 *   <li>Server 模式（--server）—— 启动 WebSocket 服务器，无 TUI</li>
 * </ul>
 */
@SpringBootApplication
public class ClaudeCodeApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ClaudeCodeApplication.class);

        if (DirectConnectServer.isServerMode(args)) {
            // Server Mode: 启用 Web 服务器 + WebSocket
            int port = DirectConnectServer.parsePort(args);

            Map<String, Object> serverProps = new HashMap<>();
            serverProps.put("spring.main.web-application-type", "servlet");
            serverProps.put("server.port", port);
            serverProps.put("claude-code.server-mode", "true");
            app.setDefaultProperties(serverProps);

            // 将原始参数保存到系统属性，供 ServerModeAutoConfiguration 解析
            System.setProperty("claude-code.server-args", String.join(" ", args));

            System.out.println("Starting in Server Mode on port " + port + "...");
        }
        // CLI 模式使用 application.yml 中的 web-application-type: none

        app.run(args);
    }
}
