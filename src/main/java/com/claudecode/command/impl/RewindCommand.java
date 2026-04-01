package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * /rewind 命令 —— 回滚对话到之前的某个位置。
 * <p>
 * 按消息对（用户消息 + 助手消息）为单位进行回滚。
 * <ul>
 *   <li>{@code /rewind} —— 移除最后 1 个消息对</li>
 *   <li>{@code /rewind <n>} —— 移除最后 n 个消息对</li>
 * </ul>
 * <p>
 * 使用 {@code agentLoop.getMessageHistory()} 获取当前消息历史，
 * 然后通过 {@code agentLoop.replaceHistory()} 用截断后的列表替换。
 */
public class RewindCommand implements SlashCommand {

    /** 每个消息对包含的消息数（用户消息 + 助手消息） */
    private static final int MESSAGES_PER_PAIR = 2;

    @Override
    public String name() {
        return "rewind";
    }

    @Override
    public String description() {
        return "Roll back conversation to a previous point";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable.");
        }

        // 解析要回滚的消息对数量
        int pairsToRemove = parseRewindCount(args);
        if (pairsToRemove < 0) {
            return AnsiStyle.red("  ✗ Invalid rewind count. Please enter a positive integer.") + "\n"
                    + AnsiStyle.dim("  Usage: /rewind [n]  (n = number of message pairs to remove, default 1)");
        }

        List<Message> currentHistory = context.agentLoop().getMessageHistory();
        int currentSize = currentHistory.size();

        if (currentSize == 0) {
            return AnsiStyle.yellow("  ⚠ Conversation history is empty, cannot rewind.");
        }

        // 计算需要移除的消息数量
        int messagesToRemove = pairsToRemove * MESSAGES_PER_PAIR;

        // 如果要移除的消息数超过总消息数，则清除所有消息
        if (messagesToRemove >= currentSize) {
            context.agentLoop().replaceHistory(new ArrayList<>());
            return AnsiStyle.green("  ✓ Cleared all " + currentSize + " messages.") + "\n"
                    + AnsiStyle.dim("  (Requested " + pairsToRemove + " pairs, cleared all messages)");
        }

        // 截断消息历史
        int newSize = currentSize - messagesToRemove;
        List<Message> truncatedHistory = new ArrayList<>(currentHistory.subList(0, newSize));
        context.agentLoop().replaceHistory(truncatedHistory);

        // 构建结果输出
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.green("  ✓ Rewound " + pairsToRemove + " message pairs")).append("\n");
        sb.append(AnsiStyle.dim("    Removed: " + messagesToRemove + " messages")).append("\n");
        sb.append(AnsiStyle.dim("    Remaining: " + newSize + " messages")).append("\n");

        return sb.toString();
    }

    /**
     * 解析回滚数量参数。
     *
     * @param args 命令参数
     * @return 要回滚的消息对数量，默认为 1；解析失败返回 -1
     */
    private int parseRewindCount(String args) {
        String trimmed = args != null ? args.trim() : "";

        if (trimmed.isEmpty()) {
            return 1;  // 默认回滚 1 个消息对
        }

        try {
            int count = Integer.parseInt(trimmed);
            return count > 0 ? count : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
