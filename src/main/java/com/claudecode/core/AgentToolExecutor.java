package com.claudecode.core;

import com.claudecode.permission.DenialTracker;
import com.claudecode.permission.PermissionRuleEngine;
import com.claudecode.permission.PermissionTypes.PermissionChoice;
import com.claudecode.permission.PermissionTypes.PermissionDecision;
import com.claudecode.tool.ToolCallbackAdapter;
import com.claudecode.tool.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 工具执行器 —— 从 AgentLoop 拆分出的工具调用执行逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>解析工具参数</li>
 *   <li>PreToolUse / PostToolUse Hook 执行</li>
 *   <li>权限检查（规则引擎 + 传统回调）</li>
 *   <li>工具调用执行与结果收集</li>
 * </ul>
 */
public class AgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HookManager hookManager;
    private final ToolContext toolContext;
    private final DenialTracker denialTracker;

    private PermissionRuleEngine permissionEngine;
    private Consumer<AgentLoop.ToolEvent> onToolEvent;
    private Function<AgentLoop.PermissionRequest, PermissionChoice> onPermissionRequest;

    public AgentToolExecutor(HookManager hookManager, ToolContext toolContext, DenialTracker denialTracker) {
        this.hookManager = hookManager;
        this.toolContext = toolContext;
        this.denialTracker = denialTracker;
    }

    public void setPermissionEngine(PermissionRuleEngine engine) {
        this.permissionEngine = engine;
    }

    public void setOnToolEvent(Consumer<AgentLoop.ToolEvent> onToolEvent) {
        this.onToolEvent = onToolEvent;
    }

    public void setOnPermissionRequest(Function<AgentLoop.PermissionRequest, PermissionChoice> onPermissionRequest) {
        this.onPermissionRequest = onPermissionRequest;
    }

    /**
     * 执行工具调用列表并返回 ToolResponseMessage 加入消息历史。
     */
    @SuppressWarnings("unchecked")
    public ToolResponseMessage executeToolCalls(List<AssistantMessage.ToolCall> toolCalls,
                                                 List<ToolCallback> callbacks,
                                                 boolean cancelled) {
        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            if (cancelled) {
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), "Cancelled by user"));
                continue;
            }

            String toolName = toolCall.name();
            String toolArgs = toolCall.arguments();
            String callId = toolCall.id();

            Map<String, Object> parsedArgs = parseArguments(toolName, toolArgs);

            // PreToolUse Hook
            var preHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            if (hookManager.execute(HookManager.HookType.PRE_TOOL_USE, preHookCtx) == HookManager.HookResult.ABORT) {
                log.info("[{}] PreToolUse Hook aborted execution", toolName);
                toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, "Aborted by hook"));
                continue;
            }

            if (onToolEvent != null) {
                onToolEvent.accept(new AgentLoop.ToolEvent(toolName, AgentLoop.ToolEvent.Phase.START, toolArgs, null));
            }

            String result = executeOneTool(toolName, toolArgs, parsedArgs, callbacks);

            // PostToolUse Hook
            var postHookCtx = new HookManager.HookContext(toolName, parsedArgs);
            postHookCtx.setResult(result);
            hookManager.execute(HookManager.HookType.POST_TOOL_USE, postHookCtx);
            if (postHookCtx.getResult() != null) {
                result = postHookCtx.getResult();
            }

            if (onToolEvent != null) {
                onToolEvent.accept(new AgentLoop.ToolEvent(toolName, AgentLoop.ToolEvent.Phase.END, toolArgs, result));
            }

            toolResponses.add(new ToolResponseMessage.ToolResponse(callId, toolName, result));
        }

        return ToolResponseMessage.builder().responses(toolResponses).build();
    }

    /**
     * 执行单个工具调用（含权限检查）。
     */
    private String executeOneTool(String toolName, String toolArgs,
                                  Map<String, Object> parsedArgs,
                                  List<ToolCallback> callbacks) {
        ToolCallbackAdapter adapter = findCallbackByName(callbacks, toolName);
        if (adapter == null) {
            log.warn("Unknown tool: {}", toolName);
            return "Error: Unknown tool '" + toolName + "'";
        }

        boolean permitted = checkPermission(toolName, toolArgs, parsedArgs, adapter);
        if (!permitted) {
            log.info("[{}] User denied tool execution", toolName);
            return "Permission denied: User rejected this operation";
        }

        // 设置进度回调
        if (onToolEvent != null) {
            toolContext.setProgressCallback(line ->
                    onToolEvent.accept(new AgentLoop.ToolEvent(
                            toolName, AgentLoop.ToolEvent.Phase.PROGRESS, toolArgs, line)));
        }
        try {
            return adapter.call(toolArgs);
        } finally {
            toolContext.setProgressCallback(null);
        }
    }

    /**
     * 权限检查：规则引擎优先，回退到传统回调。
     */
    private boolean checkPermission(String toolName, String toolArgs,
                                    Map<String, Object> parsedArgs,
                                    ToolCallbackAdapter adapter) {
        if (permissionEngine != null) {
            PermissionDecision decision = permissionEngine.evaluate(
                    toolName, parsedArgs, adapter.getTool().isReadOnly());

            if (decision.isAllowed()) {
                denialTracker.recordSuccess();
                return true;
            } else if (decision.isDenied()) {
                denialTracker.recordDenial();
                log.info("[{}] Denied by rule: {}", toolName, decision.reason());
                return false;
            } else if (decision.needsAsk() && onPermissionRequest != null) {
                if (denialTracker.shouldFallbackToPrompting()) {
                    log.info("[{}] Denial threshold reached, forcing manual prompt", toolName);
                }
                String activity = adapter.getTool().activityDescription(parsedArgs);
                AgentLoop.PermissionRequest req = new AgentLoop.PermissionRequest(toolName, toolArgs, activity);
                req.setDecision(decision);
                PermissionChoice choice = onPermissionRequest.apply(req);
                boolean allowed = (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
                if (allowed) denialTracker.recordSuccess(); else denialTracker.recordDenial();
                String command = parsedArgs != null ? (String) parsedArgs.get("command") : null;
                permissionEngine.applyChoice(choice, toolName, command);
                return allowed;
            } else {
                denialTracker.recordDenial();
                return false;
            }
        }

        // 传统回调模式
        if (!adapter.getTool().isReadOnly() && onPermissionRequest != null) {
            String activity = adapter.getTool().activityDescription(parsedArgs);
            AgentLoop.PermissionRequest req = new AgentLoop.PermissionRequest(toolName, toolArgs, activity);
            PermissionChoice choice = onPermissionRequest.apply(req);
            return (choice == PermissionChoice.ALLOW_ONCE || choice == PermissionChoice.ALWAYS_ALLOW);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String toolName, String toolArgs) {
        try {
            return MAPPER.readValue(toolArgs, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse tool arguments for {}: {}", toolName, e.getMessage());
            return Map.of();
        }
    }

    private ToolCallbackAdapter findCallbackByName(List<ToolCallback> callbacks, String name) {
        for (ToolCallback cb : callbacks) {
            if (cb instanceof ToolCallbackAdapter adapter && adapter.getTool().name().equals(name)) {
                return adapter;
            }
        }
        return null;
    }
}
