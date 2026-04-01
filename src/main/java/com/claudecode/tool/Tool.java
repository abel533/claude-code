package com.claudecode.tool;

import java.util.Map;

/**
 * 工具协议接口 —— 对应 claude-code/src/Tool.ts 中的 Tool 类型定义。
 * <p>
 * 每个工具是一个完整的协议实现，包含：
 * <ul>
 *   <li>工具定义（name、description、inputSchema）—— 告知 LLM 如何调用</li>
 *   <li>执行逻辑（execute）—— 实际运行</li>
 *   <li>权限检查（checkPermission）—— 安全前置检查</li>
 *   <li>特性门控（isEnabled）—— 条件注册</li>
 *   <li>活动描述（activityDescription）—— 人类可读的进度</li>
 * </ul>
 */
public interface Tool {

    /** 工具唯一名称标识 */
    String name();

    /** 给 LLM 看的工具描述 */
    String description();

    /**
     * 输入参数的 JSON Schema 定义。
     * <p>
     * 示例：
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "command": { "type": "string", "description": "Shell command to execute" }
     *   },
     *   "required": ["command"]
     * }
     * }</pre>
     */
    String inputSchema();

    /**
     * 执行工具。
     *
     * @param input   JSON 解析后的输入参数
     * @param context 执行上下文（工作目录、会话状态等）
     * @return 执行结果文本
     */
    String execute(Map<String, Object> input, ToolContext context);

    /**
     * 权限前置检查，在 execute 之前调用。
     * 默认放行。
     */
    default PermissionResult checkPermission(Map<String, Object> input, ToolContext context) {
        return PermissionResult.ALLOW;
    }

    /** 工具是否启用（特性门控），返回 false 则不注册 */
    default boolean isEnabled() {
        return true;
    }

    /** 是否为只读操作 */
    default boolean isReadOnly() {
        return false;
    }

    /** 人类可读的活动描述，用于 UI 显示执行进度 */
    default String activityDescription(Map<String, Object> input) {
        return "Running " + name() + "...";
    }
}
