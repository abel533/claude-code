package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * 用户提问工具 —— AI 在执行过程中向用户提问并获取回答。
 * <p>
 * 对应 claude-code 的 AskUserQuestionTool，允许 AI 在需要澄清信息时
 * 暂停执行并向用户提问。用户的回答会作为工具返回值传回 AI。
 * <p>
 * 依赖 ToolContext 中注册的 {@code USER_INPUT_CALLBACK} 回调函数，
 * 该回调由 ReplSession 在启动时设置，用于读取终端用户输入。
 */
public class AskUserQuestionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AskUserQuestionTool.class);

    /** ToolContext 中用于读取用户输入的回调 Key */
    public static final String USER_INPUT_CALLBACK = "ask_user_input_callback";

    @Override
    public String name() {
        return "AskUserQuestion";
    }

    @Override
    public String description() {
        return "Ask the user a question and wait for their response. Use this when you need clarification, " +
                "confirmation, or additional information from the user to proceed with a task. " +
                "The question should be clear, specific, and actionable.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "question": {
                      "type": "string",
                      "description": "The question to ask the user. Should be clear and specific."
                    },
                    "options": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "Optional list of choices for the user to pick from"
                    }
                  },
                  "required": ["question"]
                }
                """;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "Asking user a question...";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> input, ToolContext context) {
        String question = (String) input.get("question");
        if (question == null || question.isBlank()) {
            return "Error: question parameter is required";
        }

        // 获取用户输入回调
        Object callback = context.get(USER_INPUT_CALLBACK);
        if (callback == null) {
            log.warn("User input callback not registered (USER_INPUT_CALLBACK), returning default response");
            return "Error: User input not available in current environment";
        }

        if (!(callback instanceof Function<?, ?> inputFn)) {
            return "Error: Invalid user input callback type";
        }

        try {
            Function<String, String> askUser = (Function<String, String>) inputFn;

            // 构建提问文本
            StringBuilder prompt = new StringBuilder();
            prompt.append("\n  🤔 AI is asking you a question:\n");
            prompt.append("  ").append("─".repeat(50)).append("\n");
            prompt.append("  ").append(question).append("\n");

            // 如果有选项
            if (input.containsKey("options")) {
                var options = (java.util.List<String>) input.get("options");
                if (options != null && !options.isEmpty()) {
                    prompt.append("\n  Options:\n");
                    for (int i = 0; i < options.size(); i++) {
                        prompt.append("    ").append(i + 1).append(". ").append(options.get(i)).append("\n");
                    }
                }
            }

            prompt.append("  ").append("─".repeat(50)).append("\n");

            // 调用回调获取用户输入
            String userResponse = askUser.apply(prompt.toString());

            if (userResponse == null || userResponse.isBlank()) {
                return "(User provided no response)";
            }

            log.debug("User response: {}", userResponse);
            return "User response: " + userResponse;

        } catch (Exception e) {
            log.error("Failed to get user input", e);
            return "Error: Failed to get user input - " + e.getMessage();
        }
    }
}
