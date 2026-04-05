package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TaskManager;
import com.claudecode.core.TaskManager.TaskInfo;
import com.claudecode.core.TaskManager.TaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * /tasks 命令 —— 列出所有后台任务状态。
 * <p>
 * 对应 claude-code 中的任务管理 UI。
 * 显示所有任务的状态、创建时间和结果摘要。
 */
public class TasksCommand implements SlashCommand {

    private final TaskManager taskManager;

    public TasksCommand(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public String name() {
        return "tasks";
    }

    @Override
    public String description() {
        return "List all background tasks and their status";
    }

    @Override
    public String execute(String args, CommandContext context) {
        List<TaskInfo> tasks;
        String filter = CommandUtils.parseArgs(args);

        // Optional status filter
        if (!filter.isEmpty()) {
            try {
                TaskStatus statusFilter = TaskStatus.valueOf(filter.toUpperCase());
                tasks = taskManager.listTasks(statusFilter);
            } catch (IllegalArgumentException e) {
                return AnsiStyle.yellow("  ⚠ Invalid status filter: " + filter) + "\n"
                        + AnsiStyle.dim("  Valid values: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED");
            }
        } else {
            tasks = taskManager.listTasks();
        }

        if (tasks.isEmpty()) {
            return AnsiStyle.dim("  No tasks" + (filter.isEmpty() ? "" : " with status " + filter));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n  ").append(AnsiStyle.bold("📋 Tasks")).append(" (").append(tasks.size()).append(")\n");
        sb.append(CommandUtils.separator(60)).append("\n");

        for (TaskInfo task : tasks) {
            String icon = switch (task.status()) {
                case PENDING -> "⏳";
                case RUNNING -> "🔄";
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                case CANCELLED -> "🚫";
            };

            String statusColor = switch (task.status()) {
                case COMPLETED -> AnsiStyle.green(task.status().name());
                case FAILED -> AnsiStyle.red(task.status().name());
                case RUNNING -> AnsiStyle.CYAN + task.status().name() + AnsiStyle.RESET;
                case CANCELLED -> AnsiStyle.yellow(task.status().name());
                default -> task.status().name();
            };

            sb.append("  ").append(icon).append(" ")
                    .append(AnsiStyle.bold(task.id())).append("  ")
                    .append(statusColor).append("  ")
                    .append(task.description()).append("\n");

            // Time info
            String age = CommandUtils.formatDuration(Duration.between(task.createdAt(), Instant.now()).toSeconds());
            sb.append("     ").append(AnsiStyle.dim("Created " + age + " ago"));

            // Result preview for completed/failed
            if (task.result() != null) {
                String preview = CommandUtils.truncate(task.result(), 60);
                sb.append("  ").append(AnsiStyle.dim("→ " + preview));
            }
            sb.append("\n");
        }

        // Summary
        sb.append(CommandUtils.separator(60)).append("\n");
        sb.append("  ").append(AnsiStyle.dim(taskManager.getSummary())).append("\n");

        return sb.toString();
    }
}
