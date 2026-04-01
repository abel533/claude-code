package com.claudecode.tool.impl;

import com.claudecode.core.TaskManager;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TaskCreate 工具 —— 创建一个新的后台任务（手动管理模式）。
 * <p>
 * 对应 claude-code 中的 TaskCreate 命令。创建后任务处于 PENDING 状态，
 * 需要通过 TaskUpdate 工具推动状态流转。
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>description</b>（必填）—— 任务描述</li>
 *   <li><b>metadata</b>（可选）—— JSON 格式的附加元数据字符串</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>JSON 格式，包含 task_id 与 status 字段。</p>
 */
public class TaskCreateTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskCreate";
    }

    @Override
    public String description() {
        return "Create a new background task for tracking work items";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "description": {
                      "type": "string",
                      "description": "任务描述，说明这个任务要做什么"
                    },
                    "metadata": {
                      "type": "string",
                      "description": "可选的 JSON 格式元数据字符串，例如 {\\"priority\\":\\"high\\"}"
                    }
                  },
                  "required": ["description"]
                }""";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // 获取 TaskManager 实例
        TaskManager manager = context.get(TASK_MANAGER_KEY);
        if (manager == null) {
            return errorJson("TaskManager 未初始化，请检查上下文配置");
        }

        // 解析必填参数: description
        String desc = (String) input.get("description");
        if (desc == null || desc.isBlank()) {
            return errorJson("参数 'description' 是必填项且不能为空");
        }

        // 解析可选参数: metadata
        Map<String, String> metadata = parseMetadata((String) input.get("metadata"));

        // 创建手动管理的任务
        String taskId;
        if (metadata.isEmpty()) {
            taskId = manager.createManualTask(desc);
        } else {
            taskId = manager.createManualTask(desc, metadata);
        }

        // 返回 JSON 结果
        return """
                {
                  "task_id": "%s",
                  "description": "%s",
                  "status": "PENDING",
                  "message": "任务已创建"
                }""".formatted(escapeJson(taskId), escapeJson(desc));
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📋 Creating task: " + input.getOrDefault("description", "unnamed");
    }

    /* ------------------------------------------------------------------ */
    /*  辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 metadata JSON 字符串为 Map。
     * 简易解析：支持 key:value 对的平面 JSON 对象。
     * 解析失败时返回空 Map 而不抛异常。
     */
    private Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            // 简易解析：去掉花括号、按逗号分割、按冒号取 key-value
            String trimmed = metadataJson.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }

            if (trimmed.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, String> result = new LinkedHashMap<>();
            for (String pair : trimmed.split(",")) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = stripQuotes(kv[0].trim());
                    String value = stripQuotes(kv[1].trim());
                    if (!key.isEmpty()) {
                        result.put(key, value);
                    }
                }
            }
            return Collections.unmodifiableMap(result);
        } catch (Exception e) {
            // 解析失败，返回空 Map
            return Collections.emptyMap();
        }
    }

    /**
     * 去除字符串两端的引号。
     */
    private String stripQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 转义 JSON 特殊字符。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 构建错误 JSON 响应。
     */
    private String errorJson(String message) {
        return """
                {
                  "error": true,
                  "message": "%s"
                }""".formatted(escapeJson(message));
    }
}
