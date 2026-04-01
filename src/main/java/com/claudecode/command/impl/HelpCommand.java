package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;

/**
 * /help 命令 —— 对应 claude-code/src/commands/help.ts。
 */
public class HelpCommand implements SlashCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available commands";
    }

    @Override
    public List<String> aliases() {
        return List.of("?");
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  Available Commands:\n\n"));

        for (SlashCommand cmd : context.toolRegistry() != null
                ? List.<SlashCommand>of() // 这里后续会获取注册的命令
                : List.<SlashCommand>of()) {
            sb.append(String.format("  %s%-12s%s %s%n",
                    AnsiStyle.CYAN, "/" + cmd.name(), AnsiStyle.RESET, cmd.description()));
        }

        // 硬编码展示（后续重构为动态）
        sb.append(String.format("  %s%-12s%s %s%n", AnsiStyle.CYAN, "/help", AnsiStyle.RESET, "Show available commands"));
        sb.append(String.format("  %s%-12s%s %s%n", AnsiStyle.CYAN, "/clear", AnsiStyle.RESET, "Clear conversation history"));
        sb.append(String.format("  %s%-12s%s %s%n", AnsiStyle.CYAN, "/compact", AnsiStyle.RESET, "Compact conversation context"));
        sb.append(String.format("  %s%-12s%s %s%n", AnsiStyle.CYAN, "/cost", AnsiStyle.RESET, "Show token usage and cost"));
        sb.append(String.format("  %s%-12s%s %s%n", AnsiStyle.CYAN, "/model", AnsiStyle.RESET, "Show or switch AI model"));
        sb.append(String.format("  %s%-12s%s %s%n", AnsiStyle.CYAN, "/exit", AnsiStyle.RESET, "Exit the application"));

        sb.append("\n");
        sb.append(AnsiStyle.dim("  Tips: Press Tab for command completion, Ctrl+D to exit\n"));

        return sb.toString();
    }
}
