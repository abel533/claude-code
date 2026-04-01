package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandRegistry;
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

        // 从注入的 CommandRegistry 获取不到（因为 context 里没有），所以硬编码与注册保持一致
        sb.append(formatCmd("help", "Show available commands"));
        sb.append(formatCmd("clear", "Clear conversation history"));
        sb.append(formatCmd("compact", "Compact conversation context"));
        sb.append(formatCmd("cost", "Show token usage and cost"));
        sb.append(formatCmd("model", "Show or switch AI model"));
        sb.append(formatCmd("exit", "Exit the application (also: /quit, /q)"));

        sb.append("\n");
        sb.append(AnsiStyle.dim("  Shortcuts: Tab to autocomplete, ↑↓ to browse history, Ctrl+D to exit\n"));

        return sb.toString();
    }

    private String formatCmd(String name, String desc) {
        return String.format("  %s%-12s%s %s%n", AnsiStyle.CYAN, "/" + name, AnsiStyle.RESET, desc);
    }
}
