package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TokenTracker;
import com.claudecode.core.compact.AutoCompactManager;
import com.claudecode.core.compact.FullCompact;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * /compact 命令 —— 用 AI 生成摘要来压缩上下文。
 * <p>
 * 对应 claude-code/src/commands/compact.ts。
 * 委托给 FullCompact 执行实际压缩逻辑。
 */
public class CompactCommand implements SlashCommand {

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compact conversation context with AI summary";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.yellow("  ⚠ No active conversation to compact.");
        }

        List<Message> history = context.agentLoop().getMessageHistory();
        int before = history.size();

        if (before <= 3) {
            return AnsiStyle.dim("  Context is already small (" + before + " messages), no compaction needed.");
        }

        TokenTracker tracker = context.agentLoop().getTokenTracker();
        long tokensBefore = tracker.getInputTokens() + tracker.getOutputTokens();

        // 优先使用 AutoCompactManager 中的 FullCompact
        FullCompact fullCompact;
        AutoCompactManager acm = context.agentLoop().getAutoCompactManager();
        if (acm != null) {
            fullCompact = acm.getFullCompact();
        } else {
            fullCompact = new FullCompact(context.agentLoop().getChatModel());
        }

        // 执行全量压缩
        List<Message> compacted = fullCompact.compact(new ArrayList<>(history));

        if (compacted != null) {
            context.agentLoop().replaceHistory(compacted);
            int after = compacted.size();

            // 重置熔断器（手动压缩成功说明 AI 摘要功能正常）
            if (acm != null) {
                acm.resetCircuitBreaker();
            }

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.green("  ✅ Context compacted")).append("\n");
            sb.append("  Messages: ").append(before).append(" → ").append(after).append("\n");
            if (tokensBefore > 0) {
                sb.append("  Tokens before compaction: ").append(TokenTracker.formatTokens(tokensBefore)).append("\n");
            }
            sb.append(AnsiStyle.dim("  📝 AI summary generated and injected into context"));
            return sb.toString();
        }

        return AnsiStyle.yellow("  ⚠ Compaction failed — AI summary generation failed") + "\n"
                + AnsiStyle.dim("  The conversation history was not modified.");
    }
}
