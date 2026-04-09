package com.claudecode;

import org.springframework.boot.SpringApplication;

import java.util.HashMap;
import java.util.Map;

/**
 * 控制台模式启动入口 —— 跳过 Jink TUI，使用纯文本 Scanner 交互。
 * <p>
 * 适用于：
 * <ul>
 *   <li>IntelliJ IDEA / Eclipse 等 IDE 内置终端（dumb 模式）</li>
 *   <li>不支持全屏渲染的终端环境</li>
 *   <li>调试和开发时的快速启动</li>
 * </ul>
 * <p>
 * 在 IDE 中直接右键 Run 即可使用。
 * <p>
 * 运行前需设置环境变量：
 * <ul>
 *   <li>{@code AI_API_KEY} — API 密钥（必须）</li>
 *   <li>{@code CLAUDE_CODE_PROVIDER} — openai 或 anthropic（可选，默认 openai）</li>
 *   <li>{@code AI_BASE_URL} — API 地址（可选）</li>
 *   <li>{@code AI_MODEL} — 模型名称（可选）</li>
 * </ul>
 */
public class ConsoleMain {

    public static void main(String[] args) {
        // 强制使用 legacy REPL（Scanner 模式），跳过 Jink TUI
        System.setProperty("CLAUDE_CODE_TUI", "legacy");

        SpringApplication app = new SpringApplication(ClaudeCodeApplication.class);

        Map<String, Object> props = new HashMap<>();
        // 关闭 web 服务器（CLI 模式）
        props.put("spring.main.web-application-type", "none");
        // 减少启动日志噪音
        props.put("logging.level.root", "WARN");
        props.put("logging.level.com.claudecode", "INFO");
        app.setDefaultProperties(props);

        app.run(args);
    }
}
