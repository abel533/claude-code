package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;

import java.util.List;

/**
 * /exit 命令 —— 退出应用。
 */
public class ExitCommand implements SlashCommand {

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "Exit the application";
    }

    @Override
    public List<String> aliases() {
        return List.of("quit", "q");
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.exitCallback() != null) {
            context.exitCallback().run();
        }
        return "";
    }
}
