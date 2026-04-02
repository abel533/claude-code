package com.claudecode.permission;

import java.util.List;

/**
 * 权限管理类型定义 —— 对应 claude-code 中的 permissions.ts。
 */
public final class PermissionTypes {

    private PermissionTypes() {}

    /** 权限行为 */
    public enum PermissionBehavior {
        ALLOW,  // 允许执行
        DENY,   // 拒绝执行
        ASK     // 需要用户确认
    }

    /** 权限模式 */
    public enum PermissionMode {
        /** 默认模式：非只读工具需要用户确认 */
        DEFAULT,
        /** 自动允许文件编辑，shell 命令仍需确认 */
        ACCEPT_EDITS,
        /** 跳过所有权限检查（不安全） */
        BYPASS,
        /** 自动拒绝而非询问用户（无头模式） */
        DONT_ASK,
        /** 计划模式：仅分析不执行（拒绝所有非只读工具） */
        PLAN
    }

    /**
     * 权限规则 —— 定义工具和命令模式的权限行为。
     * <p>
     * 示例：
     * <ul>
     *   <li>{@code PermissionRule("Bash", "npm:*", ALLOW)} — 允许所有 npm 命令</li>
     *   <li>{@code PermissionRule("Bash", "rm -rf:*", DENY)} — 拒绝 rm -rf</li>
     *   <li>{@code PermissionRule("Write", "*", ALLOW)} — 允许所有文件写入</li>
     * </ul>
     *
     * @param toolName    工具名称（如 Bash, Write, Edit）
     * @param ruleContent 规则内容，支持通配符 *（如 "npm:*", "git:*", "*"）
     * @param behavior    权限行为
     */
    public record PermissionRule(
            String toolName,
            String ruleContent,
            PermissionBehavior behavior
    ) {
        /** 匹配整个工具（无命令模式限制） */
        public static PermissionRule forTool(String toolName, PermissionBehavior behavior) {
            return new PermissionRule(toolName, "*", behavior);
        }

        /** 匹配工具的特定命令前缀 */
        public static PermissionRule forCommand(String toolName, String prefix, PermissionBehavior behavior) {
            return new PermissionRule(toolName, prefix + ":*", behavior);
        }
    }

    /** 权限决策结果 */
    public record PermissionDecision(
            PermissionBehavior behavior,
            String reason,
            String toolName,
            String commandPrefix,
            List<PermissionRule> suggestedRules
    ) {
        public static PermissionDecision allow(String reason) {
            return new PermissionDecision(PermissionBehavior.ALLOW, reason, null, null, List.of());
        }

        public static PermissionDecision deny(String reason) {
            return new PermissionDecision(PermissionBehavior.DENY, reason, null, null, List.of());
        }

        public static PermissionDecision ask(String toolName, String commandPrefix) {
            // 生成建议规则供用户选择 "always allow"
            var suggested = List.of(
                    PermissionRule.forCommand(toolName, commandPrefix, PermissionBehavior.ALLOW)
            );
            return new PermissionDecision(PermissionBehavior.ASK, "Requires user confirmation",
                    toolName, commandPrefix, suggested);
        }

        public boolean isAllowed() {
            return behavior == PermissionBehavior.ALLOW;
        }

        public boolean isDenied() {
            return behavior == PermissionBehavior.DENY;
        }

        public boolean needsAsk() {
            return behavior == PermissionBehavior.ASK;
        }
    }

    /** 权限确认选项（用户在 UI 中的选择） */
    public enum PermissionChoice {
        /** 允许本次执行 */
        ALLOW_ONCE,
        /** 始终允许此模式 */
        ALWAYS_ALLOW,
        /** 拒绝本次执行 */
        DENY_ONCE,
        /** 始终拒绝此模式 */
        ALWAYS_DENY
    }
}
