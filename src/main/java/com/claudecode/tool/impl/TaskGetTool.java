package com.claudecode.tool.impl;

import com.claudecode.core.TaskManager;
import com.claudecode.core.TaskManager.TaskInfo;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * TaskGet 工具 —— 查询指定任务的详细信息。
 * <p>
 * 对应 claude-code 中的 TaskGet 命令。根据 task_id 返回任务的完整快照，
 * 包括状态、结果、时间戳和元数据。
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>task_id</b>（必填）—— 要查询的任务 ID</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>JSON 格式的任务详情，或错误信息。</p>
 */
public class TaskGetTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskGet";
    }

    @Override
    public String description() {
        return "Get information about a specific task";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "task_id": {
                      "type": "string",
                      "description": "要查询的任务 ID"
                    }
                  },
                  "required": ["task_id"]
                }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // 获取 TaskManager 实例
        TaskManager manager = context.get(TASK_MANAGER_KEY);
        if (manager == null) {
            return errorJson("TaskManager 未初始化，请检查上下文配置");
        }

        // 解析必填参数: task_id
        String taskId = (String) input.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            return errorJson("参数 'task_id' 是必填项且不能为空");
        }

        // 查询任务
        Optional<TaskInfo> taskOpt = manager.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return errorJson("未找到 ID 为 '" + taskId + "' 的任务");
        }

        // 返回任务详情 JSON
        return taskInfoToJson(taskOpt.get());
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔍 Getting task: " + input.getOrDefault("task_id", "unknown");
    }

    /* ------------------------------------------------------------------ */
    /*  辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 将 TaskInfo 转换为 JSON 字符串。
     */
    private String taskInfoToJson(TaskInfo task) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"task_id\": \"").append(escapeJson(task.id())).append("\",\n");
        sb.append("  \"description\": \"").append(escapeJson(task.description())).append("\",\n");
        sb.append("  \"status\": \"").append(task.status().name()).append("\",\n");

        if (task.result() != null) {
            sb.append("  \"result\": \"").append(escapeJson(task.result())).append("\",\n");
        } else {
            sb.append("  \"result\": null,\n");
        }

        sb.append("  \"created_at\": \"").append(task.createdAt()).append("\",\n");
        sb.append("  \"updated_at\": \"").append(task.updatedAt()).append("\"");

        // 输出元数据
        if (task.metadata() != null && !task.metadata().isEmpty()) {
            sb.append(",\n  \"metadata\": {");
            boolean first = true;
            for (Map.Entry<String, String> entry : task.metadata().entrySet()) {
                if (!first) sb.append(",");
                sb.append("\n    \"").append(escapeJson(entry.getKey()))
                        .append("\": \"").append(escapeJson(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("\n  }");
        }

        sb.append("\n}");
        return sb.toString();
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
