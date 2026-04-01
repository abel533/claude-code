package com.claudecode.tool.impl;

import com.claudecode.core.TaskManager;
import com.claudecode.core.TaskManager.TaskInfo;
import com.claudecode.core.TaskManager.TaskStatus;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.util.List;
import java.util.Map;

/**
 * TaskList 工具 —— 列出所有任务，支持按状态过滤。
 * <p>
 * 对应 claude-code 中的 TaskList 命令。返回任务列表的 JSON 数组，
 * 每个元素包含任务 ID、描述和当前状态。
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>status</b>（可选）—— 状态过滤器：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>JSON 格式的任务列表。</p>
 */
public class TaskListTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskList";
    }

    @Override
    public String description() {
        return "List all tasks, optionally filtered by status";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "status": {
                      "type": "string",
                      "description": "按状态过滤：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED",
                      "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
                    }
                  },
                  "required": []
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

        // 解析可选参数: status
        TaskStatus statusFilter = null;
        String statusStr = (String) input.get("status");
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                statusFilter = TaskStatus.valueOf(statusStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return errorJson("无效的状态值: '" + statusStr
                        + "'。可选值: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
            }
        }

        // 查询任务列表
        List<TaskInfo> taskList = manager.listTasks(statusFilter);

        // 构建 JSON 响应
        return buildListJson(taskList, statusFilter);
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String status = (String) input.get("status");
        if (status != null && !status.isBlank()) {
            return "📋 Listing tasks [" + status + "]";
        }
        return "📋 Listing all tasks";
    }

    /* ------------------------------------------------------------------ */
    /*  辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 将任务列表构建为 JSON 响应。
     *
     * @param taskList     任务列表
     * @param statusFilter 当前使用的过滤条件（用于信息展示），可为 null
     * @return JSON 字符串
     */
    private String buildListJson(List<TaskInfo> taskList, TaskStatus statusFilter) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total\": ").append(taskList.size()).append(",\n");

        if (statusFilter != null) {
            sb.append("  \"filter\": \"").append(statusFilter.name()).append("\",\n");
        }

        sb.append("  \"tasks\": [");

        if (taskList.isEmpty()) {
            sb.append("]\n}");
            return sb.toString();
        }

        sb.append('\n');
        for (int i = 0; i < taskList.size(); i++) {
            TaskInfo task = taskList.get(i);
            sb.append("    {\n");
            sb.append("      \"task_id\": \"").append(escapeJson(task.id())).append("\",\n");
            sb.append("      \"description\": \"").append(escapeJson(task.description())).append("\",\n");
            sb.append("      \"status\": \"").append(task.status().name()).append("\",\n");

            if (task.result() != null) {
                sb.append("      \"result\": \"").append(escapeJson(task.result())).append("\",\n");
            } else {
                sb.append("      \"result\": null,\n");
            }

            sb.append("      \"created_at\": \"").append(task.createdAt()).append("\",\n");
            sb.append("      \"updated_at\": \"").append(task.updatedAt()).append("\"");

            // 输出元数据
            if (task.metadata() != null && !task.metadata().isEmpty()) {
                sb.append(",\n      \"metadata\": {");
                boolean first = true;
                for (Map.Entry<String, String> entry : task.metadata().entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\n        \"").append(escapeJson(entry.getKey()))
                            .append("\": \"").append(escapeJson(entry.getValue())).append("\"");
                    first = false;
                }
                sb.append("\n      }");
            }

            sb.append("\n    }");
            if (i < taskList.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }

        sb.append("  ]\n}");
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
