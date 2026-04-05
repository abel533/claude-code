package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TokenTracker;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;

/**
 * /status 命令 —— 显示会话状态仪表板。
 * <p>
 * 展示当前模型、Token 用量、工具数、消息数、内存和运行时间等信息。
 */
public class StatusCommand implements SlashCommand {

    private final Instant startTime = Instant.now();

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show session status dashboard";
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("📊", "Session Status"));

        // 模型信息
        TokenTracker tracker = context.agentLoop().getTokenTracker();
        sb.append("  ").append(AnsiStyle.bold("Model:    ")).append(AnsiStyle.cyan(tracker.getModelName())).append("\n");

        // Token 使用
        sb.append("  ").append(AnsiStyle.bold("Tokens:   "))
                .append("↑ ").append(TokenTracker.formatTokens(tracker.getInputTokens()))
                .append(" input, ↓ ").append(TokenTracker.formatTokens(tracker.getOutputTokens()))
                .append(" output")
                .append(AnsiStyle.dim(" ($" + String.format("%.4f", tracker.estimateCost()) + ")"))
                .append("\n");

        // API 调用次数
        sb.append("  ").append(AnsiStyle.bold("API Calls:")).append(" ").append(tracker.getApiCallCount()).append("\n");

        // 消息历史
        int msgCount = context.agentLoop().getMessageHistory().size();
        sb.append("  ").append(AnsiStyle.bold("Messages: ")).append(msgCount).append("\n");

        // 工具数
        sb.append("  ").append(AnsiStyle.bold("Tools:    ")).append(context.toolRegistry().size()).append(" registered\n");

        // 工作目录
        sb.append("  ").append(AnsiStyle.bold("Work Dir: ")).append(AnsiStyle.dim(System.getProperty("user.dir"))).append("\n");

        // JVM 内存
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMB = rt.maxMemory() / (1024 * 1024);
        sb.append("  ").append(AnsiStyle.bold("Memory:   ")).append(usedMB).append("MB / ").append(maxMB).append("MB\n");

        // 运行时间
        Duration uptime = Duration.between(startTime, Instant.now());
        sb.append("  ").append(AnsiStyle.bold("Uptime:   ")).append(CommandUtils.formatDuration(uptime.toSeconds())).append("\n");

        // Java 版本
        sb.append("  ").append(AnsiStyle.bold("JDK:      ")).append(System.getProperty("java.version")).append("\n");

        return sb.toString();
    }


}
