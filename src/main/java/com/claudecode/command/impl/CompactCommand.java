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
 * 支持压缩级别和统计显示。
 * <ul>
 *   <li>/compact —— 标准压缩</li>
 *   <li>/compact --stats —— 仅显示统计信息</li>
 *   <li>/compact --aggressive —— 激进压缩（保留更少上下文）</li>
 * </ul>
 */
public class CompactCommand implements SlashCommand {

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compact conversation context. Use --stats for info, --aggressive for deeper compaction.";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.yellow("  ⚠ No active conversation to compact.");
        }

        String argStr = (args == null) ? "" : args.strip();

        // --stats: only show statistics
        if (argStr.equals("--stats") || argStr.equals("-s")) {
            return showStats(context);
        }

        boolean aggressive = argStr.equals("--aggressive") || argStr.equals("-a");

        List<Message> history = context.agentLoop().getMessageHistory();
        int before = history.size();

        if (before <= 3) {
            return showStats(context) + "\n"
                    + AnsiStyle.dim("  Context is already small, no compaction needed.");
        }

        TokenTracker tracker = context.agentLoop().getTokenTracker();
        long tokensBefore = tracker.getInputTokens() + tracker.getOutputTokens();

        // Use FullCompact from AutoCompactManager
        FullCompact fullCompact;
        AutoCompactManager acm = context.agentLoop().getAutoCompactManager();
        if (acm != null) {
            fullCompact = acm.getFullCompact();
        } else {
            fullCompact = new FullCompact(context.agentLoop().getChatModel());
        }

        // In aggressive mode, compact more aggressively by keeping fewer messages
        List<Message> toCompact = new ArrayList<>(history);
        if (aggressive && toCompact.size() > 5) {
            // For aggressive mode, only keep the system message and last 2 exchanges
            List<Message> aggressive_list = new ArrayList<>();
            // Keep first message (usually system)
            aggressive_list.add(toCompact.getFirst());
            // Keep last 4 messages (2 exchanges)
            int start = Math.max(1, toCompact.size() - 4);
            aggressive_list.addAll(toCompact.subList(start, toCompact.size()));
            toCompact = aggressive_list;
        }

        List<Message> compacted = fullCompact.compact(toCompact);

        if (compacted != null) {
            context.agentLoop().replaceHistory(compacted);
            int after = compacted.size();

            if (acm != null) {
                acm.resetCircuitBreaker();
            }

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.green("  ✅ Context compacted")).append(
                    aggressive ? AnsiStyle.yellow(" (aggressive)") : "").append("\n");
            sb.append("  ").append("─".repeat(40)).append("\n");
            sb.append("  Messages: ").append(before).append(" → ").append(after);
            sb.append(" (").append(String.format("%.0f%%", (1.0 - (double) after / before) * 100)).append(" reduction)\n");
            if (tokensBefore > 0) {
                sb.append("  Tokens before: ").append(TokenTracker.formatTokens(tokensBefore)).append("\n");
            }
            sb.append(AnsiStyle.dim("  📝 AI summary generated and injected into context"));
            return sb.toString();
        }

        return AnsiStyle.yellow("  ⚠ Compaction failed — AI summary generation failed") + "\n"
                + AnsiStyle.dim("  The conversation history was not modified.");
    }

    private String showStats(CommandContext context) {
        List<Message> history = context.agentLoop().getMessageHistory();
        TokenTracker tracker = context.agentLoop().getTokenTracker();

        int userMsgs = 0, assistantMsgs = 0, systemMsgs = 0, toolMsgs = 0;
        for (Message m : history) {
            if (m instanceof UserMessage) userMsgs++;
            else if (m instanceof AssistantMessage) assistantMsgs++;
            else if (m instanceof SystemMessage) systemMsgs++;
            else toolMsgs++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n  ").append(AnsiStyle.bold("📊 Context Statistics")).append("\n");
        sb.append("  ").append("─".repeat(40)).append("\n");
        sb.append("  Total messages: ").append(history.size()).append("\n");
        sb.append("    User:      ").append(userMsgs).append("\n");
        sb.append("    Assistant:  ").append(assistantMsgs).append("\n");
        sb.append("    System:     ").append(systemMsgs).append("\n");
        if (toolMsgs > 0) {
            sb.append("    Tool:       ").append(toolMsgs).append("\n");
        }
        sb.append("  ").append("─".repeat(40)).append("\n");
        sb.append("  Input tokens:  ").append(TokenTracker.formatTokens(tracker.getInputTokens())).append("\n");
        sb.append("  Output tokens: ").append(TokenTracker.formatTokens(tracker.getOutputTokens())).append("\n");
        sb.append("  Total tokens:  ").append(TokenTracker.formatTokens(
                tracker.getInputTokens() + tracker.getOutputTokens())).append("\n");

        return sb.toString();
    }
}
