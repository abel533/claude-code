package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TokenTracker;

/**
 * /cost 命令 —— 显示 Token 使用量和费用估算。
 * <p>
 * 对应 claude-code/src/commands/cost.ts。
 * 从 AgentLoop 的 TokenTracker 获取真实 Token 统计。
 */
public class CostCommand implements SlashCommand {

    @Override
    public String name() {
        return "cost";
    }

    @Override
    public String description() {
        return "Show token usage and cost";
    }

    @Override
    public String execute(String args, CommandContext context) {
        TokenTracker tracker = context.agentLoop().getTokenTracker();
        int msgCount = context.agentLoop().getMessageHistory().size();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  💰 Token Usage & Cost\n"));
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        sb.append("  ").append(AnsiStyle.bold("Model:        ")).append(AnsiStyle.cyan(tracker.getModelName())).append("\n");
        sb.append("  ").append(AnsiStyle.bold("API Calls:    ")).append(tracker.getApiCallCount()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Messages:     ")).append(msgCount).append("\n\n");

        sb.append("  ").append(AnsiStyle.bold("Input tokens: ")).append(formatTokenLine(tracker.getInputTokens())).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Output tokens:")).append(formatTokenLine(tracker.getOutputTokens())).append("\n");

        if (tracker.getCacheReadTokens() > 0) {
            sb.append("  ").append(AnsiStyle.bold("Cache read:   ")).append(formatTokenLine(tracker.getCacheReadTokens())).append("\n");
        }
        if (tracker.getCacheCreationTokens() > 0) {
            sb.append("  ").append(AnsiStyle.bold("Cache create: ")).append(formatTokenLine(tracker.getCacheCreationTokens())).append("\n");
        }

        sb.append("  ").append("─".repeat(30)).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Total:        ")).append(TokenTracker.formatTokens(tracker.getTotalTokens())).append(" tokens\n");
        sb.append("  ").append(AnsiStyle.bold("Est. Cost:    ")).append(AnsiStyle.green("$" + String.format("%.4f", tracker.estimateCost()))).append("\n");

        if (tracker.getApiCallCount() == 0) {
            sb.append("\n").append(AnsiStyle.dim("  No API calls yet. Start a conversation to see usage."));
        }

        return sb.toString();
    }

    private String formatTokenLine(long tokens) {
        return " " + TokenTracker.formatTokens(tokens) + AnsiStyle.dim(" (" + tokens + ")");
    }
}
