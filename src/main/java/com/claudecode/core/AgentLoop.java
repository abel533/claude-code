package com.claudecode.core;

import com.claudecode.core.compact.AutoCompactManager;
import com.claudecode.permission.DenialTracker;
import com.claudecode.permission.PermissionRuleEngine;
import com.claudecode.permission.PermissionTypes.PermissionChoice;
import com.claudecode.permission.PermissionTypes.PermissionDecision;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
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
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Agent 循环 —— 对应 claude-code/src/core/query.ts 的 agent loop。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>{@link #run(String)} —— 阻塞模式，等待完整响应后返回</li>
 *   <li>{@link #runStreaming(String, Consumer)} —— 流式模式，逐 token 实时输出</li>
 * </ul>
 * 使用 ChatModel（非 ChatClient）的显式循环，完整控制每一轮：
 * <ol>
 *   <li>构建 Prompt（消息历史 + 系统提示 + 工具定义）</li>
 *   <li>调用 ChatModel.call() 或 ChatModel.stream()</li>
 *   <li>检查工具调用 → 权限确认 → 执行工具 → 结果回传</li>
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
    private final TokenTracker tokenTracker;
    private final HookManager hookManager;

    /** 权限规则引擎（可选，为 null 时使用传统回调方式） */
    private PermissionRuleEngine permissionEngine;

    /** 自动压缩管理器（可选） */
    private AutoCompactManager autoCompactManager;

    /** 会话记忆服务（可选） */
    private SessionMemoryService sessionMemoryService;

    /** 拒绝追踪器 */
    private final DenialTracker denialTracker = new DenialTracker();

    /** 工具执行器（拆分出的权限+Hook+执行逻辑） */
    private final AgentToolExecutor toolExecutor;

    /** 中断标志 —— 用于取消当前运行中的 Agent 循环 */
    private volatile boolean cancelled = false;

    /** 消息历史 —— 自行管理，不依赖 Spring AI ChatMemory */
    private final List<Message> messageHistory = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 工具调用事件回调：在每次工具调用前/后通知 UI */
    private Consumer<ToolEvent> onToolEvent;

    /** 助手文本回调：在每次助手回复时通知 UI（仅阻塞模式使用） */
    private Consumer<String> onAssistantMessage;

    /** 流式输出开始回调：通知 UI 停止 spinner */
    private Runnable onStreamStart;

    /** 权限确认回调：危险操作前请求用户确认（返回 PermissionChoice） */
    private Function<PermissionRequest, PermissionChoice> onPermissionRequest;

    /** Thinking 内容回调：显示 AI 的思考过程 */
    private Consumer<String> onThinkingContent;

    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                     ToolContext toolContext, String systemPrompt) {
        this(chatModel, toolRegistry, toolContext, systemPrompt, new TokenTracker());
    }

    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry,
                     ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.systemPrompt = systemPrompt;
        this.tokenTracker = tokenTracker;
        this.hookManager = new HookManager();
        this.toolExecutor = new AgentToolExecutor(hookManager, toolContext, denialTracker);
        this.messageHistory.add(new SystemMessage(systemPrompt));
    }

    public void setOnToolEvent(Consumer<ToolEvent> onToolEvent) {
        this.onToolEvent = onToolEvent;
        this.toolExecutor.setOnToolEvent(onToolEvent);
    }

    public void setOnAssistantMessage(Consumer<String> onAssistantMessage) {
        this.onAssistantMessage = onAssistantMessage;
    }

    public void setOnStreamStart(Runnable onStreamStart) {
        this.onStreamStart = onStreamStart;
    }

    public void setOnPermissionRequest(Function<PermissionRequest, PermissionChoice> onPermissionRequest) {
        this.onPermissionRequest = onPermissionRequest;
        this.toolExecutor.setOnPermissionRequest(onPermissionRequest);
    }

    public void setPermissionEngine(PermissionRuleEngine engine) {
        this.permissionEngine = engine;
        this.toolExecutor.setPermissionEngine(engine);
    }

    public void setAutoCompactManager(AutoCompactManager manager) {
        this.autoCompactManager = manager;
    }

    public void setSessionMemoryService(SessionMemoryService service) {
        this.sessionMemoryService = service;
    }

    public AutoCompactManager getAutoCompactManager() {
        return autoCompactManager;
    }

    public void setOnThinkingContent(Consumer<String> onThinkingContent) {
        this.onThinkingContent = onThinkingContent;
    }

    /** 取消当前运行中的 Agent 循环 */
    public void cancel() {
        cancelled = true;
    }

    /** 重置取消标志（每次新的循环开始时调用） */
    private void resetCancel() {
        cancelled = false;
    }

    // ==================== 阻塞模式 ====================

    /**
     * 阻塞执行一轮用户输入的完整 Agent 循环。
     * 等待完整响应后才返回。
     */
    public String run(String userInput) {
        messageHistory.add(new UserMessage(userInput));
        return executeLoop(false, null);
    }

    // ==================== 流式模式 ====================

    /**
     * 流式执行一轮用户输入的完整 Agent 循环。
     * 文本逐 token 通过 onToken 回调实时输出到终端。
     *
     * @param userInput 用户输入文本
     * @param onToken   每个文本 token 的实时回调（用于终端逐字显示）
     * @return 最终完整的助手回复文本
     */
    public String runStreaming(String userInput, Consumer<String> onToken) {
        messageHistory.add(new UserMessage(userInput));
        return executeLoop(true, onToken);
    }

    // ==================== 核心循环（统一阻塞/流式） ====================

    private String executeLoop(boolean streaming, Consumer<String> onToken) {
        resetCancel();
        List<ToolCallback> callbacks = toolRegistry.toCallbacks(toolContext);
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        int iteration = 0;
        String lastAssistantText = "";

        while (iteration < MAX_ITERATIONS) {
            // 检查取消标志
            if (cancelled) {
                log.info("Agent loop cancelled by user at iteration {}", iteration);
                lastAssistantText += "\n\n[Interrupted by user]";
                break;
            }

            iteration++;
            log.debug("Agent loop iteration {} ({})", iteration, streaming ? "streaming" : "blocking");

            Prompt prompt = new Prompt(List.copyOf(messageHistory), options);

            // 调用 AI 并获取结果
            IterationResult result;
            if (streaming) {
                result = streamIteration(prompt, onToken);
            } else {
                result = blockingIteration(prompt);
            }

            // 检查取消标志（API调用后）
            if (cancelled) {
                log.info("Agent loop cancelled by user after API call at iteration {}", iteration);
                break;
            }

            // 记录 Token 使用量
            if (result.promptTokens > 0 || result.completionTokens > 0) {
                tokenTracker.recordUsage(result.promptTokens, result.completionTokens);
            }

            // 将助手消息加入历史
            messageHistory.add(result.assistant);

            String text = result.assistant.getText();
            if (text != null && !text.isBlank()) {
                lastAssistantText = text;
                // 阻塞模式通知 UI（流式模式已在回调中实时输出）
                if (!streaming && onAssistantMessage != null) {
                    onAssistantMessage.accept(text);
                }
            }

            // 无工具调用 → 结束
            if (!result.assistant.hasToolCalls()) {
                log.debug("No tool calls, loop ended (total {} iterations)", iteration);
                break;
            }

            // 执行工具调用（委托给 AgentToolExecutor）
            var toolResponseMsg = toolExecutor.executeToolCalls(
                    result.assistant.getToolCalls(), callbacks, cancelled);
            messageHistory.add(toolResponseMsg);

            // 自动压缩检查（在工具调用后，下次 API 调用前）
            if (autoCompactManager != null) {
                autoCompactManager.autoCompactIfNeeded(
                        () -> messageHistory,
                        this::replaceHistory
                );
            }

            // 会话记忆提取检查（异步，不阻塞主循环）
            if (sessionMemoryService != null) {
                int toolCallCount = result.assistant.hasToolCalls()
                        ? result.assistant.getToolCalls().size() : 0;
                sessionMemoryService.onPostSampling(
                        result.promptTokens + result.completionTokens,
                        toolCallCount,
                        messageHistory
                );
            }
        }

        if (iteration >= MAX_ITERATIONS) {
            log.warn("Agent loop reached max iterations {}, force stopping", MAX_ITERATIONS);
            lastAssistantText += "\n\n[WARNING: Maximum loop iteration limit reached]";
        }

        return lastAssistantText;
    }

    /** 阻塞模式：调用 chatModel.call() 并解析结果 */
    private IterationResult blockingIteration(Prompt prompt) {
        ChatResponse response = chatModel.call(prompt);

        long promptTokens = 0, completionTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            promptTokens = usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens();
        }

        // 尝试提取 thinking 内容（Anthropic extended thinking）
        extractThinkingContent(response);

        return new IterationResult(response.getResult().getOutput(), promptTokens, completionTokens);
    }

    /** 流式模式：调用 chatModel.stream() 逐 token 输出，累积完整响应 */
    private IterationResult streamIteration(Prompt prompt, Consumer<String> onToken) {
        StringBuilder textBuffer = new StringBuilder();
        // 工具调用按 ID 去重累积（流式分片可能多次发送同一工具调用）
        Map<String, AssistantMessage.ToolCall> toolCallMap = new LinkedHashMap<>();
        long[] tokenUsage = {0, 0};
        boolean[] firstToken = {true};

        try {
            Flux<ChatResponse> flux = chatModel.stream(prompt);

            flux.doOnNext(chunk -> {
                // 记录 token 使用量（通常出现在最后一个 chunk）
                if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                    var usage = chunk.getMetadata().getUsage();
                    if (usage.getPromptTokens() > 0) tokenUsage[0] = usage.getPromptTokens();
                    if (usage.getCompletionTokens() > 0) tokenUsage[1] = usage.getCompletionTokens();
                }

                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return;
                AssistantMessage output = chunk.getResult().getOutput();

                // 实时输出文本 token
                String text = output.getText();
                if (text != null && !text.isEmpty() && !cancelled) {
                    // 第一个 token 到达时通知 UI（停止 spinner）
                    if (firstToken[0]) {
                        firstToken[0] = false;
                        if (onStreamStart != null) onStreamStart.run();
                    }
                    textBuffer.append(text);
                    if (onToken != null) onToken.accept(text);
                }

                // 累积工具调用（按 ID 去重）
                if (output.hasToolCalls()) {
                    for (var tc : output.getToolCalls()) {
                        if (tc.id() != null) {
                            toolCallMap.putIfAbsent(tc.id(), tc);
                        }
                    }
                }
            }).blockLast();

        } catch (Exception e) {
            // 流式调用失败 → 降级到阻塞模式
            log.warn("Streaming call failed, falling back to blocking mode: {}", e.getMessage());
            return blockingIteration(prompt);
        }

        // 使用 Builder 构建 AssistantMessage（构造器是 protected 的）
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(toolCallMap.values());
        AssistantMessage assistant = AssistantMessage.builder()
                .content(textBuffer.toString())
                .toolCalls(toolCalls)
                .build();

        return new IterationResult(assistant, tokenUsage[0], tokenUsage[1]);
    }

    /** 获取消息历史（用于上下文压缩等场景） */
    public List<Message> getMessageHistory() {
        return Collections.unmodifiableList(messageHistory);
    }

    /** 获取 Token 追踪器 */
    public TokenTracker getTokenTracker() {
        return tokenTracker;
    }

    /** 获取系统提示词 */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** 获取 ChatModel（用于上下文压缩等需要直接调用模型的场景） */
    public ChatModel getChatModel() {
        return chatModel;
    }

    /** 获取工具上下文（用于注册回调） */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /** 获取 Hook 管理器 */
    public HookManager getHookManager() {
        return hookManager;
    }

    /** 重置历史（保留系统提示词） */
    public void reset() {
        messageHistory.clear();
        messageHistory.add(new SystemMessage(systemPrompt));
    }

    /** 替换消息历史（用于上下文压缩后替换） */
    public void replaceHistory(List<Message> newHistory) {
        messageHistory.clear();
        messageHistory.addAll(newHistory);
    }

    /** 单次迭代结果 */
    private record IterationResult(AssistantMessage assistant, long promptTokens, long completionTokens) {}

    /**
     * 从 ChatResponse 中尝试提取 thinking 内容。
     * <p>
     * Anthropic 的 extended thinking 功能会在响应中包含思考过程。
     * Spring AI 可能将其放在 metadata 中或作为独立的消息属性。
     */
    private void extractThinkingContent(ChatResponse response) {
        if (onThinkingContent == null) return;

        try {
            // 方式1: 检查 response metadata 中的 thinking 字段
            if (response.getMetadata() != null) {
                var metadata = response.getMetadata();
                // Spring AI 可能在 metadata 中存储 thinking 内容
                // 不同版本可能有不同的 key
                if (metadata instanceof Map<?, ?> metaMap) {
                    Object thinking = metaMap.get("thinking");
                    if (thinking instanceof String thinkText && !thinkText.isBlank()) {
                        onThinkingContent.accept(thinkText);
                        return;
                    }
                }
            }

            // 方式2: 检查 AssistantMessage 的 metadata
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                var output = response.getResult().getOutput();
                var msgMeta = output.getMetadata();
                if (msgMeta != null) {
                    // 尝试获取 thinking 相关的元数据
                    Object thinking = msgMeta.get("thinking");
                    if (thinking instanceof String thinkText && !thinkText.isBlank()) {
                        onThinkingContent.accept(thinkText);
                    }
                }
            }
        } catch (Exception e) {
            // thinking 提取失败不影响主流程
            log.debug("Thinking content extraction exception (can be ignored): {}", e.getMessage());
        }
    }

    /** 权限确认请求 */
    public static class PermissionRequest {
        private final String toolName;
        private final String arguments;
        private final String activityDescription;
        private PermissionDecision decision;

        public PermissionRequest(String toolName, String arguments, String activityDescription) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.activityDescription = activityDescription;
        }

        public String toolName() { return toolName; }
        public String arguments() { return arguments; }
        public String activityDescription() { return activityDescription; }
        public PermissionDecision decision() { return decision; }
        public void setDecision(PermissionDecision decision) { this.decision = decision; }
    }

    /** 工具事件，用于 UI 展示 */
    public record ToolEvent(String toolName, Phase phase, String arguments, String result) {
        public enum Phase { START, PROGRESS, END }
    }
}
