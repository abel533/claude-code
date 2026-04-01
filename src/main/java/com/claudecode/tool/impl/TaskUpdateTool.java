package com.claudecode.tool.impl;

import com.claudecode.core.TaskManager;
import com.claudecode.core.TaskManager.TaskInfo;
import com.claudecode.core.TaskManager.TaskStatus;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * TaskUpdate 工具 —— 更新指定任务的状态和结果。
 * <p>
 * 对应 claude-code 中的 TaskUpdate 命令。用于推动手动管理任务的状态流转，
 * 例如从 PENDING → RUNNING → COMPLETED。
 * </p>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>task_id</b>（必填）—— 要更新的任务 ID</li>
 *   <li><b>status</b>（必填）—— 新状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED</li>
 *   <li><b>result</b>（可选）—— 任务执行结果或附加信息</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>JSON 格式的更新确认，包含更新后的任务信息。</p>
 *
 * <h3>状态约束</h3>
 * <p>已处于终态（COMPLETED / FAILED / CANCELLED）的任务不允许再次更新。</p>
 */
public class TaskUpdateTool implements Tool {

    /** ToolContext 中 TaskManager 的存储键 */
    private static final String TASK_MANAGER_KEY = "TASK_MANAGER";

    @Override
    public String name() {
        return "TaskUpdate";
    }

    @Override
    public String description() {
        return "Update a task's status and optional result";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "task_id": {
                      "type": "string",
                      "description": "Task ID to update"
                    },
                    "status": {
                      "type": "string",
                      "description": "New status: PENDING / RUNNING / COMPLETED / FAILED / CANCELLED",
                      "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"]
                    },
                    "result": {
                      "type": "string",
                      "description": "Task execution result or additional info (optional)"
                    }
                  },
                  "required": ["task_id", "status"]
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
            return errorJson("TaskManager not initialized, check context configuration");
        }

        // 解析必填参数: task_id
        String taskId = (String) input.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            return errorJson("Parameter 'task_id' is required and cannot be empty");
        }

        // 解析必填参数: status
        String statusStr = (String) input.get("status");
        if (statusStr == null || statusStr.isBlank()) {
            return errorJson("Parameter 'status' is required and cannot be empty");
        }

        TaskStatus newStatus;
        try {
            newStatus = TaskStatus.valueOf(statusStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return errorJson("Invalid status value: '" + statusStr
                    + "'. Valid values: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
        }

        // 解析可选参数: result
        String result = (String) input.get("result");

        // 在更新前先获取旧状态（用于返回信息）
        Optional<TaskInfo> beforeOpt = manager.getTask(taskId);
        if (beforeOpt.isEmpty()) {
            return errorJson("Task with ID '" + taskId + "' not found");
        }

        TaskInfo before = beforeOpt.get();
        String oldStatus = before.status().name();

        // 执行更新
        boolean success = manager.updateTask(taskId, newStatus, result);
        if (!success) {
            return errorJson("Update failed: task '" + taskId + "' current status is "
                    + oldStatus + ", may be in terminal state and cannot be updated");
        }

        // 获取更新后的任务信息
        Optional<TaskInfo> afterOpt = manager.getTask(taskId);
        if (afterOpt.isEmpty()) {
            // 理论上不会出现，防御性编程
            return errorJson("Failed to get task info after update");
        }

        TaskInfo after = afterOpt.get();

        // 返回更新确认 JSON
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"success\": true,\n");
        sb.append("  \"task_id\": \"").append(escapeJson(after.id())).append("\",\n");
        sb.append("  \"previous_status\": \"").append(oldStatus).append("\",\n");
        sb.append("  \"current_status\": \"").append(after.status().name()).append("\",\n");

        if (after.result() != null) {
            sb.append("  \"result\": \"").append(escapeJson(after.result())).append("\",\n");
        } else {
            sb.append("  \"result\": null,\n");
        }

        sb.append("  \"updated_at\": \"").append(after.updatedAt()).append("\",\n");
        sb.append("  \"message\": \"Task status updated from ").append(oldStatus)
                .append(" to ").append(after.status().name()).append("\"\n");
        sb.append("}");

        return sb.toString();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String taskId = (String) input.getOrDefault("task_id", "unknown");
        String status = (String) input.getOrDefault("status", "?");
        return "✏️ Updating task " + taskId + " → " + status;
    }

    /* ------------------------------------------------------------------ */
    /*  辅助方法                                                           */
    /* ------------------------------------------------------------------ */

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
