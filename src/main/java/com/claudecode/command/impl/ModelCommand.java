package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;

/**
 * /model 命令 —— 显示或切换当前 AI 模型。
 */
public class ModelCommand implements SlashCommand {

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Show or switch AI model";
    }

    @Override
    public List<String> aliases() {
        return List.of("m");
    }

    @Override
    public String execute(String args, CommandContext context) {
        // 当前只显示信息，后续可扩展为切换模型
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  Model Configuration:\n\n"));
        sb.append("  Provider:  ").append(AnsiStyle.cyan("Anthropic")).append("\n");
        sb.append("  Model:     ").append(AnsiStyle.cyan(
                System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-4-20250514"))).append("\n");

        if (args != null && !args.isBlank()) {
            sb.append("\n");
            sb.append(AnsiStyle.yellow("  ⚠ Model switching not yet implemented. Set AI_MODEL env variable."));
        }

        return sb.toString();
    }
}
