package com.claudecode.tool.impl;

import com.claudecode.permission.PermissionSettings;
import com.claudecode.permission.PermissionTypes.PermissionMode;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 进入计划模式工具 —— 对应 claude-code/src/tools/EnterPlanModeTool。
 * <p>
 * 将权限切换到只读模式，AI只能分析代码不能修改，
 * 只有计划文件（PLAN.md）可以编辑。
 */
public class EnterPlanModeTool implements Tool {

    public static final String PLAN_MODE_KEY = "PLAN_MODE_ACTIVE";
    public static final String PLAN_FILE_PATH_KEY = "PLAN_FILE_PATH";
    public static final String PRE_PLAN_MODE_KEY = "PRE_PLAN_MODE";

    @Override
    public String name() {
        return "EnterPlanMode";
    }

    @Override
    public String description() {
        return """
                Enter plan mode to analyze the codebase and design an implementation plan WITHOUT making changes.
                
                When to use:
                - User asks you to "plan" or "think about" a change before implementing
                - User wants to understand approach before committing to it
                - Complex multi-file changes that need careful design
                
                In plan mode:
                - You can ONLY use read-only tools (Read, Grep, Glob, ListFiles, WebFetch, WebSearch)
                - You can ONLY write to the plan file (PLAN.md)
                - All other file modifications and shell commands are BLOCKED
                - Use AskUserQuestion to clarify requirements
                - Call ExitPlanMode when the plan is complete
                
                The plan file location is determined automatically based on the project path.
                """;
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "reason": {
                      "type": "string",
                      "description": "Brief reason for entering plan mode"
                    }
                  },
                  "required": []
                }
                """;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // Check if already in plan mode
        Boolean active = context.getOrDefault(PLAN_MODE_KEY, false);
        if (active) {
            String existingPlan = context.get(PLAN_FILE_PATH_KEY);
            return "Already in plan mode. Plan file: " + existingPlan;
        }

        // Determine plan file path
        Path workDir = context.getWorkDir();
        Path planDir = getPlanDirectory(workDir);
        Path planFile = planDir.resolve("PLAN.md");

        // Save pre-plan mode for restoration
        PermissionSettings permSettings = context.get("PERMISSION_SETTINGS");
        if (permSettings != null) {
            PermissionMode previousMode = permSettings.getCurrentMode();
            context.set(PRE_PLAN_MODE_KEY, previousMode);
            // Switch to PLAN mode
            permSettings.setCurrentMode(PermissionMode.PLAN);
        }

        // Store plan state
        context.set(PLAN_MODE_KEY, true);
        context.set(PLAN_FILE_PATH_KEY, planFile.toString());

        // Create plan directory if needed
        try {
            Files.createDirectories(planDir);
        } catch (Exception e) {
            // Non-fatal
        }

        String reason = input != null ? (String) input.get("reason") : null;
        boolean planExists = Files.exists(planFile);

        StringBuilder result = new StringBuilder();
        result.append("✅ Entered plan mode.\n\n");
        result.append("📋 Plan file: ").append(planFile).append("\n");
        if (planExists) {
            result.append("📄 Existing plan found — you can read and update it.\n");
        } else {
            result.append("📝 No existing plan — create one by writing to the plan file.\n");
        }
        result.append("\n");
        result.append("Restrictions active:\n");
        result.append("  • Only read-only tools allowed (Read, Grep, Glob, etc.)\n");
        result.append("  • Only the plan file can be edited\n");
        result.append("  • Shell commands are blocked\n");
        result.append("  • Call ExitPlanMode when your plan is ready\n");

        if (reason != null && !reason.isBlank()) {
            result.append("\nReason: ").append(reason);
        }

        return result.toString();
    }

    @Override
    public boolean isReadOnly() {
        // This tool itself doesn't modify files
        return true;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Entering plan mode...";
    }

    /**
     * Get plan directory for the given work directory.
     * Uses ~/.claude/projects/[sanitized-path]/ structure.
     */
    static Path getPlanDirectory(Path workDir) {
        String sanitized = workDir.toAbsolutePath().toString()
                .replace(":", "_")
                .replace("\\", "_")
                .replace("/", "_");
        return Path.of(System.getProperty("user.home"))
                .resolve(".claude")
                .resolve("projects")
                .resolve(sanitized);
    }
}
