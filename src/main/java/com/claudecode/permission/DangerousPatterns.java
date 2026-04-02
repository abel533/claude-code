package com.claudecode.permission;

import java.util.List;
import java.util.Set;

/**
 * 危险命令模式检测 —— 识别可能有害的 shell 命令。
 * <p>
 * 即使在 BYPASS 模式下也会对这些命令发出警告。
 */
public final class DangerousPatterns {

    private DangerousPatterns() {}

    /** 危险 shell 命令前缀（不区分大小写匹配） */
    private static final List<String> DANGEROUS_BASH_PREFIXES = List.of(
            "rm -rf /",
            "rm -rf ~",
            "rm -rf .",
            "rm -r /",
            "rmdir /s",
            "del /f /s /q",
            "format ",
            "mkfs.",
            "dd if=",
            "> /dev/sda",
            "chmod -R 777 /",
            "chown -R",
            ":(){:|:&};:"       // fork bomb
    );

    /** 危险代码执行模式 */
    private static final List<String> CODE_EXECUTION_PATTERNS = List.of(
            "eval ",
            "exec ",
            "python -c",
            "python3 -c",
            "node -e",
            "ruby -e",
            "perl -e",
            "| sh",
            "| bash",
            "| zsh",
            "| powershell",
            "| pwsh",
            "curl | sh",
            "wget | sh",
            "Invoke-Expression",
            "iex ",
            "Start-Process",
            "Add-Type"
    );

    /** 在规则匹配中应自动拒绝的工具级通配符 */
    private static final Set<String> DANGEROUS_TOOL_WILDCARDS = Set.of(
            "Bash",         // 不应允许所有 bash 命令
            "Bash(*)",
            "PowerShell",
            "PowerShell(*)"
    );

    /**
     * 检测命令是否包含危险模式
     *
     * @param command shell 命令文本
     * @return 如果危险返回原因描述，否则返回 null
     */
    public static String detectDangerous(String command) {
        if (command == null || command.isBlank()) return null;
        String lower = command.toLowerCase().trim();

        for (String prefix : DANGEROUS_BASH_PREFIXES) {
            if (lower.startsWith(prefix.toLowerCase()) || lower.contains(prefix.toLowerCase())) {
                return "Dangerous command detected: " + prefix.trim();
            }
        }

        for (String pattern : CODE_EXECUTION_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return "Code execution pattern detected: " + pattern.trim();
            }
        }

        return null;
    }

    /**
     * 检测是否为危险的工具级通配符规则
     * <p>
     * 用于防止用户添加过于宽泛的 "always allow" 规则。
     */
    public static boolean isDangerousWildcard(String ruleStr) {
        return DANGEROUS_TOOL_WILDCARDS.contains(ruleStr);
    }

    /**
     * 获取危险原因的简短描述
     */
    public static String getDangerLevel(String command) {
        String reason = detectDangerous(command);
        if (reason == null) return "LOW";
        if (reason.contains("Dangerous command")) return "HIGH";
        return "MEDIUM";
    }
}
