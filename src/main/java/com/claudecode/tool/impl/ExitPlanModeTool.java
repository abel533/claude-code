package com.claudecode.tool.impl;

import com.claudecode.permission.PermissionSettings;
import com.claudecode.permission.PermissionTypes.PermissionMode;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 退出计划模式工具 —— 对应 claude-code/src/tools/ExitPlanModeTool。
 * <p>
 * 验证计划文件内容，恢复之前的权限模式。
 */
public class ExitPlanModeTool implements Tool {

    @Override
    public String name() {
        return "ExitPlanMode";
    }

    @Override
    public String description() {
        return """
                Exit plan mode after completing your implementation plan.
                
                When to use:
                - You have finished writing the plan to the plan file
                - The plan includes: context, approach, file paths, and verification steps
                - You are ready for the user to review and approve the plan
                
                This will:
                - Restore normal permission mode (all tools available again)
                - Present the plan to the user for review
                - The user can then ask you to implement the plan
                
                Do NOT call this tool until the plan is written to the plan file.
                """;
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "summary": {
                      "type": "string",
                      "description": "Brief summary of the plan (1-2 sentences)"
                    }
                  },
                  "required": ["summary"]
                }
                """;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // Check if in plan mode
        Boolean active = context.getOrDefault(EnterPlanModeTool.PLAN_MODE_KEY, false);
        if (!active) {
            return "⚠️ Not currently in plan mode. Nothing to exit.";
        }

        String planFilePath = context.get(EnterPlanModeTool.PLAN_FILE_PATH_KEY);
        String summary = input != null ? (String) input.get("summary") : null;

        // Validate plan file exists and has content
        String planContent = null;
        if (planFilePath != null) {
            Path planFile = Path.of(planFilePath);
            if (Files.exists(planFile)) {
                try {
                    planContent = Files.readString(planFile);
                } catch (Exception e) {
                    // Non-fatal
                }
            }
        }

        // Restore previous mode
        PermissionSettings permSettings = context.get("PERMISSION_SETTINGS");
        if (permSettings != null) {
            PermissionMode previousMode = context.getOrDefault(
                    EnterPlanModeTool.PRE_PLAN_MODE_KEY, PermissionMode.DEFAULT);
            permSettings.setCurrentMode(previousMode);
        }

        // Clear plan mode state
        context.set(EnterPlanModeTool.PLAN_MODE_KEY, false);

        StringBuilder result = new StringBuilder();
        result.append("✅ Exited plan mode. Normal permissions restored.\n\n");

        if (planContent != null && !planContent.isBlank()) {
            int lines = (int) planContent.lines().count();
            int chars = planContent.length();
            result.append("📋 Plan file: ").append(planFilePath).append("\n");
            result.append("📊 Plan size: ").append(lines).append(" lines, ")
                    .append(chars).append(" characters\n");
        } else {
            result.append("⚠️ Warning: Plan file is empty or missing.\n");
            result.append("   Path: ").append(planFilePath).append("\n");
        }

        if (summary != null && !summary.isBlank()) {
            result.append("\n📝 Summary: ").append(summary).append("\n");
        }

        result.append("\nThe user can now review the plan and ask you to implement it.");

        return result.toString();
    }

    @Override
    public boolean isReadOnly() {
        // This tool itself doesn't modify files, just state
        return true;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Exiting plan mode...";
    }
}
