package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.ConversationPersistence;

/**
 * /history 命令 —— 列出保存的对话历史。
 * <p>
 * 显示最近的对话记录，包括时间、摘要和消息数量。
 */
public class HistoryCommand implements SlashCommand {

    @Override
    public String name() {
        return "/history";
    }

    @Override
    public String description() {
        return "列出保存的对话历史";
    }

    @Override
    public String execute(String args, CommandContext context) {
        ConversationPersistence persistence = new ConversationPersistence();
        var conversations = persistence.listConversations();

        if (conversations.isEmpty()) {
            return AnsiStyle.dim("  📂 暂无保存的对话历史");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("  📂 对话历史") + AnsiStyle.dim(" (")
                + conversations.size() + AnsiStyle.dim(" 条记录)\n"));
        sb.append(AnsiStyle.dim("  " + "─".repeat(50)) + "\n");

        int shown = Math.min(conversations.size(), 10);
        for (int i = 0; i < shown; i++) {
            var conv = conversations.get(i);
            sb.append("  " + AnsiStyle.cyan((i + 1) + ".") + " ");
            sb.append(AnsiStyle.bold(conv.summary()));
            sb.append(AnsiStyle.dim(" (" + conv.messageCount() + " messages)") + "\n");
            sb.append("     " + AnsiStyle.dim(conv.savedAt() + " | " + conv.workingDir()) + "\n");
        }

        if (conversations.size() > 10) {
            sb.append(AnsiStyle.dim("  ... 还有 " + (conversations.size() - 10) + " 条更早的记录\n"));
        }

        sb.append(AnsiStyle.dim("\n  对话存储位置: " + persistence.getConversationsDir()));

        return sb.toString();
    }
}
