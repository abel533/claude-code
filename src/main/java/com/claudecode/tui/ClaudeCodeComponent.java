package com.claudecode.tui;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandRegistry;
import com.claudecode.console.BannerPrinter;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.TokenTracker;
import com.claudecode.tui.UIMessage.*;
import io.mybatis.jink.component.*;
import io.mybatis.jink.input.Key;
import io.mybatis.jink.style.*;
import io.mybatis.jink.util.StringWidth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Claude Code 主界面组件 —— 使用 jink 框架实现全屏 TUI。
 * <p>
 * 布局结构（从上到下）：
 * <pre>
 * ╭─── Claude Code Java v0.1.0 ───────────────────╮  ← 标题框
 * │  ...                                           │
 * ╰────────────────────────────────────────────────╯
 *  ● System message...                                ← 消息列表
 *  ● User: hello                                      （带虚拟滚动）
 *  ● AI response...
 *                                                     ← 弹性空白
 *  path/to/dir                    model info           ← 状态栏
 * ────────────────────────────────────────────────────  ← 上分隔线
 *  ❯ user input here                                   ← 输入区
 * ────────────────────────────────────────────────────  ← 下分隔线
 *  ↑↓ history   wheel messages          tokens: xxx   ← 快捷键栏
 * </pre>
 */
public class ClaudeCodeComponent extends Component<ClaudeCodeComponent.TuiState> {

    private static final int PROMPT_WIDTH = 2; // "❯ "

    /** TUI 全局状态 */
    record TuiState(
            String inputText,
            List<UIMessage> messages,
            int scrollOffset,
            boolean thinking,
            String thinkingText
    ) {
        static TuiState empty() {
            return new TuiState("", List.of(), 0, false, "");
        }
    }

    // --- 外部依赖（通过构造器注入） ---
    private final AgentLoop agentLoop;
    private final CommandRegistry commandRegistry;
    private final String provider;
    private final String model;
    private final String baseUrl;
    private final int toolCount;
    private final int cmdCount;
    private final TokenTracker tokenTracker;
    private final Runnable onExit;

    // --- 内部状态 ---
    private final List<String> inputHistory = new ArrayList<>();
    private int historyIndex = -1;
    private String savedInput = "";
    private final AtomicBoolean agentRunning = new AtomicBoolean(false);

    /** 权限确认回调（由权限请求设置，用户输入后调用） */
    private volatile Consumer<String> permissionCallback;

    /** 首次用户输入回调（用于 conversation summary） */
    private Consumer<String> onFirstUserInput;

    public ClaudeCodeComponent(AgentLoop agentLoop,
                               CommandRegistry commandRegistry,
                               String provider, String model, String baseUrl,
                               int toolCount, int cmdCount,
                               TokenTracker tokenTracker,
                               Runnable onExit) {
        super(TuiState.empty());
        this.agentLoop = agentLoop;
        this.commandRegistry = commandRegistry;
        this.provider = provider;
        this.model = model;
        this.baseUrl = baseUrl;
        this.toolCount = toolCount;
        this.cmdCount = cmdCount;
        this.tokenTracker = tokenTracker;
        this.onExit = onExit;
    }

    // ==================== 渲染 ====================

    @Override
    public Renderable render() {
        TuiState s = getState();
        int w = getColumns();
        int h = getRows();

        // 计算输入区行数
        int inputLineCount = 1;
        String lastLine = s.inputText;
        if (!s.inputText.isEmpty()) {
            String[] inputLines = s.inputText.split("\n", -1);
            inputLineCount = inputLines.length;
            lastLine = inputLines[inputLines.length - 1];
        }

        // 光标定位：底部结构 shortcutBar(1) + separator(1) + input(N) + separator(1) + statusBar(1)
        int cursorRow = h - 3;
        int cursorCol = 1 + PROMPT_WIDTH + StringWidth.width(lastLine);
        setCursorPosition(cursorRow, cursorCol);

        int headerHeight = 7;
        int bottomHeight = 4 + inputLineCount;
        int messagePaddingTop = 1;
        int maxMessageLines = h - headerHeight - bottomHeight - messagePaddingTop;

        return Box.of(
                headerBox(w),
                messagesArea(s, maxMessageLines),
                Spacer.create(),
                statusBar(w, h),
                separator(w),
                inputArea(s, w),
                separator(w),
                shortcutBar(w)
        ).flexDirection(FlexDirection.COLUMN).width(w).height(h);
    }

    /** 标题框（圆角洋红色边框） */
    private Renderable headerBox(int w) {
        return Box.of(
                Text.of(
                        Text.of("☕").color(Color.BRIGHT_YELLOW),
                        Text.of("  "),
                        Text.of("Claude Code").color(Color.BRIGHT_MAGENTA).bold(),
                        Text.of(" (Java)").color(Color.WHITE),
                        Text.of(" v" + BannerPrinter.getVersion()).dimmed()
                ),
                Text.of(
                        Text.of("▸ ").color(Color.BRIGHT_CYAN),
                        Text.of("API: ").dimmed(),
                        Text.of(baseUrl).color(Color.BRIGHT_CYAN)
                ),
                Text.of(
                        Text.of("▸ ").color(Color.BRIGHT_CYAN),
                        Text.of("Provider: ").dimmed(),
                        Text.of(provider.toUpperCase()).color(Color.BRIGHT_GREEN),
                        Text.of("  Model: ").dimmed(),
                        Text.of(model).color(Color.BRIGHT_GREEN)
                ),
                Text.of(" "),
                Text.of(
                        Text.of("Tip: ").dimmed(),
                        Text.of("/help").color(Color.BRIGHT_CYAN).bold(),
                        Text.of(" for commands • ").dimmed(),
                        Text.of("Ctrl+D").color(Color.BRIGHT_CYAN).bold(),
                        Text.of(" to exit").dimmed()
                )
        ).flexDirection(FlexDirection.COLUMN)
                .borderStyle(BorderStyle.ROUND)
                .borderColor(Color.BRIGHT_MAGENTA)
                .paddingX(1);
    }

    /** 消息列表（带虚拟滚动） */
    private Renderable messagesArea(TuiState s, int maxLines) {
        List<Renderable> allItems = new ArrayList<>();

        // 初始系统消息
        allItems.add(msgLine(Color.BRIGHT_BLUE,
                "Tools: " + toolCount + " | Commands: " + cmdCount + " | Work Dir: " + System.getProperty("user.dir")));

        // 渲染所有消息
        for (UIMessage msg : s.messages) {
            allItems.addAll(renderMessage(msg));
        }

        // Thinking 状态
        if (s.thinking && !s.thinkingText.isEmpty()) {
            allItems.add(Text.of(
                    Text.of("◐ ").color(Color.BRIGHT_MAGENTA),
                    Text.of("Thinking...").color(Color.BRIGHT_MAGENTA).italic()
            ));
        }

        // 虚拟滚动
        List<Renderable> visibleItems;
        if (maxLines > 0 && allItems.size() > maxLines) {
            int endIdx = allItems.size() - s.scrollOffset;
            int startIdx = Math.max(0, endIdx - maxLines);
            endIdx = Math.min(allItems.size(), startIdx + maxLines);
            visibleItems = allItems.subList(startIdx, endIdx);
        } else {
            visibleItems = allItems;
        }

        return Box.of(visibleItems.toArray(new Renderable[0]))
                .flexDirection(FlexDirection.COLUMN)
                .paddingTop(1)
                .paddingX(1);
    }

    /** 将 UIMessage 渲染为 Renderable 列表（一条消息可能产生多行） */
    private List<Renderable> renderMessage(UIMessage msg) {
        return switch (msg) {
            case UserMsg m -> List.of(Text.of(
                    Text.of("❯ ").color(Color.BRIGHT_GREEN).bold(),
                    Text.of(m.text()).color(Color.WHITE).bold()
            ));

            case AssistantMsg m -> {
                List<Renderable> lines = new ArrayList<>();
                lines.add(Text.of(
                        Text.of("● ").color(Color.BRIGHT_CYAN),
                        Text.of(m.streaming() ? m.text() + "▌" : m.text()).color(Color.WHITE)
                ));
                yield lines;
            }

            case ToolCallMsg m -> {
                List<Renderable> lines = new ArrayList<>();
                String argPreview = m.args() != null && m.args().length() > 60
                        ? m.args().substring(0, 60) + "..."
                        : (m.args() != null ? m.args() : "");
                if (m.running()) {
                    lines.add(Text.of(
                            Text.of("  ● ").color(Color.BRIGHT_BLUE),
                            Text.of(m.toolName()).color(Color.BRIGHT_CYAN).bold(),
                            Text.of(" " + argPreview).dimmed()
                    ));
                } else {
                    lines.add(Text.of(
                            Text.of("  ● ").color(Color.BRIGHT_GREEN),
                            Text.of(m.toolName()).color(Color.BRIGHT_CYAN),
                            Text.of(" ✓").color(Color.BRIGHT_GREEN)
                    ));
                    if (m.result() != null && !m.result().isBlank()) {
                        String preview = m.result().length() > 200
                                ? m.result().substring(0, 200) + "..."
                                : m.result();
                        for (String line : preview.split("\n")) {
                            lines.add(Text.of(
                                    Text.of("  ⎿  ").dimmed(),
                                    Text.of(line).dimmed()
                            ));
                        }
                    }
                }
                yield lines;
            }

            case ThinkingMsg m -> List.of(Text.of(
                    Text.of("  ◐ ").color(Color.BRIGHT_MAGENTA),
                    Text.of(m.text().length() > 100 ? m.text().substring(0, 100) + "..." : m.text())
                            .color(Color.BRIGHT_MAGENTA).dimmed()
            ));

            case SystemMsg m -> List.of(msgLine(m.color(), m.text()));

            case TimingMsg m -> List.of(Text.of(
                    Text.of("  ✻ ").dimmed(),
                    Text.of("Worked for " + m.seconds() + "s").dimmed()
            ));

            case PermissionMsg m -> {
                List<Renderable> lines = new ArrayList<>();
                lines.add(Text.of(
                        m.dangerous()
                                ? Text.of("⚠ DANGEROUS Operation").color(Color.BRIGHT_RED).bold()
                                : Text.of("⚠ Permission Required").color(Color.BRIGHT_YELLOW).bold()
                ));
                lines.add(Text.of(
                        Text.of("  Tool: ").bold(),
                        Text.of(m.toolName()).color(Color.BRIGHT_CYAN)
                ));
                lines.add(Text.of(
                        Text.of("  Action: "),
                        Text.of(m.action()).color(Color.WHITE)
                ));
                if (!m.answered()) {
                    lines.add(Text.of(
                            Text.of("  [Y]").color(Color.BRIGHT_GREEN),
                            Text.of(" Allow  "),
                            Text.of("[A]").color(Color.BRIGHT_GREEN),
                            Text.of(" Always  "),
                            Text.of("[N]").color(Color.BRIGHT_RED),
                            Text.of(" Deny  "),
                            Text.of("[D]").color(Color.BRIGHT_RED),
                            Text.of(" Always deny")
                    ));
                }
                yield lines;
            }

            case CommandOutputMsg m -> {
                List<Renderable> lines = new ArrayList<>();
                for (String line : m.text().split("\n")) {
                    lines.add(Text.of(Text.of("  " + line).dimmed()));
                }
                yield lines;
            }
        };
    }

    /** 单条状态消息行 */
    private Renderable msgLine(Color dotColor, String text) {
        return Text.of(
                Text.of("● ").color(dotColor),
                Text.of(text).color(Color.WHITE)
        );
    }

    /** 状态栏 */
    private Renderable statusBar(int w, int h) {
        String left = System.getProperty("user.dir", ".") + " (" + w + "×" + h + ")";
        String right = model + " | " + provider.toUpperCase();

        return Box.of(
                Text.of(left).dimmed(),
                Spacer.create(),
                Text.of(right).dimmed()
        ).paddingX(1);
    }

    /** 分隔线 */
    private Renderable separator(int w) {
        return Box.of(
                Text.of("─".repeat(Math.max(0, w - 2))).color(Color.BRIGHT_BLACK)
        ).paddingX(1);
    }

    /** 输入区 */
    private Renderable inputArea(TuiState s, int w) {
        Text prompt = Text.of("❯ ").color(Color.BRIGHT_GREEN).bold();
        Text content;

        if (permissionCallback != null) {
            // 权限确认模式
            content = Text.of(s.inputText.isEmpty()
                    ? "Y/a/n/d >"
                    : s.inputText).color(Color.BRIGHT_YELLOW);
        } else if (agentRunning.get()) {
            // AI 正在运行
            content = Text.of(s.thinking ? "◐ Thinking..." : "● Processing...").color(Color.BRIGHT_CYAN).dimmed();
            prompt = Text.of("  ").dimmed();
        } else if (s.inputText.isEmpty()) {
            content = Text.of("Type a message, / for commands, or Ctrl+D to exit").dimmed();
        } else {
            String indent = " ".repeat(PROMPT_WIDTH);
            String displayText = s.inputText.replace("\n", "\n" + indent);
            content = Text.of(displayText).color(Color.WHITE);
        }

        return Box.of(
                Text.of(prompt, content)
        ).paddingX(1);
    }

    /** 快捷键栏 */
    private Renderable shortcutBar(int w) {
        // Token 统计
        String tokenInfo = "";
        if (tokenTracker != null) {
            long input = tokenTracker.getInputTokens();
            long output = tokenTracker.getOutputTokens();
            if (input > 0 || output > 0) {
                tokenInfo = "↑" + formatTokens(input) + " ↓" + formatTokens(output);
            }
        }

        return Box.of(
                Text.of(
                        Text.of("↑↓").dimmed(),
                        Text.of(" history").dimmed(),
                        Text.of("  "),
                        Text.of("wheel").dimmed(),
                        Text.of(" scroll").dimmed(),
                        Text.of("  "),
                        Text.of("Ctrl+D").dimmed(),
                        Text.of(" exit").dimmed()
                ),
                Spacer.create(),
                Text.of(tokenInfo).color(Color.BRIGHT_GREEN)
        ).paddingX(1);
    }

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    // ==================== 输入处理 ====================

    @Override
    public void onInput(String input, Key key) {
        TuiState s = getState();

        // Ctrl+D: 退出
        if (key.ctrl() && "d".equals(input)) {
            if (onExit != null) onExit.run();
            return;
        }

        // Ctrl+C: 取消当前输入或中断 Agent
        if (key.ctrl() && "c".equals(input)) {
            if (agentRunning.get()) {
                // TODO: 中断 Agent 运行
                addMessage(new SystemMsg("^C (interrupt)", Color.BRIGHT_YELLOW));
            } else {
                setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
            }
            return;
        }

        // 权限确认模式
        if (permissionCallback != null) {
            handlePermissionInput(input, key, s);
            return;
        }

        // AI 运行中时忽略大部分输入（但允许滚动）
        if (agentRunning.get()) {
            handleScrollInput(key, s);
            return;
        }

        if (key.return_() && key.meta()) {
            // Shift+Enter: 多行换行
            setState(new TuiState(s.inputText + "\n", s.messages, 0, false, ""));
        } else if (key.return_()) {
            // Enter: 发送
            if (!s.inputText.isEmpty()) {
                submitInput(s.inputText, s);
            }
        } else if (key.backspace()) {
            if (!s.inputText.isEmpty()) {
                abandonHistoryPreview();
                String newText = s.inputText.substring(0, s.inputText.length() - 1);
                setState(new TuiState(newText, s.messages, s.scrollOffset, false, ""));
            }
        } else if (key.upArrow()) {
            browseHistoryUp(s);
        } else if (key.downArrow()) {
            browseHistoryDown(s);
        } else if (key.scrollUp()) {
            scroll(s, 3);
        } else if (key.scrollDown()) {
            scroll(s, -3);
        } else if (key.pageUp()) {
            scroll(s, 10);
        } else if (key.pageDown()) {
            scroll(s, -10);
        } else if (key.escape()) {
            // Esc: 清空输入
            setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
        } else if (!input.isEmpty() && isPrintableInput(input, key)) {
            abandonHistoryPreview();
            setState(new TuiState(s.inputText + input, s.messages, s.scrollOffset, false, ""));
        }
    }

    /** 处理权限确认输入 */
    private void handlePermissionInput(String input, Key key, TuiState s) {
        if (key.return_()) {
            String answer = s.inputText.isEmpty() ? "y" : s.inputText;
            Consumer<String> cb = permissionCallback;
            permissionCallback = null;
            setState(new TuiState("", s.messages, 0, false, ""));
            if (cb != null) cb.accept(answer);
        } else if (key.backspace() && !s.inputText.isEmpty()) {
            setState(new TuiState(s.inputText.substring(0, s.inputText.length() - 1),
                    s.messages, s.scrollOffset, false, ""));
        } else if (!input.isEmpty() && isPrintableInput(input, key)) {
            setState(new TuiState(s.inputText + input, s.messages, s.scrollOffset, false, ""));
        }
    }

    /** 处理滚动输入 */
    private void handleScrollInput(Key key, TuiState s) {
        if (key.scrollUp()) scroll(s, 3);
        else if (key.scrollDown()) scroll(s, -3);
        else if (key.pageUp()) scroll(s, 10);
        else if (key.pageDown()) scroll(s, -10);
    }

    /** 提交用户输入 */
    private void submitInput(String text, TuiState s) {
        inputHistory.add(text);
        historyIndex = -1;
        savedInput = "";

        // 斜杠命令
        if (commandRegistry != null && commandRegistry.isCommand(text)) {
            addMessage(new UserMsg(text));
            CommandContext cmdCtx = new CommandContext(agentLoop, null, commandRegistry,
                    new java.io.PrintStream(java.io.OutputStream.nullOutputStream()), () -> {
                if (onExit != null) onExit.run();
            });
            Optional<String> result = commandRegistry.dispatch(text, cmdCtx);
            result.ifPresent(r -> addMessage(new CommandOutputMsg(r)));
            setState(new TuiState("", getState().messages, 0, false, ""));
            return;
        }

        // Agent 调用
        if (onFirstUserInput != null) {
            onFirstUserInput.accept(text);
            onFirstUserInput = null; // 只触发一次
        }
        addMessage(new UserMsg(text));
        setState(new TuiState("", getState().messages, 0, true, ""));
        runAgent(text);
    }

    /** 在后台线程运行 Agent 循环 */
    private void runAgent(String userInput) {
        agentRunning.set(true);

        Thread.startVirtualThread(() -> {
            long startTime = System.currentTimeMillis();
            try {
                agentLoop.runStreaming(userInput, token -> {
                    // 流式 token 追加到最后一个 AssistantMsg
                    appendToStreamingMessage(token);
                });

                // 完成当前流式消息
                finishStreamingMessage();

                // 显示耗时
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                if (elapsed > 0) {
                    addMessage(new TimingMsg(elapsed));
                }
            } catch (Exception e) {
                addMessage(new SystemMsg("Error: " + e.getMessage(), Color.BRIGHT_RED));
            } finally {
                agentRunning.set(false);
                TuiState cs = getState();
                setState(new TuiState(cs.inputText, cs.messages, 0, false, ""));
            }
        });
    }

    // ==================== 消息管理 ====================

    /** 添加一条消息 */
    public void addMessage(UIMessage msg) {
        TuiState s = getState();
        List<UIMessage> newMsgs = new ArrayList<>(s.messages);
        newMsgs.add(msg);
        setState(new TuiState(s.inputText, Collections.unmodifiableList(newMsgs),
                0, s.thinking, s.thinkingText));
    }

    /** 追加 token 到当前流式助手消息 */
    private void appendToStreamingMessage(String token) {
        TuiState s = getState();
        List<UIMessage> msgs = new ArrayList<>(s.messages);

        // 查找最后一个 streaming AssistantMsg
        if (!msgs.isEmpty() && msgs.getLast() instanceof AssistantMsg am && am.streaming()) {
            msgs.set(msgs.size() - 1, am.appendText(token));
        } else {
            msgs.add(new AssistantMsg(token, true));
        }

        setState(new TuiState(s.inputText, Collections.unmodifiableList(msgs),
                0, s.thinking, s.thinkingText));
    }

    /** 完成当前流式消息（公开给 JinkReplSession 使用） */
    public void finishStreamingMessage() {
        TuiState s = getState();
        List<UIMessage> msgs = new ArrayList<>(s.messages);

        if (!msgs.isEmpty() && msgs.getLast() instanceof AssistantMsg am && am.streaming()) {
            msgs.set(msgs.size() - 1, am.finish());
            setState(new TuiState(s.inputText, Collections.unmodifiableList(msgs),
                    0, s.thinking, s.thinkingText));
        }
    }

    /** 更新最后一个工具调用消息的结果 */
    public void completeLastToolCall(String result) {
        TuiState s = getState();
        List<UIMessage> msgs = new ArrayList<>(s.messages);

        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof ToolCallMsg tcm && tcm.running()) {
                msgs.set(i, tcm.complete(result));
                break;
            }
        }

        setState(new TuiState(s.inputText, Collections.unmodifiableList(msgs),
                s.scrollOffset, s.thinking, s.thinkingText));
    }

    /** 设置权限确认回调 */
    public void requestPermission(Consumer<String> callback) {
        this.permissionCallback = callback;
    }

    /** 设置 thinking 状态 */
    public void setThinking(boolean thinking, String text) {
        TuiState s = getState();
        setState(new TuiState(s.inputText, s.messages, s.scrollOffset, thinking, text));
    }

    /** 设置首次用户输入回调 */
    public void setOnFirstUserInput(Consumer<String> callback) {
        this.onFirstUserInput = callback;
    }

    // ==================== 历史导航 ====================

    private void browseHistoryUp(TuiState s) {
        if (inputHistory.isEmpty()) return;
        if (historyIndex == -1) {
            savedInput = s.inputText;
            historyIndex = inputHistory.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        }
        setState(new TuiState(inputHistory.get(historyIndex), s.messages, s.scrollOffset, false, ""));
    }

    private void browseHistoryDown(TuiState s) {
        if (historyIndex < 0) return;
        historyIndex++;
        if (historyIndex >= inputHistory.size()) {
            historyIndex = -1;
            setState(new TuiState(savedInput, s.messages, s.scrollOffset, false, ""));
            savedInput = "";
            return;
        }
        setState(new TuiState(inputHistory.get(historyIndex), s.messages, s.scrollOffset, false, ""));
    }

    private void abandonHistoryPreview() {
        if (historyIndex >= 0) {
            historyIndex = -1;
            savedInput = "";
        }
    }

    // ==================== 滚动 ====================

    private void scroll(TuiState s, int delta) {
        int totalMessages = s.messages.size() + 1; // +1 for initial system msg
        int maxOffset = Math.max(0, totalMessages - 1);
        int newOffset = Math.max(0, Math.min(s.scrollOffset + delta, maxOffset));
        setState(new TuiState(s.inputText, s.messages, newOffset, s.thinking, s.thinkingText));
    }

    // ==================== 工具方法 ====================

    private boolean isPrintableInput(String input, Key key) {
        if (key.ctrl() || key.meta()) return false;
        if (key.upArrow() || key.downArrow() || key.leftArrow() || key.rightArrow()) return false;
        if (key.pageUp() || key.pageDown() || key.home() || key.end()) return false;
        if (key.escape() || key.tab() || key.delete()) return false;
        if (key.scrollUp() || key.scrollDown()) return false;
        if (input.length() == 1 && input.charAt(0) < 0x20) return false;
        return true;
    }

    /** 获取 Agent 运行状态 */
    public boolean isAgentRunning() {
        return agentRunning.get();
    }
}
