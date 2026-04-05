package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.permission.PermissionSettings;
import com.claudecode.permission.PermissionTypes;

import java.util.List;

/**
 * /permissions 命令 —— 查看和管理权限设置。
 * <p>
 * 对应 claude-code/src/commands/permissions.ts。
 * 显示当前权限模式和规则列表。
 */
public class PermissionsCommand implements SlashCommand {

    private final PermissionSettings settings;

    public PermissionsCommand(PermissionSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "permissions";
    }

    @Override
    public String description() {
        return "View and manage permission settings";
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  ").append(AnsiStyle.bold("🔐 Permission Settings")).append("\n");
        sb.append("  ").append("─".repeat(50)).append("\n");

        // Current mode
        PermissionTypes.PermissionMode mode = settings.getCurrentMode();
        String modeStr = mode != null ? mode.name() : "DEFAULT";
        String modeColor = switch (modeStr) {
            case "AUTO_ALLOW" -> AnsiStyle.green(modeStr);
            case "DENY_ALL" -> AnsiStyle.red(modeStr);
            default -> AnsiStyle.yellow(modeStr);
        };
        sb.append("  Mode: ").append(modeColor).append("\n\n");

        // All rules
        List<String> rules = settings.listRules();
        sb.append("  ").append(AnsiStyle.bold("Rules")).append(" (").append(rules.size()).append("):\n");
        if (rules.isEmpty()) {
            sb.append("    ").append(AnsiStyle.dim("(no rules configured)")).append("\n");
        } else {
            for (String rule : rules) {
                String icon = rule.contains("ALLOW") ? AnsiStyle.green("✓") : AnsiStyle.red("✗");
                sb.append("    ").append(icon).append(" ").append(rule).append("\n");
            }
        }

        sb.append("\n  ").append(AnsiStyle.dim("Use /config to change permission settings")).append("\n");

        return sb.toString();
    }
}
