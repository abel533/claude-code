package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.HookManager;
import com.claudecode.core.HookManager.HookRegistration;
import com.claudecode.core.HookManager.HookType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /hooks 命令 —— 显示所有已注册的 Hook 信息。
 * <p>
 * 按 Hook 类型分组展示，包含每个 Hook 的名称和优先级。
 * 如果没有注册任何 Hook，则显示提示信息。
 */
public class HooksCommand implements SlashCommand {

    @Override
    public String name() {
        return "hooks";
    }

    @Override
    public String description() {
        return "Show all registered hooks";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null || context.agentLoop().getHookManager() == null) {
            return AnsiStyle.yellow("  ⚠ Hook manager unavailable.");
        }

        HookManager hookManager = context.agentLoop().getHookManager();
        List<HookRegistration> allHooks = hookManager.getHooks();

        // 无已注册的 Hook
        if (allHooks.isEmpty()) {
            return AnsiStyle.dim("  No hooks registered.");
        }

        // 按类型分组
        Map<HookType, List<HookRegistration>> grouped = allHooks.stream()
                .collect(Collectors.groupingBy(HookRegistration::type));

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  📎 Registered Hooks\n"));

        // 遍历所有 HookType，保持固定顺序
        for (HookType type : HookType.values()) {
            List<HookRegistration> hooks = grouped.get(type);
            sb.append("\n");
            sb.append("  ").append(AnsiStyle.cyan(formatTypeName(type))).append("\n");

            if (hooks == null || hooks.isEmpty()) {
                sb.append("    ").append(AnsiStyle.dim("(none)")).append("\n");
            } else {
                // 按优先级排序后展示
                hooks.stream()
                        .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                        .forEach(hook -> {
                            String priorityStr = AnsiStyle.dim("[priority=" + hook.priority() + "]");
                            sb.append("    • ")
                                    .append(AnsiStyle.bold(hook.name()))
                                    .append("  ")
                                    .append(priorityStr)
                                    .append("\n");
                        });
            }
        }

        // 统计总数
        sb.append("\n  ").append(AnsiStyle.dim("Total: " + allHooks.size() + " hook(s) registered.")).append("\n");
        return sb.toString();
    }

    /**
     * 将 HookType 枚举格式化为可读名称。
     *
     * @param type Hook 类型枚举
     * @return 格式化后的类型名称
     */
    private String formatTypeName(HookType type) {
        return switch (type) {
            case PRE_TOOL_USE -> "PRE_TOOL_USE (before tool execution)";
            case POST_TOOL_USE -> "POST_TOOL_USE (after tool execution)";
            case PRE_PROMPT -> "PRE_PROMPT (before sending prompt)";
            case POST_RESPONSE -> "POST_RESPONSE (after receiving response)";
        };
    }
}
