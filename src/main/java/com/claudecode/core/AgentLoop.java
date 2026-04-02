package com.claudecode.core;

import com.claudecode.core.compact.AutoCompactManager;
import com.claudecode.permission.PermissionRuleEngine;
import com.claudecode.permission.PermissionTypes.PermissionChoice;
import com.claudecode.permission.PermissionTypes.PermissionDecision;
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
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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

    /** 消息历史 —— 自行管理，不依赖 Spring AI ChatMemory */
    private final List<Message> messageHistory = new ArrayList<>();

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
        this.messageHistory.add(new SystemMessage(systemPrompt));
    }

    public void setOnToolEvent(Consumer<ToolEvent> onToolEvent) {
        this.onToolEvent = onToolEvent;
    }

    public void setOnAssistantMessage(Consumer<String> onAssistantMessage) {
        this.onAssistantMessage = onAssistantMessage;
    }

    public void setOnStreamStart(Runnable onStreamStart) {
        this.onStreamStart = onStreamStart;
    }

    public void setOnPermissionRequest(Function<PermissionRequest, PermissionChoice> onPermissionRequest) {
        this.onPermissionRequest = onPermissionRequest;
    }

    public void setPermissionEngine(PermissionRuleEngine engine) {
        this.permissionEngine = engine;
    }

    public void setAutoCompactManager(AutoCompactManager manager) {
        this.autoCompactManager = manager;
    }

    public AutoCompactManager getAutoCompactManager() {
        return autoCompactManager;
    }

    public void setOnThinkingContent(Consumer<String> onThinkingContent) {
        this.onThinkingContent = onThinkingContent;
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
        List<ToolCallback> callbacks = toolRegistry.toCallbacks(toolContext);
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();

        int iteration = 0;
        String lastAssistantText = "";

        while (iteration < MAX_ITERATIONS) {
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

            // 执行工具调用
            executeToolCalls(result.assistant.getToolCalls(), callbacks);

            // 自动压缩检查（在工具调用后，下次 API 调用前）
            if (autoCompactManager != null) {
                autoCompactManager.autoCompactIfNeeded(
                        () -> messageHistory,
                        this::replaceHistory
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
                if (text != null && !text.isEmpty()) {
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

    /** 执行工具调用列表并将结果加入消息历史 */
    @SuppressWarnings("unchecked")
    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls,
                                  List<ToolCallback> callbacks) {
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            String toolArgs = toolCall.arguments();
            String callId = toolCall.id();

            // 解析参数用于 Hook 和权限检查
            Map<String, Object> parsedArgs = Map.of();
            try {
                parsedArgs = MAPPER.readValue(toolArgs, Map.class);
            } catch (Exception ignored) {}

            // PreToolUse Hook
            var preHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            if (hookManager.execute(HookManager.HookType.PRE_TOOL_USE, preHookCtx) == HookManager.HookResult.ABORT) {
                log.info("[{}] PreToolUse Hook aborted execution", toolName);
                toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, "Aborted by hook"));
                continue;
            }

            if (onToolEvent != null) {
                onToolEvent.accept(new ToolEvent(toolName, ToolEvent.Phase.START, toolArgs, null));
            }

            String result;
            ToolCallbackAdapter adapter = findCallbackByName(callbacks, toolName);
            if (adapter != null) {
                // 权限检查：优先使用规则引擎，回退到传统回调
                boolean permitted = true;
                if (permissionEngine != null) {
                    PermissionDecision decision = permissionEngine.evaluate(
                            toolName, parsedArgs, adapter.getTool().isReadOnly());
                    if (decision.isAllowed()) {
                        permitted = true;
                    } else if (decision.isDenied()) {
                        permitted = false;
                        log.info("[{}] Denied by rule: {}", toolName, decision.reason());
                    } else if (decision.needsAsk() && onPermissionRequest != null) {
                        String activity = adapter.getTool().activityDescription(parsedArgs);
                        PermissionRequest req = new PermissionRequest(toolName, toolArgs, activity);
                        req.setDecision(decision);
                        PermissionChoice choice = onPermissionRequest.apply(req);
                        permitted = (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
                        // 持久化用户选择
                        String command = parsedArgs != null ? (String) parsedArgs.get("command") : null;
                        permissionEngine.applyChoice(choice, toolName, command);
                    } else {
                        permitted = false;
                    }
                } else if (!adapter.getTool().isReadOnly() && onPermissionRequest != null) {
                    // 传统回调模式（向后兼容）
                    String activity = adapter.getTool().activityDescription(parsedArgs);
                    PermissionRequest req = new PermissionRequest(toolName, toolArgs, activity);
                    PermissionChoice choice = onPermissionRequest.apply(req);
                    permitted = (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
                }

                if (permitted) {
                    result = adapter.call(toolArgs);
                } else {
                    result = "Permission denied: User rejected this operation";
                    log.info("[{}] User denied tool execution", toolName);
                }
            } else {
                result = "Error: Unknown tool '" + toolName + "'";
                log.warn("Unknown tool: {}", toolName);
            }

            // PostToolUse Hook
            var postHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            postHookCtx.setResult(result);
            hookManager.execute(HookManager.HookType.POST_TOOL_USE, postHookCtx);
            // Hook 可能修改了结果
            if (postHookCtx.getResult() != null) {
                result = postHookCtx.getResult();
            }

            if (onToolEvent != null) {
                onToolEvent.accept(new ToolEvent(toolName, ToolEvent.Phase.END, toolArgs, result));
            }

            toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, result));
        }

        messageHistory.add(ToolResponseMessage.builder().responses(toolResponses).build());
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
        public enum Phase { START, END }
    }
}
