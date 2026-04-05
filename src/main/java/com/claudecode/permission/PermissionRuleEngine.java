package com.claudecode.permission;

import com.claudecode.permission.PermissionTypes.*;
import com.claudecode.tool.impl.EnterPlanModeTool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限规则引擎 —— 根据规则、模式和工具属性做出权限决策。
 * <p>
 * 决策流程：
 * <ol>
 *   <li>检查全局模式（BYPASS → 全部允许，DONT_ASK → 拒绝需确认的）</li>
 *   <li>检查 alwaysDeny 规则 → 匹配则 DENY</li>
 *   <li>检查 alwaysAllow 规则 → 匹配则 ALLOW</li>
 *   <li>只读工具 → ALLOW</li>
 *   <li>ACCEPT_EDITS 模式下文件操作 → ALLOW</li>
 *   <li>检查危险命令 → 强制 ASK</li>
 *   <li>默认 → ASK</li>
 * </ol>
 */
public class PermissionRuleEngine {

    private static final Set<String> FILE_EDIT_TOOLS = Set.of("Write", "Edit", "NotebookEdit");
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "Read", "Glob", "Grep", "ListFiles", "WebFetch", "WebSearch",
            "TodoRead", "TaskGet", "TaskList", "AskUserQuestion",
            "EnterPlanMode", "ExitPlanMode", "ToolSearch"
    );

    /** Tools allowed to operate on plan file during PLAN mode */
    private static final Set<String> PLAN_FILE_TOOLS = Set.of("Write", "Edit");

    private final PermissionSettings settings;

    public PermissionRuleEngine(PermissionSettings settings) {
        this.settings = settings;
    }

    /**
     * 评估工具调用的权限
     *
     * @param toolName   工具名称
     * @param input      工具参数
     * @param isReadOnly 工具是否为只读
     * @return 权限决策
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> input, boolean isReadOnly) {
        return evaluate(toolName, input, isReadOnly, null);
    }

    /**
     * 评估工具调用的权限（带 ToolContext 用于 plan 模式检查）
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> input,
                                       boolean isReadOnly, Object toolContext) {
        PermissionMode mode = settings.getCurrentMode();

        // BYPASS 模式：全部允许
        if (mode == PermissionMode.BYPASS) {
            return PermissionDecision.allow("Bypass mode enabled");
        }

        // PLAN 模式：仅允许只读工具 + plan文件编辑
        if (mode == PermissionMode.PLAN) {
            if (isReadOnly || READ_ONLY_TOOLS.contains(toolName)) {
                return PermissionDecision.allow("Read-only tool allowed in plan mode");
            }
            // Allow Write/Edit to plan file only
            if (PLAN_FILE_TOOLS.contains(toolName) && isPlanFileOperation(input, toolContext)) {
                return PermissionDecision.allow("Plan file edit allowed in plan mode");
            }
            return PermissionDecision.deny("Plan mode: execution disabled (analysis only)");
        }

        // 获取命令内容（用于 Bash/PowerShell 的命令匹配）
        String command = extractCommand(toolName, input);

        // 检查所有持久化规则
        List<PermissionRule> rules = settings.getAllRules();

        // 1. 检查 alwaysDeny 规则
        for (var rule : rules) {
            if (rule.behavior() == PermissionBehavior.DENY && matchesRule(rule, toolName, command)) {
                return PermissionDecision.deny("Denied by rule: " + PermissionSettings.formatRule(rule));
            }
        }

        // 2. 检查 alwaysAllow 规则
        for (var rule : rules) {
            if (rule.behavior() == PermissionBehavior.ALLOW && matchesRule(rule, toolName, command)) {
                return PermissionDecision.allow("Allowed by rule: " + PermissionSettings.formatRule(rule));
            }
        }

        // 3. 只读工具直接放行
        if (isReadOnly || READ_ONLY_TOOLS.contains(toolName)) {
            return PermissionDecision.allow("Read-only tool");
        }

        // 4. ACCEPT_EDITS 模式：文件操作工具自动允许
        if (mode == PermissionMode.ACCEPT_EDITS && FILE_EDIT_TOOLS.contains(toolName)) {
            return PermissionDecision.allow("File edits auto-allowed in accept-edits mode");
        }

        // 5. DONT_ASK 模式：自动拒绝
        if (mode == PermissionMode.DONT_ASK) {
            return PermissionDecision.deny("Auto-denied in dont-ask mode");
        }

        // 6. 检查危险命令（强制 ASK，附带警告）
        if (command != null) {
            String danger = DangerousPatterns.detectDangerous(command);
            if (danger != null) {
                String prefix = extractCommandPrefix(command);
                return new PermissionDecision(
                        PermissionBehavior.ASK,
                        "⚠ DANGEROUS: " + danger,
                        toolName, prefix, List.of()
                );
            }
        }

        // 7. 默认：需要用户确认
        String prefix = extractCommandPrefix(command);
        return PermissionDecision.ask(toolName, prefix);
    }

    /**
     * 根据用户选择应用权限变更
     */
    public void applyChoice(PermissionChoice choice, String toolName, String command) {
        String prefix = extractCommandPrefix(command);
        switch (choice) {
            case ALWAYS_ALLOW -> {
                var rule = prefix != null
                        ? PermissionRule.forCommand(toolName, prefix, PermissionBehavior.ALLOW)
                        : PermissionRule.forTool(toolName, PermissionBehavior.ALLOW);
                // 检查是否为危险通配符
                String ruleStr = PermissionSettings.formatRule(rule);
                if (!DangerousPatterns.isDangerousWildcard(ruleStr)) {
                    settings.addUserRule(rule);
                }
            }
            case ALWAYS_DENY -> {
                var rule = prefix != null
                        ? PermissionRule.forCommand(toolName, prefix, PermissionBehavior.DENY)
                        : PermissionRule.forTool(toolName, PermissionBehavior.DENY);
                settings.addUserRule(rule);
            }
            case ALLOW_ONCE, DENY_ONCE -> {
                // 单次操作，不持久化
            }
        }
    }

    // ── 内部匹配方法 ──

    /** 检查规则是否匹配当前工具和命令 */
    boolean matchesRule(PermissionRule rule, String toolName, String command) {
        // 工具名不匹配直接跳过
        if (!rule.toolName().equalsIgnoreCase(toolName)) return false;

        String content = rule.ruleContent();
        // 通配符 * 匹配所有命令
        if ("*".equals(content)) return true;

        // 前缀匹配模式：npm:* 匹配以 "npm" 开头的命令
        if (content.endsWith(":*") && command != null) {
            String prefix = content.substring(0, content.length() - 2);
            return command.toLowerCase().startsWith(prefix.toLowerCase());
        }

        // 精确匹配
        return content.equalsIgnoreCase(command);
    }

    /** 从工具参数中提取命令文本 */
    private String extractCommand(String toolName, Map<String, Object> input) {
        if (input == null) return null;
        return switch (toolName) {
            case "Bash" -> (String) input.get("command");
            case "Write" -> (String) input.get("file_path");
            case "Edit" -> (String) input.get("file_path");
            default -> null;
        };
    }

    /** 提取命令前缀（第一个空格前的部分） */
    private String extractCommandPrefix(String command) {
        if (command == null || command.isBlank()) return null;
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    /**
     * 检查文件操作是否针对 plan 文件。
     * 在 PLAN 模式中，只有 PLAN.md 可以被编辑。
     */
    private boolean isPlanFileOperation(Map<String, Object> input, Object toolContext) {
        if (input == null) return false;
        String filePath = (String) input.get("file_path");
        if (filePath == null) return false;

        // Check if the file path ends with PLAN.md
        if (filePath.endsWith("PLAN.md") || filePath.endsWith("plan.md")) {
            return true;
        }

        // If we have toolContext, check against stored plan file path
        if (toolContext != null) {
            try {
                var ctx = (com.claudecode.tool.ToolContext) toolContext;
                String planPath = ctx.get(EnterPlanModeTool.PLAN_FILE_PATH_KEY);
                if (planPath != null) {
                    var targetPath = java.nio.file.Path.of(filePath).toAbsolutePath().normalize();
                    var planFilePath = java.nio.file.Path.of(planPath).toAbsolutePath().normalize();
                    return targetPath.equals(planFilePath);
                }
            } catch (Exception e) {
                // Ignore cast/access errors
            }
        }

        return false;
    }
}
