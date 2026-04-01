package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

/**
 * /compact 命令 —— 压缩当前对话上下文。
 * <p>
 * 对应 claude-code/src/commands/compact.ts。
 * 当前为简化实现，直接清空历史并提示用户。
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
        if (context.agentLoop() != null) {
            int before = context.agentLoop().getMessageHistory().size();
            context.agentLoop().reset();
            return AnsiStyle.green("  ✓ Context compacted: " + before + " messages → 1 (system prompt only)");
        }
        return AnsiStyle.yellow("  ⚠ No active conversation to compact.");
    }
}
