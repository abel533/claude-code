package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

/**
 * /cost 命令 —— 显示 Token 使用量和费用估算。
 * <p>
 * 对应 claude-code/src/commands/cost.ts。
 * 当前为占位实现，后续接入实际 Token 统计。
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
        int msgCount = 0;
        if (context.agentLoop() != null) {
            msgCount = context.agentLoop().getMessageHistory().size();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  Token Usage:\n\n"));
        sb.append("  Messages:     ").append(AnsiStyle.cyan(String.valueOf(msgCount))).append("\n");
        sb.append("  Input tokens: ").append(AnsiStyle.dim("(tracking not yet implemented)")).append("\n");
        sb.append("  Output tokens:").append(AnsiStyle.dim("(tracking not yet implemented)")).append("\n");
        sb.append("  Est. cost:    ").append(AnsiStyle.dim("(tracking not yet implemented)")).append("\n");
        sb.append("\n");
        sb.append(AnsiStyle.dim("  Token tracking will be added in a future update."));

        return sb.toString();
    }
}
