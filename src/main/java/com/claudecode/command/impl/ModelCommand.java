package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;
import java.util.Map;

/**
 * /model 命令 —— 显示或切换当前 AI 模型。
 * <p>
 * 支持查看当前模型信息和切换到其他模型。
 */
public class ModelCommand implements SlashCommand {

    private static final Map<String, String> AVAILABLE_MODELS = Map.of(
            "sonnet", "claude-sonnet-4-20250514",
            "opus", "claude-opus-4-20250514",
            "haiku", "claude-haiku-4-20250514",
            "sonnet-3.5", "claude-3-5-sonnet-20241022"
    );

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
        if (args == null || args.isBlank()) {
            return showCurrentModel(context);
        }

        return switchModel(args.strip(), context);
    }

    private String showCurrentModel(CommandContext context) {
        String currentModel = context.agentLoop().getTokenTracker().getModelName();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🤖 Model Configuration\n"));
        sb.append("  ").append("─".repeat(40)).append("\n\n");
        sb.append("  Current: ").append(AnsiStyle.cyan(currentModel)).append("\n\n");

        sb.append(AnsiStyle.dim("  Available shortcuts:\n"));
        for (var entry : AVAILABLE_MODELS.entrySet()) {
            String marker = entry.getValue().equals(currentModel) ? " ◀" : "";
            sb.append(AnsiStyle.dim("    " + entry.getKey() + " → " + entry.getValue() + marker)).append("\n");
        }
        sb.append("\n");
        sb.append(AnsiStyle.dim("  Usage: /model <name>  (e.g., /model opus, /model claude-3-5-sonnet-20241022)"));

        return sb.toString();
    }

    private String switchModel(String modelArg, CommandContext context) {
        // 支持快捷名称
        String resolvedModel = AVAILABLE_MODELS.getOrDefault(modelArg.toLowerCase(), modelArg);

        String oldModel = context.agentLoop().getTokenTracker().getModelName();
        context.agentLoop().getTokenTracker().setModel(resolvedModel);

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.green("  ✅ Model switched")).append("\n");
        sb.append("  From: ").append(AnsiStyle.dim(oldModel)).append("\n");
        sb.append("  To:   ").append(AnsiStyle.cyan(resolvedModel)).append("\n");
        sb.append(AnsiStyle.dim("  Note: Changes take effect on next API call."));
        sb.append("\n").append(AnsiStyle.dim("  Note: Runtime model switch only updates pricing. Actual API model is set in application.yml."));

        return sb.toString();
    }
}
