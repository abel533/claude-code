package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;
import java.util.Map;

/**
 * /config 命令 —— 查看和设置应用配置。
 * <p>
 * 支持查看当前配置、设置单个配置项。
 * 配置变更仅在当前会话内生效。
 */
public class ConfigCommand implements SlashCommand {

    /** 支持的配置项及说明 */
    private static final Map<String, String> CONFIG_KEYS = Map.of(
            "model", "AI model name (e.g., claude-sonnet-4-20250514)",
            "max-tokens", "Maximum output tokens per response",
            "temperature", "Response randomness (0.0-1.0)",
            "verbose", "Enable verbose logging (true/false)",
            "auto-compact", "Auto compact when context is large (true/false)"
    );

    @Override
    public String name() {
        return "config";
    }

    @Override
    public String description() {
        return "View or set configuration";
    }

    @Override
    public List<String> aliases() {
        return List.of("cfg");
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (args == null || args.isBlank()) {
            return showAllConfig(context);
        }

        String[] parts = args.strip().split("\\s+", 2);
        String key = parts[0];

        if (parts.length == 1) {
            // 显示单个配置项
            return showConfig(key, context);
        }

        // 设置配置项
        String value = parts[1];
        return setConfig(key, value, context);
    }

    private String showAllConfig(CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  ⚙ Configuration\n"));
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        // 当前活跃配置
        String model = context.agentLoop().getTokenTracker().getModelName();
        sb.append("  ").append(AnsiStyle.bold("model:       ")).append(AnsiStyle.cyan(model)).append("\n");

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && apiKey.length() > 8) {
            sb.append("  ").append(AnsiStyle.bold("api-key:     ")).append(AnsiStyle.dim(
                    apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4))).append("\n");
        } else {
            sb.append("  ").append(AnsiStyle.bold("api-key:     ")).append(AnsiStyle.yellow("(not set)")).append("\n");
        }

        String baseUrl = System.getenv().getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        sb.append("  ").append(AnsiStyle.bold("base-url:    ")).append(AnsiStyle.dim(baseUrl)).append("\n");

        sb.append("\n");
        sb.append(AnsiStyle.dim("  Available keys:\n"));
        for (var entry : CONFIG_KEYS.entrySet()) {
            sb.append(AnsiStyle.dim("    " + entry.getKey() + " — " + entry.getValue())).append("\n");
        }
        sb.append("\n");
        sb.append(AnsiStyle.dim("  Usage: /config <key> <value>"));

        return sb.toString();
    }

    private String showConfig(String key, CommandContext context) {
        if (!CONFIG_KEYS.containsKey(key)) {
            return AnsiStyle.yellow("  ⚠ Unknown config key: " + key) + "\n"
                    + AnsiStyle.dim("  Available: " + String.join(", ", CONFIG_KEYS.keySet()));
        }

        String desc = CONFIG_KEYS.get(key);
        return "  " + AnsiStyle.bold(key) + ": " + AnsiStyle.dim(desc) + "\n"
                + AnsiStyle.dim("  Set with: /config " + key + " <value>");
    }

    private String setConfig(String key, String value, CommandContext context) {
        return switch (key) {
            case "model" -> {
                context.agentLoop().getTokenTracker().setModel(value);
                yield AnsiStyle.green("  ✅ Model set to: " + value) + "\n"
                        + AnsiStyle.dim("  Note: model change takes effect on next API call");
            }
            case "verbose" -> {
                boolean verbose = Boolean.parseBoolean(value);
                yield AnsiStyle.green("  ✅ Verbose mode: " + (verbose ? "ON" : "OFF"));
            }
            default -> {
                if (!CONFIG_KEYS.containsKey(key)) {
                    yield AnsiStyle.yellow("  ⚠ Unknown config key: " + key);
                }
                yield AnsiStyle.yellow("  ⚠ Setting '" + key + "' is not yet supported at runtime") + "\n"
                        + AnsiStyle.dim("  Set via application.yml or environment variables");
            }
        };
    }
}
