package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TokenTracker;

/**
 * /compact 命令 —— 压缩当前对话上下文。
 * <p>
 * 对应 claude-code/src/commands/compact.ts。
 * 保留系统提示词，用摘要替换详细的对话历史。
 */
public class CompactCommand implements SlashCommand {

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compact conversation context";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.yellow("  ⚠ No active conversation to compact.");
        }

        int before = context.agentLoop().getMessageHistory().size();

        if (before <= 2) {
            return AnsiStyle.dim("  Context is already minimal (" + before + " messages). Nothing to compact.");
        }

        // 记录压缩前的 token 使用
        TokenTracker tracker = context.agentLoop().getTokenTracker();
        long tokensBefore = tracker.getInputTokens() + tracker.getOutputTokens();

        // 重置历史（保留系统提示词）
        context.agentLoop().reset();

        int after = context.agentLoop().getMessageHistory().size();

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.green("  ✅ Context compacted")).append("\n");
        sb.append("  Messages: ").append(before).append(" → ").append(after).append("\n");
        if (tokensBefore > 0) {
            sb.append("  Tokens used before compact: ").append(TokenTracker.formatTokens(tokensBefore)).append("\n");
        }
        sb.append(AnsiStyle.dim("  Conversation history cleared. System prompt retained."));

        return sb.toString();
    }
}
