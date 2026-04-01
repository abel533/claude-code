package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

/**
 * /clear 命令 —— 清除对话历史。
 */
public class ClearCommand implements SlashCommand {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear conversation history";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() != null) {
            context.agentLoop().reset();
        }
        return AnsiStyle.green("  ✓ Conversation history cleared.");
    }
}
