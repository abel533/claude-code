package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.ConversationPersistence;
import com.claudecode.core.ConversationPersistence.ConversationSummary;
import org.springframework.ai.chat.messages.Message;

import java.nio.file.Path;
import java.util.List;

/**
 * /resume 命令 —— 恢复之前保存的对话。
 * <p>
 * 从 ~/.claude-code-java/conversations/ 加载对话历史，
 * 替换当前消息历史，恢复之前的上下文。
 * <ul>
 *   <li>/resume —— 恢复最近一次对话</li>
 *   <li>/resume list —— 列出可恢复的对话</li>
 *   <li>/resume [序号] —— 恢复指定序号的对话</li>
 * </ul>
 */
public class ResumeCommand implements SlashCommand {

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "Resume a saved conversation";
    }

    @Override
    public String execute(String args, CommandContext context) {
        ConversationPersistence persistence = new ConversationPersistence();
        List<ConversationSummary> conversations = persistence.listConversations();

        args = args == null ? "" : args.strip();

        if (conversations.isEmpty()) {
            return AnsiStyle.yellow("  ⚠ No saved conversations\n")
                    + AnsiStyle.dim("  Conversations are auto-saved on exit to ~/.claude-code-java/conversations/");
        }

        // /resume list —— 列出所有对话
        if (args.equals("list")) {
            return formatConversationList(conversations);
        }

        // 确定要恢复的对话索引
        int index = 0; // 默认最近一个
        if (!args.isEmpty()) {
            try {
                index = Integer.parseInt(args) - 1;
                if (index < 0 || index >= conversations.size()) {
                    return AnsiStyle.red("  ✗ Invalid index (range 1-" + conversations.size() + ")");
                }
            } catch (NumberFormatException e) {
                return AnsiStyle.yellow("  ⚠ Usage: /resume [index] or /resume list");
            }
        }

        // 加载并恢复对话
        ConversationSummary summary = conversations.get(index);
        Path file = persistence.getConversationsDir().resolve(summary.filename());
        List<Message> messages = persistence.loadFromFile(file);

        if (messages.isEmpty()) {
            return AnsiStyle.red("  ✗ Failed to load conversation: " + summary.filename());
        }

        // 替换当前消息历史
        context.agentLoop().replaceHistory(messages);

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.green("  ✓ Conversation restored\n"));
        sb.append("  ").append("─".repeat(50)).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Summary:  ")).append(summary.summary()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Time:     ")).append(summary.savedAt()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Messages: ")).append(summary.messageCount()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Dir:      ")).append(AnsiStyle.dim(summary.workingDir())).append("\n");

        return sb.toString();
    }

    private String formatConversationList(List<ConversationSummary> conversations) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📂 Saved Conversations\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        int maxShow = Math.min(conversations.size(), 20);
        for (int i = 0; i < maxShow; i++) {
            ConversationSummary conv = conversations.get(i);
            sb.append("  ").append(AnsiStyle.cyan(String.format("%2d", i + 1))).append(". ");
            sb.append(AnsiStyle.bold(conv.summary())).append("\n");
            sb.append("      ").append(AnsiStyle.dim(conv.savedAt()))
                    .append(AnsiStyle.dim(" | " + conv.messageCount() + " messages"))
                    .append("\n");
        }

        if (conversations.size() > maxShow) {
            sb.append(AnsiStyle.dim("\n  ... and " + (conversations.size() - maxShow) + " more conversations\n"));
        }

        sb.append(AnsiStyle.dim("\n  Use /resume [index] to restore a conversation\n"));

        return sb.toString();
    }
}
