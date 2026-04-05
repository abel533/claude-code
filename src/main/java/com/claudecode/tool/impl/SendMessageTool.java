package com.claudecode.tool.impl;

import com.claudecode.core.TaskManager;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * SendMessage 工具 —— 对应 claude-code/src/tools/SendMessageTool/。
 * <p>
 * 在 Coordinator 模式下用于向正在运行的 worker agent 发送消息，
 * 支持继续执行、提供反馈或请求停止。
 * <p>
 * 消息类型：
 * <ul>
 *   <li>普通文本 —— 继续指示或额外上下文</li>
 *   <li>shutdown_request —— 请求 worker 优雅退出</li>
 *   <li>broadcast —— 向所有 worker 广播（to="*"）</li>
 * </ul>
 */
public class SendMessageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SendMessageTool.class);

    public static final String TOOL_NAME = "SendMessage";

    /** ToolContext key for pending messages map: Map<String, List<String>> */
    public static final String PENDING_MESSAGES_KEY = "__pending_messages__";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return """
            Send a message to a running worker agent (teammate). Use this to:
            - Continue a worker with additional instructions after it completes a task
            - Provide follow-up context or corrections to a running worker
            - Request a worker to stop (shutdown_request)
            - Broadcast a message to all workers (to="*")

            The message will be queued and delivered to the worker on its next tool round.
            If the worker has already completed, it will be resumed with the new message.

            IMPORTANT:
            - Workers cannot see the coordinator's conversation history.
            - Include all necessary context in the message.
            - Use TaskStop to forcefully terminate a worker; SendMessage for graceful communication.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "to": {
                  "type": "string",
                  "description": "Recipient: task ID, agent name, or '*' for broadcast"
                },
                "message": {
                  "type": "string",
                  "description": "The message content to send"
                },
                "summary": {
                  "type": "string",
                  "description": "Brief 5-10 word summary of the message"
                }
              },
              "required": ["to", "message"]
            }""";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input, ToolContext context) {
        String to = (String) input.get("to");
        String message = (String) input.get("message");
        String summary = (String) input.getOrDefault("summary", "");

        if (to == null || to.isBlank()) {
            return "Error: 'to' is required — specify a task ID, agent name, or '*' for broadcast";
        }
        if (message == null || message.isBlank()) {
            return "Error: 'message' is required";
        }

        TaskManager taskManager = context.getOrDefault("TASK_MANAGER", null);
        if (taskManager == null) {
            return "Error: TaskManager not available";
        }

        // Broadcast to all running workers
        if ("*".equals(to)) {
            return handleBroadcast(message, summary, taskManager, context);
        }

        // Send to specific worker
        return handleDirectMessage(to, message, summary, taskManager, context);
    }

    private String handleDirectMessage(String to, String message, String summary,
                                        TaskManager taskManager, ToolContext context) {
        var taskOpt = taskManager.getTask(to);
        if (taskOpt.isEmpty()) {
            // Try to find by description/name match
            var allTasks = taskManager.listTasks();
            var matched = allTasks.stream()
                    .filter(t -> t.description().toLowerCase().contains(to.toLowerCase()))
                    .findFirst();
            if (matched.isEmpty()) {
                return "Error: No task found with ID or name matching '" + to + "'";
            }
            taskOpt = matched;
        }

        var task = taskOpt.get();

        // Queue the message for the worker
        queueMessage(task.id(), message, context);

        String statusInfo = switch (task.status()) {
            case RUNNING -> "Message queued for running worker '" + task.description() + "'";
            case COMPLETED -> "Worker '" + task.description() + "' has completed. "
                    + "Message stored but worker will need to be re-spawned to receive it.";
            case PENDING -> "Message queued for pending worker '" + task.description() + "'";
            case FAILED -> "Warning: Worker '" + task.description() + "' has failed. "
                    + "Message stored but worker may need to be re-spawned.";
            case CANCELLED -> "Warning: Worker '" + task.description() + "' was cancelled. "
                    + "Message stored but worker will need to be re-spawned.";
        };

        log.info("SendMessage to {}: {}", task.id(),
                summary.isBlank() ? truncate(message, 50) : summary);

        return statusInfo;
    }

    private String handleBroadcast(String message, String summary,
                                    TaskManager taskManager, ToolContext context) {
        var runningTasks = taskManager.listTasks(TaskManager.TaskStatus.RUNNING);
        if (runningTasks.isEmpty()) {
            return "No running workers to broadcast to.";
        }

        int count = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Broadcast sent to ").append(runningTasks.size()).append(" worker(s):\n");

        for (var task : runningTasks) {
            queueMessage(task.id(), message, context);
            sb.append("  • ").append(task.id()).append(" (").append(task.description()).append(")\n");
            count++;
        }

        log.info("Broadcast to {} workers: {}",
                count, summary.isBlank() ? truncate(message, 50) : summary);

        return sb.toString().stripTrailing();
    }

    @SuppressWarnings("unchecked")
    private void queueMessage(String taskId, String message, ToolContext context) {
        Map<String, java.util.List<String>> pendingMessages =
                context.getOrDefault(PENDING_MESSAGES_KEY, null);

        if (pendingMessages == null) {
            pendingMessages = new java.util.concurrent.ConcurrentHashMap<>();
            context.set(PENDING_MESSAGES_KEY, pendingMessages);
        }

        pendingMessages.computeIfAbsent(taskId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(message);
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String to = (String) input.getOrDefault("to", "?");
        String summary = (String) input.getOrDefault("summary", "");
        if (!summary.isBlank()) {
            return "📨 SendMessage to " + to + ": " + summary;
        }
        return "📨 SendMessage to " + to;
    }
}
