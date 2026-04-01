package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

/**
 * /version 命令 —— 显示版本和环境信息。
 * <p>
 * 展示 Claude Code Java 版本、运行时环境等信息。
 */
public class VersionCommand implements SlashCommand {

    /** 当前版本号 */
    public static final String VERSION = "1.0.0";
    public static final String BUILD_DATE = "2025-07";

    @Override
    public String name() {
        return "version";
    }

    @Override
    public String description() {
        return "Show version information";
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🏷️  Claude Code Java\n"));
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        sb.append("  ").append(AnsiStyle.bold("Version:      "))
                .append(AnsiStyle.cyan("v" + VERSION)).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Build:        "))
                .append(BUILD_DATE).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Java:         "))
                .append(System.getProperty("java.version")).append("\n");
        sb.append("  ").append(AnsiStyle.bold("JVM:          "))
                .append(System.getProperty("java.vm.name")).append(" ")
                .append(System.getProperty("java.vm.version")).append("\n");
        sb.append("  ").append(AnsiStyle.bold("OS:           "))
                .append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.arch")).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Spring Boot:  "))
                .append(getSpringBootVersion()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Spring AI:    "))
                .append("2.0.0-M4").append("\n");

        return sb.toString();
    }

    private String getSpringBootVersion() {
        try {
            // 从 Spring Boot 包中获取版本
            String version = org.springframework.boot.SpringBootVersion.getVersion();
            return version != null ? version : "4.1.0-M2";
        } catch (Exception e) {
            return "4.1.0-M2";
        }
    }
}
