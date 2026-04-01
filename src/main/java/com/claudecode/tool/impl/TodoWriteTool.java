package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 待办任务工具 —— 对应 claude-code/src/tools/TodoWriteTool。
 * <p>
 * 管理 AI 工作过程中的待办事项列表，支持创建、更新、完成和删除任务。
 * 任务存储在内存中（ToolContext 的共享状态中），生命周期与会话一致。
 */
public class TodoWriteTool implements Tool {

    private static final String TODOS_KEY = "__todos__";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String name() {
        return "TodoWrite";
    }

    @Override
    public boolean isReadOnly() {
        return true; // 仅操作内存中的 todo 列表，无文件系统副作用
    }

    @Override
    public String description() {
        return """
            Manage a todo list for tracking tasks during the conversation. \
            Supports operations: add, update, complete, delete, list. \
            Use this to track multi-step tasks, record progress, and organize work.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "operation": {
                  "type": "string",
                  "enum": ["add", "update", "complete", "delete", "list"],
                  "description": "The operation to perform"
                },
                "id": {
                  "type": "string",
                  "description": "Task ID (required for update/complete/delete)"
                },
                "title": {
                  "type": "string",
                  "description": "Task title (required for add)"
                },
                "description": {
                  "type": "string",
                  "description": "Task description (optional)"
                },
                "status": {
                  "type": "string",
                  "enum": ["pending", "in_progress", "done", "blocked"],
                  "description": "Task status (for update)"
                },
                "priority": {
                  "type": "string",
                  "enum": ["high", "medium", "low"],
                  "description": "Task priority (default: medium)"
                }
              },
              "required": ["operation"]
            }""";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input, ToolContext context) {
        String operation = (String) input.get("operation");
        if (operation == null) {
            return "Error: 'operation' is required";
        }

        // 从 ToolContext 获取或初始化 todo 列表
        Map<String, TodoItem> todos = context.getOrDefault(TODOS_KEY, null);
        if (todos == null) {
            todos = new ConcurrentHashMap<>();
            context.set(TODOS_KEY, todos);
        }

        return switch (operation) {
            case "add" -> addTodo(input, todos);
            case "update" -> updateTodo(input, todos);
            case "complete" -> completeTodo(input, todos);
            case "delete" -> deleteTodo(input, todos);
            case "list" -> listTodos(todos);
            default -> "Error: Unknown operation '" + operation + "'. Use: add, update, complete, delete, list";
        };
    }

    private String addTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String title = (String) input.get("title");
        if (title == null || title.isBlank()) {
            return "Error: 'title' is required for add operation";
        }

        String id = generateId();
        String description = (String) input.getOrDefault("description", "");
        String priority = (String) input.getOrDefault("priority", "medium");

        TodoItem item = new TodoItem(id, title, description, "pending", priority, LocalDateTime.now());
        todos.put(id, item);

        return "✅ Task added:\n" + formatItem(item);
    }

    private String updateTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String id = (String) input.get("id");
        if (id == null) {
            return "Error: 'id' is required for update operation";
        }

        TodoItem item = todos.get(id);
        if (item == null) {
            return "Error: Task not found: " + id;
        }

        // 更新字段
        String title = (String) input.getOrDefault("title", item.title());
        String description = (String) input.getOrDefault("description", item.description());
        String status = (String) input.getOrDefault("status", item.status());
        String priority = (String) input.getOrDefault("priority", item.priority());

        TodoItem updated = new TodoItem(id, title, description, status, priority, item.createdAt());
        todos.put(id, updated);

        return "✏️ Task updated:\n" + formatItem(updated);
    }

    private String completeTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String id = (String) input.get("id");
        if (id == null) {
            return "Error: 'id' is required for complete operation";
        }

        TodoItem item = todos.get(id);
        if (item == null) {
            return "Error: Task not found: " + id;
        }

        TodoItem completed = new TodoItem(id, item.title(), item.description(), "done", item.priority(), item.createdAt());
        todos.put(id, completed);

        return "✅ Task completed: " + item.title();
    }

    private String deleteTodo(Map<String, Object> input, Map<String, TodoItem> todos) {
        String id = (String) input.get("id");
        if (id == null) {
            return "Error: 'id' is required for delete operation";
        }

        TodoItem removed = todos.remove(id);
        if (removed == null) {
            return "Error: Task not found: " + id;
        }

        return "🗑️ Task deleted: " + removed.title();
    }

    private String listTodos(Map<String, TodoItem> todos) {
        if (todos.isEmpty()) {
            return "📋 No tasks. Use 'add' operation to create one.";
        }

        // 按状态分组，优先级排序
        Map<String, List<TodoItem>> byStatus = todos.values().stream()
                .sorted(Comparator.comparingInt(this::priorityOrder))
                .collect(Collectors.groupingBy(TodoItem::status, LinkedHashMap::new, Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("📋 Task List (").append(todos.size()).append(" tasks)\n");
        sb.append("━".repeat(40)).append("\n");

        for (Map.Entry<String, List<TodoItem>> entry : byStatus.entrySet()) {
            String statusIcon = statusIcon(entry.getKey());
            sb.append("\n").append(statusIcon).append(" ").append(entry.getKey().toUpperCase()).append(":\n");
            for (TodoItem item : entry.getValue()) {
                sb.append(formatItem(item)).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    private String formatItem(TodoItem item) {
        String priorityIcon = switch (item.priority()) {
            case "high" -> "🔴";
            case "medium" -> "🟡";
            case "low" -> "🟢";
            default -> "⚪";
        };
        return String.format("  %s [%s] %s - %s (%s)",
                priorityIcon, item.id(), item.title(),
                item.description().isEmpty() ? "(no description)" : item.description(),
                item.createdAt().format(FMT));
    }

    private int priorityOrder(TodoItem item) {
        return switch (item.priority()) {
            case "high" -> 0;
            case "medium" -> 1;
            case "low" -> 2;
            default -> 3;
        };
    }

    private String statusIcon(String status) {
        return switch (status) {
            case "pending" -> "⏳";
            case "in_progress" -> "🔄";
            case "done" -> "✅";
            case "blocked" -> "🚫";
            default -> "❓";
        };
    }

    /** 生成短 ID（4 位十六进制） */
    private String generateId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 不可变任务数据记录 */
    record TodoItem(String id, String title, String description, String status,
                    String priority, LocalDateTime createdAt) {
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String op = (String) input.getOrDefault("operation", "managing");
        return "📋 Todo: " + op;
    }
}
