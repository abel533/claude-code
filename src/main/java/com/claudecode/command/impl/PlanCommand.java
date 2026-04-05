package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.permission.PermissionSettings;
import com.claudecode.permission.PermissionTypes.PermissionMode;

/**
 * /plan 命令 —— 对应 claude-code/src/commands/plan/plan.tsx。
 * <p>
 * 切换计划模式开关。在计划模式下，AI只能分析不能修改。
 */
public class PlanCommand implements SlashCommand {

    private final PermissionSettings permissionSettings;

    public PlanCommand(PermissionSettings permissionSettings) {
        this.permissionSettings = permissionSettings;
    }

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public String description() {
        return "Toggle plan mode (analysis only, no file modifications)";
    }

    @Override
    public String execute(String args, CommandContext context) {
        PermissionMode currentMode = permissionSettings.getCurrentMode();

        if (currentMode == PermissionMode.PLAN) {
            // Exit plan mode
            permissionSettings.setCurrentMode(PermissionMode.DEFAULT);
            return "📋 Exited plan mode. Normal permissions restored.\n" +
                    "All tools are now available.";
        } else {
            // Enter plan mode
            permissionSettings.setCurrentMode(PermissionMode.PLAN);
            return "📋 Entered plan mode.\n" +
                    "Only read-only tools are available. Use /plan again to exit.\n" +
                    "Or ask the AI to call EnterPlanMode for the full workflow.";
        }
    }
}
