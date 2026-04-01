package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 子 Agent 工具 —— 对应 claude-code/src/tools/agent/AgentTool.ts。
 * <p>
 * 创建一个独立的子 Agent 来处理复杂的子任务。子 Agent 拥有独立的消息历史，
 * 但共享工具集和上下文环境。适用于：
 * <ul>
 *   <li>需要独立上下文的子任务（如分析另一个文件）</li>
 *   <li>并行处理多个任务</li>
 *   <li>隔离风险操作</li>
 * </ul>
 * <p>
 * 注意：子 Agent 使用主 Agent 的 ChatModel 和工具集，
 * 通过 ToolContext 中的 "agentLoop.factory" 获取 AgentLoop 工厂方法。
 */
public class AgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    /** ToolContext 中存储 AgentLoop 工厂的键名 */
    public static final String AGENT_FACTORY_KEY = "__agent_factory__";

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        return """
            Launch a sub-agent to handle a complex task independently. \
            The sub-agent has its own conversation context but shares tools \
            and environment. Use this for tasks that require focused attention \
            or when you want to isolate a subtask. \
            The sub-agent will execute the given prompt and return its final response.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "The task description / prompt for the sub-agent"
                },
                "context": {
                  "type": "string",
                  "description": "Additional context or instructions (optional)"
                }
              },
              "required": ["prompt"]
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String prompt = (String) input.get("prompt");
        String additionalContext = (String) input.getOrDefault("context", "");

        if (prompt == null || prompt.isBlank()) {
            return "Error: 'prompt' is required";
        }

        // 从 ToolContext 获取 AgentLoop 工厂方法
        @SuppressWarnings("unchecked")
        java.util.function.Function<String, String> agentFactory =
                context.getOrDefault(AGENT_FACTORY_KEY, null);

        if (agentFactory == null) {
            log.warn("AgentTool: Agent factory not configured, cannot create sub-agent");
            return "Error: Sub-agent capability is not configured. "
                   + "The Agent tool requires an agent factory to be registered in the ToolContext.";
        }

        // 构建完整的子 Agent 提示
        String fullPrompt = buildSubAgentPrompt(prompt, additionalContext);

        log.info("Starting sub-agent, task: {}", truncate(prompt, 80));

        try {
            String result = agentFactory.apply(fullPrompt);
            log.info("Sub-agent completed, result length: {} chars", result.length());
            return result;
        } catch (Exception e) {
            log.debug("Sub-agent execution failed", e);
            return "Error: Sub-agent failed: " + e.getMessage();
        }
    }

    /**
     * 构建子 Agent 的完整提示词
     */
    private String buildSubAgentPrompt(String prompt, String additionalContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a sub-agent tasked with a specific job. ");
        sb.append("Complete the following task thoroughly and return your findings/results:\n\n");
        sb.append("## Task\n");
        sb.append(prompt);

        if (additionalContext != null && !additionalContext.isBlank()) {
            sb.append("\n\n## Additional Context\n");
            sb.append(additionalContext);
        }

        sb.append("\n\n## Instructions\n");
        sb.append("- Focus only on the given task\n");
        sb.append("- Use available tools as needed\n");
        sb.append("- Provide a clear, concise result\n");
        sb.append("- If the task cannot be completed, explain why\n");

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String prompt = (String) input.getOrDefault("prompt", "");
        if (prompt.length() > 40) {
            prompt = prompt.substring(0, 37) + "...";
        }
        return "🤖 Sub-agent: " + prompt;
    }
}
