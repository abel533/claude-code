package com.claudecode.tui;

import com.claudecode.config.AppConfig.ProviderInfo;
import com.claudecode.command.CommandRegistry;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.ConversationPersistence;
import com.claudecode.core.TokenTracker;
import com.claudecode.permission.PermissionTypes.PermissionChoice;
import com.claudecode.tui.UIMessage.*;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.AskUserQuestionTool;
import io.mybatis.jink.Ink;
import io.mybatis.jink.style.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 基于 jink 的 REPL 会话 —— 替代原有的 JLine readLine() 模式。
 * <p>
 * 使用 jink 的 Component 模型实现全屏 TUI：
 * <ul>
 *   <li>全屏渲染（alternate screen buffer）</li>
 *   <li>输入区上下有分隔线（最关键的 UI 改进）</li>
 *   <li>消息列表、状态栏、快捷键栏</li>
 *   <li>AgentLoop 在后台线程运行，回调驱动 UI 更新</li>
 * </ul>
 */
public class JinkReplSession {

    private static final Logger log = LoggerFactory.getLogger(JinkReplSession.class);

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final CommandRegistry commandRegistry;
    private final ProviderInfo providerInfo;
    private final ConversationPersistence persistence;
    private final TokenTracker tokenTracker;

    private ClaudeCodeComponent component;
    private Ink.Instance inkApp;
    private String conversationSummary = "";

    public JinkReplSession(AgentLoop agentLoop,
                           ToolRegistry toolRegistry,
                           CommandRegistry commandRegistry,
                           ProviderInfo providerInfo,
                           TokenTracker tokenTracker) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.commandRegistry = commandRegistry;
        this.providerInfo = providerInfo;
        this.persistence = new ConversationPersistence();
        this.tokenTracker = tokenTracker;
    }

    /**
     * 启动 jink TUI 会话。
     */
    public void start() {
        try {
            startJink();
        } catch (Exception e) {
            log.error("Jink TUI startup failed: {}", e.getMessage(), e);
            System.err.println("TUI startup failed: " + e.getMessage());
            System.err.println("Please use a terminal that supports ANSI escape codes.");
        }
    }

    private void startJink() {
        // 创建主组件
        component = new ClaudeCodeComponent(
                agentLoop,
                commandRegistry,
                toolRegistry,
                providerInfo.provider(),
                providerInfo.model(),
                providerInfo.baseUrl(),
                toolRegistry.size(),
                tokenTracker,
                this::exit
        );

        // 注册 AgentLoop 回调
        setupAgentCallbacks();
        setupToolContextCallbacks();

        // 注册首次用户输入回调（用于对话摘要）
        component.setOnFirstUserInput(text -> {
            conversationSummary = text.length() > 40 ? text.substring(0, 40) : text;
        });

        // 启动 jink 渲染（exitOnCtrlC=false，让组件处理 Ctrl+C）
        inkApp = Ink.render(component, false);

        // 设置 inkApp 引用，使组件可以通过 writeRaw 设置终端标题
        component.setInkApp(inkApp);

        // 拦截 System.out/err，防止日志干扰 TUI
        inkApp.patchConsole();

        // 阻塞等待退出
        inkApp.waitUntilExit();

        // 退出后保存对话
        saveConversation();
    }

    /** 注册 AgentLoop 事件回调，驱动 TUI 更新 */
    private void setupAgentCallbacks() {
        // 工具调用事件
        agentLoop.setOnToolEvent(event -> {
            switch (event.phase()) {
                case START -> {
                    // 完成当前流式消息（如果有）
                    finishCurrentStreaming();
                    component.addMessage(new ToolCallMsg(
                            event.toolName(),
                            event.arguments(),
                            null,
                            true
                    ));
                }
                case PROGRESS -> {
                    // 工具执行中的流式输出行
                    component.appendToolOutput(event.result());
                }
                case END -> {
                    component.completeLastToolCall(event.result());
                }
            }
        });

        // 流式输出第一个 token
        agentLoop.setOnStreamStart(() -> {
            component.setThinking(false, "");
        });

        // 阻塞模式回调（流式模式不使用）
        agentLoop.setOnAssistantMessage(text -> {});

        // 权限确认回调
        agentLoop.setOnPermissionRequest(request -> {
            return promptPermissionInTui(request);
        });

        // Thinking 内容回调
        agentLoop.setOnThinkingContent(thinkingText -> {
            component.setThinking(true, thinkingText);
            component.addMessage(new ThinkingMsg(thinkingText));
        });
    }

    /** 注册 ToolContext 回调（AskUser） */
    private void setupToolContextCallbacks() {
        var toolContext = agentLoop.getToolContext();
        if (toolContext != null) {
            // 简单文本回调（兜底）
            toolContext.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                    (Function<String, String>) this::askUserInTui);
            // 结构化回调（支持交互式选择）
            toolContext.set(AskUserQuestionTool.ASK_USER_STRUCTURED_CALLBACK,
                    (java.util.function.BiFunction<String, java.util.List<String>, String>) this::askUserStructured);
        }
    }

    /** 在 TUI 中请求权限确认 */
    private PermissionChoice promptPermissionInTui(AgentLoop.PermissionRequest request) {
        // 完成当前流式消息
        finishCurrentStreaming();

        // 添加权限请求消息
        boolean isDangerous = request.decision() != null
                && request.decision().reason() != null
                && request.decision().reason().startsWith("⚠ DANGEROUS");

        String suggestedRule = null;
        if (request.decision() != null && request.decision().commandPrefix() != null) {
            suggestedRule = request.toolName() + "(" + request.decision().commandPrefix() + ":*)";
        }

        component.addMessage(new PermissionMsg(
                request.toolName(),
                request.activityDescription(),
                request.arguments(),
                isDangerous,
                suggestedRule,
                false
        ));

        // 使用 CompletableFuture 阻塞等待用户选择
        CompletableFuture<String> future = new CompletableFuture<>();
        component.requestPermission(request.toolName(), suggestedRule, future::complete);

        try {
            String answer = future.get();
            answer = answer.strip().toLowerCase();

            return switch (answer) {
                case "a", "always" -> {
                    component.addMessage(new SystemMsg(
                            "✓ Rule saved: always allow " + (suggestedRule != null ? suggestedRule : request.toolName()),
                            Color.BRIGHT_GREEN));
                    yield PermissionChoice.ALWAYS_ALLOW;
                }
                case "n", "no" -> {
                    component.addMessage(new SystemMsg("✗ Operation denied", Color.BRIGHT_RED));
                    yield PermissionChoice.DENY_ONCE;
                }
                default -> PermissionChoice.ALLOW_ONCE;
            };
        } catch (Exception e) {
            log.error("Permission prompt interrupted", e);
            return PermissionChoice.DENY_ONCE;
        }
    }

    /** 在 TUI 中请求用户输入（AskUser 工具 — 简单文本模式） */
    private String askUserInTui(String prompt) {
        finishCurrentStreaming();
        component.addMessage(new SystemMsg(prompt, Color.BRIGHT_CYAN));

        CompletableFuture<String> future = new CompletableFuture<>();
        component.requestTextInput(future::complete);

        try {
            return future.get();
        } catch (Exception e) {
            return "(User cancelled)";
        }
    }

    /** 在 TUI 中请求用户输入（结构化模式 — 支持交互式选择） */
    private String askUserStructured(String question, java.util.List<String> options) {
        finishCurrentStreaming();

        // 添加问题到消息列表
        component.addMessage(new SystemMsg("🤔 " + question, Color.BRIGHT_CYAN));

        CompletableFuture<String> future = new CompletableFuture<>();

        if (options != null && !options.isEmpty()) {
            // 有选项 — 使用交互式选择
            component.requestAskUser(question, options, future::complete);
        } else {
            // 无选项 — 使用普通输入
            component.requestTextInput(future::complete);
        }

        try {
            return future.get();
        } catch (Exception e) {
            return "(User cancelled)";
        }
    }

    /** 完成当前流式消息（如果存在） */
    private void finishCurrentStreaming() {
        component.finishStreamingMessage();
    }

    /** 退出并清理 */
    private void exit() {
        saveConversation();
        if (inkApp != null) {
            inkApp.exit();
        }
    }

    /** 保存对话历史 */
    private void saveConversation() {
        var history = agentLoop.getMessageHistory();
        if (history.size() > 2) {
            var file = persistence.save(history, conversationSummary);
            if (file != null) {
                log.info("Conversation saved: {}", file.getFileName());
            }
        }
    }

    /** 获取对话持久化管理器 */
    public ConversationPersistence getPersistence() {
        return persistence;
    }
}
