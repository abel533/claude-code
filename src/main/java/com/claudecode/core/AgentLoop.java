package com.claudecode.core;

import com.claudecode.tool.ToolCallbackAdapter;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.function.Consumer;

/**
 * Agent 循环 —— 对应 claude-code/src/core/query.ts 的 agent loop。
 * <p>
 * 使用 ChatModel（非 ChatClient）的显式循环，完整控制每一轮：
 * <ol>
 *   <li>构建 Prompt（消息历史 + 系统提示 + 工具定义）</li>
 *   <li>调用 ChatModel.call()</li>
 *   <li>检查工具调用 → 执行工具 → 结果回传</li>
 *   <li>循环直到无工具调用或达到最大迭代</li>
 * </ol>
 */
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单轮最大迭代次数，防止无限循环 */
    private static final int MAX_ITERATIONS = 50;

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final String systemPrompt;

    /** 消息历史 —— 自行管理，不依赖 Spring AI ChatMemory */
    private final List<Message> messageHistory = new ArrayList<>();

    /** 工具调用事件回调：在每次工具调用前/后通知 UI */
    private Consumer<ToolEvent> onToolEvent;

    /** 助手文本回调：在每次助手回复时通知 UI */
    private Consumer<String> onAssistantMessage;

    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                     ToolContext toolContext, String systemPrompt) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.systemPrompt = systemPrompt;
        // 添加系统提示词到消息历史
        this.messageHistory.add(new SystemMessage(systemPrompt));
    }

    public void setOnToolEvent(Consumer<ToolEvent> onToolEvent) {
        this.onToolEvent = onToolEvent;
    }

    public void setOnAssistantMessage(Consumer<String> onAssistantMessage) {
        this.onAssistantMessage = onAssistantMessage;
    }

    /**
     * 执行一轮用户输入的完整 Agent 循环。
     *
     * @param userInput 用户输入文本
     * @return 最终助手回复文本
     */
    public String run(String userInput) {
        messageHistory.add(new UserMessage(userInput));

        List<ToolCallback> callbacks = toolRegistry.toCallbacks(toolContext);
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        int iteration = 0;
        String lastAssistantText = "";

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.debug("Agent 循环 第{}轮", iteration);

            Prompt prompt = new Prompt(List.copyOf(messageHistory), options);
            ChatResponse response = chatModel.call(prompt);

            AssistantMessage assistant = response.getResult().getOutput();
            messageHistory.add(assistant);

            // 提取并通知助手文本
            String text = assistant.getText();
            if (text != null && !text.isBlank()) {
                lastAssistantText = text;
                if (onAssistantMessage != null) {
                    onAssistantMessage.accept(text);
                }
            }

            // 检查是否有工具调用
            if (!assistant.hasToolCalls()) {
                log.debug("无工具调用，循环结束（共{}轮）", iteration);
                break;
            }

            // 逐个执行工具调用
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                String toolName = toolCall.name();
                String toolArgs = toolCall.arguments();
                String callId = toolCall.id();

                // 通知 UI 工具调用开始
                if (onToolEvent != null) {
                    onToolEvent.accept(new ToolEvent(toolName, ToolEvent.Phase.START, toolArgs, null));
                }

                // 查找并执行工具
                String result;
                ToolCallbackAdapter adapter = findCallbackByName(callbacks, toolName);
                if (adapter != null) {
                    result = adapter.call(toolArgs);
                } else {
                    result = "Error: Unknown tool '" + toolName + "'";
                    log.warn("未知工具: {}", toolName);
                }

                // 通知 UI 工具调用完成
                if (onToolEvent != null) {
                    onToolEvent.accept(new ToolEvent(toolName, ToolEvent.Phase.END, toolArgs, result));
                }

                toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, result));
            }

            // 将工具结果加入消息历史
            messageHistory.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }

        if (iteration >= MAX_ITERATIONS) {
            log.warn("Agent 循环已达最大迭代次数 {}，强制终止", MAX_ITERATIONS);
            lastAssistantText += "\n\n[WARNING: 达到最大循环次数限制]";
        }

        return lastAssistantText;
    }

    /** 从 ToolCallback 列表中查找匹配名称的适配器 */
    private ToolCallbackAdapter findCallbackByName(List<ToolCallback> callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb instanceof ToolCallbackAdapter adapter && adapter.getTool().name().equals(name)) {
                return adapter;
            }
        }
        return null;
    }

    /** 获取消息历史（用于上下文压缩等场景） */
    public List<Message> getMessageHistory() {
        return Collections.unmodifiableList(messageHistory);
    }

    /** 重置历史（保留系统提示词） */
    public void reset() {
        messageHistory.clear();
        messageHistory.add(new SystemMessage(systemPrompt));
    }

    /** 工具事件，用于 UI 展示 */
    public record ToolEvent(String toolName, Phase phase, String arguments, String result) {
        public enum Phase { START, END }
    }
}
