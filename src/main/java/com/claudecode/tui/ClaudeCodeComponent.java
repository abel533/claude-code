package com.claudecode.tui;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandRegistry;
import com.claudecode.console.BannerPrinter;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.TokenTracker;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tui.UIMessage.*;
import io.mybatis.jink.component.*;
import io.mybatis.jink.input.Key;
import io.mybatis.jink.style.*;
import io.mybatis.jink.util.StringWidth;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    private final ToolRegistry toolRegistry;
    private final String provider;
    private final String model;
    private final String baseUrl;
    private final int toolCount;
    private final int cmdCount;
    private final TokenTracker tokenTracker;
    private final Runnable onExit;

    // --- 内部状态 ---
    private final Object stateLock = new Object(); // 保护 getState/setState 的读-改-写操作
    private final List<String> inputHistory = new ArrayList<>();
    private int historyIndex = -1;
    private String savedInput = "";
    private final AtomicBoolean agentRunning = new AtomicBoolean(false);

    /** 思考动画帧 */
    private static final String[] SPINNER_FRAMES = {"◐", "◓", "◑", "◒"};
    private volatile int spinnerFrame = 0;
    private volatile Thread spinnerThread;

    /** 权限确认回调（由权限请求设置，用户输入后调用） */
    private volatile Consumer<String> permissionCallback;

    /** AskUser 交互模式状态 */
    private volatile List<String> askOptions;       // 可选项列表
    private volatile int askSelectedIndex = 0;      // 当前选中索引
    private volatile boolean askInputMode = false;  // 是否在自由输入模式（选择"其他"后）
    private volatile String askQuestion;            // 当前问题文本

    /** 首次用户输入回调（用于 conversation summary） */
    private Consumer<String> onFirstUserInput;

    public ClaudeCodeComponent(AgentLoop agentLoop,
                               CommandRegistry commandRegistry,
                               ToolRegistry toolRegistry,
                               String provider, String model, String baseUrl,
                               int toolCount, int cmdCount,
                               TokenTracker tokenTracker,
                               Runnable onExit) {
        super(TuiState.empty());
        this.agentLoop = agentLoop;
        this.commandRegistry = commandRegistry;
        this.toolRegistry = toolRegistry;
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
        if (askOptions != null && !askOptions.isEmpty() && permissionCallback != null) {
            // AskUser 模式：选项数 + 提示行
            inputLineCount = askOptions.size() + 1;
        } else if (!s.inputText.isEmpty()) {
            String[] inputLines = s.inputText.split("\n", -1);
            inputLineCount = inputLines.length;
            lastLine = inputLines[inputLines.length - 1];
        }

        // 光标定位
        if (askOptions != null && !askOptions.isEmpty() && permissionCallback != null) {
            // AskUser 模式：隐藏光标（选项列表模式不需要）
            if (askInputMode) {
                int askCursorRow = h - 2 - (askOptions.size() - askSelectedIndex);
                setCursorPosition(askCursorRow, 7 + StringWidth.width(s.inputText));
            } else {
                setCursorPosition(h - 2 - (askOptions.size() - askSelectedIndex), 6);
            }
        } else {
            int cursorRow = h - 3;
            int cursorCol = 1 + PROMPT_WIDTH + StringWidth.width(lastLine);
            setCursorPosition(cursorRow, cursorCol);
        }

        int headerHeight = 8; // 6 content rows + 2 border lines
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

    /** 标题框 — 保留原始 ASCII Logo 样式（双列布局） */
    private Renderable headerBox(int w) {
        // ASCII 冒烟咖啡杯
        String[] logo = {
                "       ) ) )       ",
                "    ╭────────╮     ",
                "    │ ~~~~~~ │─╮   ",
                "    │ CLAUDE │ │   ",
                "    │  CODE  │─╯   ",
                "    ╰─┬────┬─╯     "
        };
        int logoWidth = 19;
        int sepWidth = 3; // " │ "
        int rightWidth = Math.max(0, w - 4 - logoWidth - sepWidth - 2);

        // 构建右侧信息行（带颜色高亮）
        @SuppressWarnings("unchecked")
        Renderable[] rightTexts = {
                Text.of(""),
                Text.of("Welcome!").bold(),
                Text.of(
                        Text.of("API: ").dimmed(),
                        Text.of(baseUrl).color(Color.BRIGHT_CYAN)
                ),
                Text.of(
                        Text.of("Protocol: ").dimmed(),
                        Text.of(provider.toUpperCase()).color(Color.BRIGHT_GREEN),
                        Text.of("  Model: ").dimmed(),
                        Text.of(model).color(Color.BRIGHT_GREEN)
                ),
                Text.of(
                        Text.of("Work Dir: ").dimmed(),
                        Text.of(System.getProperty("user.dir", ".")).color(Color.BRIGHT_YELLOW)
                ),
                Text.of(
                        Text.of("Tools: ").dimmed(),
                        Text.of(String.valueOf(toolCount)).color(Color.BRIGHT_CYAN),
                        Text.of(" │ Commands: ").dimmed(),
                        Text.of(String.valueOf(cmdCount)).color(Color.BRIGHT_CYAN)
                )
        };

        List<Renderable> rows = new ArrayList<>();
        int maxRows = Math.max(logo.length, rightTexts.length);
        for (int i = 0; i < maxRows; i++) {
            String left = i < logo.length ? logo[i] : "";
            if (left.length() < logoWidth) left = left + " ".repeat(logoWidth - left.length());
            Renderable rightPart = i < rightTexts.length ? rightTexts[i] : Text.of("");

            rows.add(Text.of(
                    Text.of(left).color(Color.BRIGHT_CYAN),
                    Text.of(" │ ").dimmed(),
                    rightPart
            ));
        }

        return Box.of(rows.toArray(new Renderable[0]))
                .flexDirection(FlexDirection.COLUMN)
                .borderStyle(BorderStyle.ROUND)
                .borderColor(Color.BRIGHT_MAGENTA)
                .paddingX(1)
                .width(w);
    }

    /** 消息列表（带虚拟滚动） */
    private Renderable messagesArea(TuiState s, int maxLines) {
        List<Renderable> allItems = new ArrayList<>();

        // 初始提示消息
        allItems.add(Text.of(
                Text.of("● ").color(Color.BRIGHT_BLUE),
                Text.of("Ready. Describe a task or type ").color(Color.WHITE),
                Text.of("/help").color(Color.BRIGHT_CYAN).bold(),
                Text.of(" for available commands.").color(Color.WHITE)
        ));

        // 渲染所有消息（带空行分隔）
        for (int i = 0; i < s.messages.size(); i++) {
            UIMessage msg = s.messages.get(i);
            // 在用户消息前添加空行分隔（除了第一条）
            if (msg instanceof UserMsg && i > 0) {
                allItems.add(Text.of(" "));
            }
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
                .paddingX(1)
                .height(Math.max(1, maxLines))
                .overflow(io.mybatis.jink.style.Overflow.HIDDEN);
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
                String text = m.text();
                if (text == null || text.isEmpty()) {
                    if (m.streaming()) {
                        lines.add(Text.of(
                                Text.of("● ").color(Color.BRIGHT_CYAN),
                                Text.of("▌").color(Color.BRIGHT_CYAN)
                        ));
                    }
                    yield lines;
                }

                // 始终使用 Markdown 渲染（流式和完成都渲染）
                List<Renderable> mdLines = MarkdownToText.convert(text);
                // 流式时在最后一行追加光标
                if (m.streaming() && !mdLines.isEmpty()) {
                    Renderable lastLine = mdLines.getLast();
                    mdLines.set(mdLines.size() - 1, Text.of(lastLine, Text.of("▌").color(Color.BRIGHT_CYAN)));
                }
                for (int i = 0; i < mdLines.size(); i++) {
                    if (i == 0) {
                        lines.add(Text.of(Text.of("● ").color(Color.BRIGHT_CYAN), mdLines.get(i)));
                    } else {
                        lines.add(Text.of(Text.of("  "), mdLines.get(i)));
                    }
                }
                yield lines;
            }

            case ToolCallMsg m -> {
                List<Renderable> lines = new ArrayList<>();
                String argSummary = extractToolSummary(m.toolName(), m.args());
                if (m.running()) {
                    lines.add(Text.of(
                            Text.of("  ● ").color(Color.BRIGHT_BLUE),
                            Text.of(m.toolName()).color(Color.BRIGHT_CYAN).bold(),
                            argSummary != null ? Text.of("(" + argSummary + ")").dimmed() : Text.of(""),
                            Text.of("  running...").dimmed()
                    ));
                } else {
                    lines.add(Text.of(
                            Text.of("  ● ").color(Color.BRIGHT_GREEN),
                            Text.of(m.toolName()).color(Color.BRIGHT_CYAN),
                            argSummary != null ? Text.of("(" + argSummary + ")").dimmed() : Text.of(""),
                            Text.of("  done").dimmed()
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
        // AskUser 交互模式 — 显示选项列表
        if (permissionCallback != null && askOptions != null && !askOptions.isEmpty()) {
            return renderAskUserArea(s, w);
        }

        Text prompt = Text.of("❯ ").color(Color.BRIGHT_GREEN).bold();
        Text content;

        if (permissionCallback != null) {
            // 权限确认模式
            content = Text.of(s.inputText.isEmpty()
                    ? "Y/a/n/d >"
                    : s.inputText).color(Color.BRIGHT_YELLOW);
        } else if (agentRunning.get()) {
            // AI 正在运行 — 使用旋转动画
            String spinner = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length];
            String label = s.thinking ? " Thinking..." : " Processing...";
            content = Text.of(spinner + label).color(Color.BRIGHT_CYAN).dimmed();
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

    /** 渲染 AskUser 选项列表 */
    private Renderable renderAskUserArea(TuiState s, int w) {
        List<Renderable> lines = new ArrayList<>();

        for (int i = 0; i < askOptions.size(); i++) {
            boolean selected = (i == askSelectedIndex);
            String option = askOptions.get(i);

            if (selected && askInputMode) {
                // 自由输入模式
                lines.add(Text.of(
                        Text.of("  ❯ " + (i + 1) + ". ").color(Color.BRIGHT_CYAN),
                        Text.of(s.inputText + "█").color(Color.BRIGHT_CYAN)
                ));
            } else {
                String prefix = selected ? "  ❯ " : "    ";
                lines.add(Text.of(prefix + (i + 1) + ". " + option)
                        .color(selected ? Color.BRIGHT_CYAN : null));
            }
        }

        // 提示行
        String hint = askInputMode
                ? "Type your answer · Enter confirm · Esc back"
                : "↑↓ select · Enter confirm · 1-9 quick select · Esc cancel";
        lines.add(Text.of("  " + hint).dimmed());

        return Box.of(lines.toArray(new Renderable[0]))
                .flexDirection(FlexDirection.COLUMN)
                .paddingX(1);
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
                Text.of("↑↓ history  Esc interrupt  Ctrl+D exit").dimmed(),
                Spacer.create(),
                Text.of(tokenInfo).color(Color.BRIGHT_GREEN)
        ).paddingX(1).height(1);
    }

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    // ==================== 输入处理 ====================

    @Override
    public void onInput(String input, Key key) {
        synchronized (stateLock) {
            TuiState s = getState();

            // Ctrl+D: 退出
            if (key.ctrl() && "d".equals(input)) {
                if (onExit != null) onExit.run();
                return;
            }

            // Ctrl+C: 取消当前输入或中断 Agent
            if (key.ctrl() && "c".equals(input)) {
                if (agentRunning.get()) {
                    agentLoop.cancel();
                    addMessageInternal(new SystemMsg("^C (interrupt)", Color.BRIGHT_YELLOW), s);
                } else {
                    setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
                }
                return;
            }

            // 权限确认模式 / AskUser 模式
            if (permissionCallback != null) {
                if (askOptions != null && !askOptions.isEmpty()) {
                    handleAskUserInput(input, key, s);
                } else {
                    handlePermissionInput(input, key, s);
                }
                return;
            }

            // AI 运行中时允许滚动和 Escape 中断
            if (agentRunning.get()) {
                if (key.escape()) {
                    // Esc: 中断 Agent 运行
                    agentLoop.cancel();
                    addMessageInternal(new SystemMsg("⚡ Interrupted", Color.BRIGHT_YELLOW), s);
                } else {
                    handleScrollInput(key, s);
                }
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
    }

    @Override
    public void onPaste(String text) {
        synchronized (stateLock) {
            if (agentRunning.get() || text == null || text.isEmpty()) return;
            TuiState s = getState();
            abandonHistoryPreview();
            setState(new TuiState(s.inputText + text, s.messages, s.scrollOffset, false, ""));
        }
    }

    /** 处理权限确认输入 */
    private void handlePermissionInput(String input, Key key, TuiState s) {
        if (key.return_()) {
            String answer = s.inputText.isEmpty() ? "y" : s.inputText;
            Consumer<String> cb = permissionCallback;
            permissionCallback = null;
            setState(new TuiState("", s.messages, 0, false, ""));
            if (cb != null) {
                Thread.startVirtualThread(() -> cb.accept(answer));
            }
        } else if (key.backspace() && !s.inputText.isEmpty()) {
            setState(new TuiState(s.inputText.substring(0, s.inputText.length() - 1),
                    s.messages, s.scrollOffset, false, ""));
        } else if (!input.isEmpty() && isPrintableInput(input, key)) {
            setState(new TuiState(s.inputText + input, s.messages, s.scrollOffset, false, ""));
        }
    }

    /** 处理 AskUser 交互输入（带选项列表的选择模式） */
    private void handleAskUserInput(String input, Key key, TuiState s) {
        if (askInputMode) {
            // 自由输入模式（选择了"其他"之后）
            if (key.return_()) {
                if (!s.inputText.isEmpty()) {
                    confirmAskUser(s.inputText);
                }
            } else if (key.escape()) {
                // 返回选择模式
                askInputMode = false;
                setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
            } else if (key.backspace() && !s.inputText.isEmpty()) {
                setState(new TuiState(s.inputText.substring(0, s.inputText.length() - 1),
                        s.messages, s.scrollOffset, false, ""));
            } else if (!input.isEmpty() && isPrintableInput(input, key)) {
                setState(new TuiState(s.inputText + input, s.messages, s.scrollOffset, false, ""));
            }
        } else {
            // 列表选择模式
            if (key.upArrow()) {
                askSelectedIndex = askSelectedIndex == 0 ? askOptions.size() - 1 : askSelectedIndex - 1;
                setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
            } else if (key.downArrow()) {
                askSelectedIndex = (askSelectedIndex + 1) % askOptions.size();
                setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
            } else if (key.return_()) {
                String selected = askOptions.get(askSelectedIndex);
                // 最后一个选项如果包含"其他"或"Other"，切换到输入模式
                if (askSelectedIndex == askOptions.size() - 1 &&
                        (selected.contains("其他") || selected.toLowerCase().contains("other"))) {
                    askInputMode = true;
                    setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
                } else {
                    confirmAskUser(selected);
                }
            } else if (key.escape()) {
                confirmAskUser("(cancelled)");
            } else if (input.length() == 1 && Character.isDigit(input.charAt(0))) {
                // 数字键快速选择
                int idx = input.charAt(0) - '1';
                if (idx >= 0 && idx < askOptions.size()) {
                    askSelectedIndex = idx;
                    String selected = askOptions.get(idx);
                    if (idx == askOptions.size() - 1 &&
                            (selected.contains("其他") || selected.toLowerCase().contains("other"))) {
                        askInputMode = true;
                        setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
                    } else {
                        confirmAskUser(selected);
                    }
                }
            }
        }
    }

    /** 确认 AskUser 选择并回调（调用方已持有 stateLock） */
    private void confirmAskUser(String answer) {
        Consumer<String> cb = permissionCallback;
        permissionCallback = null;
        askOptions = null;
        askQuestion = null;
        askInputMode = false;
        askSelectedIndex = 0;
        TuiState s = getState();
        setState(new TuiState("", s.messages, 0, false, ""));
        // 回调在锁外执行（cb.accept 可能阻塞或触发其他状态变更）
        if (cb != null) {
            Thread.startVirtualThread(() -> cb.accept(answer));
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
            // 捕获命令输出到 ByteArrayOutputStream
            var baos = new ByteArrayOutputStream();
            var capturedOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            CommandContext cmdCtx = new CommandContext(agentLoop, toolRegistry, commandRegistry,
                    capturedOut, () -> {
                if (onExit != null) onExit.run();
            });
            Optional<String> result = commandRegistry.dispatch(text, cmdCtx);
            // 合并 dispatch 返回值和 capturedOut 的内容
            StringBuilder output = new StringBuilder();
            result.ifPresent(output::append);
            String captured = baos.toString(StandardCharsets.UTF_8);
            if (!captured.isBlank()) {
                if (!output.isEmpty()) output.append("\n");
                output.append(captured);
            }
            if (!output.isEmpty()) {
                addMessage(new CommandOutputMsg(output.toString()));
            }
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
        startSpinner();

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
                stopSpinner();
                agentRunning.set(false);
                synchronized (stateLock) {
                    TuiState cs = getState();
                    setState(new TuiState(cs.inputText, cs.messages, 0, false, ""));
                }
            }
        });
    }

    /** 启动思考动画 */
    private void startSpinner() {
        spinnerFrame = 0;
        Thread t = Thread.startVirtualThread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(120);
                    spinnerFrame++;
                    // 触发重绘：读取当前状态并重新设置（内容不变，但 spinner 帧已更新）
                    synchronized (stateLock) {
                        TuiState s = getState();
                        setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
                    }
                }
            } catch (InterruptedException ignored) {}
        });
        spinnerThread = t;
    }

    /** 停止思考动画 */
    private void stopSpinner() {
        Thread t = spinnerThread;
        if (t != null) {
            t.interrupt();
            spinnerThread = null;
        }
    }

    // ==================== 消息管理 ====================

    /** 添加一条消息 */
    public void addMessage(UIMessage msg) {
        synchronized (stateLock) {
            addMessageInternal(msg, getState());
        }
    }

    /** 内部添加消息（调用方需持有 stateLock） */
    private void addMessageInternal(UIMessage msg, TuiState s) {
        List<UIMessage> newMsgs = new ArrayList<>(s.messages);
        newMsgs.add(msg);
        setState(new TuiState(s.inputText, Collections.unmodifiableList(newMsgs),
                0, s.thinking, s.thinkingText));
    }

    /** 追加 token 到当前流式助手消息 */
    private void appendToStreamingMessage(String token) {
        synchronized (stateLock) {
            TuiState s = getState();
            List<UIMessage> msgs = new ArrayList<>(s.messages);

            // 查找最后一个 streaming AssistantMsg
            if (!msgs.isEmpty() && msgs.getLast() instanceof AssistantMsg am && am.streaming()) {
                msgs.set(msgs.size() - 1, am.appendText(token));
            } else {
                msgs.add(new AssistantMsg(token, true));
            }

            // 保留用户的滚动偏移（如果用户手动滚动过则不自动归零）
            setState(new TuiState(s.inputText, Collections.unmodifiableList(msgs),
                    s.scrollOffset, s.thinking, s.thinkingText));
        }
    }

    /** 完成当前流式消息（公开给 JinkReplSession 使用） */
    public void finishStreamingMessage() {
        synchronized (stateLock) {
            TuiState s = getState();
            List<UIMessage> msgs = new ArrayList<>(s.messages);

            if (!msgs.isEmpty() && msgs.getLast() instanceof AssistantMsg am && am.streaming()) {
                msgs.set(msgs.size() - 1, am.finish());
                setState(new TuiState(s.inputText, Collections.unmodifiableList(msgs),
                        s.scrollOffset, s.thinking, s.thinkingText));
            }
        }
    }

    /** 更新最后一个工具调用消息的结果 */
    public void completeLastToolCall(String result) {
        synchronized (stateLock) {
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
    }

    /** 设置权限确认回调 */
    public void requestPermission(Consumer<String> callback) {
        this.askOptions = null;
        this.askInputMode = false;
        this.askQuestion = null;
        this.permissionCallback = callback;
    }

    /** 设置 AskUser 交互模式（带可选列表） */
    public void requestAskUser(String question, List<String> options, Consumer<String> callback) {
        this.askQuestion = question;
        this.askOptions = options;
        this.askSelectedIndex = 0;
        this.askInputMode = false;
        this.permissionCallback = callback;
        // 触发重绘
        synchronized (stateLock) {
            TuiState s = getState();
            setState(new TuiState("", s.messages, s.scrollOffset, s.thinking, s.thinkingText));
        }
    }

    /** 设置 thinking 状态 */
    public void setThinking(boolean thinking, String text) {
        synchronized (stateLock) {
            TuiState s = getState();
            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, thinking, text));
        }
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

    /** 从 JSON 工具参数中提取人类可读的摘要 */
    private static String extractToolSummary(String toolName, String args) {
        if (args == null || args.isBlank()) return null;
        try {
            String[] keys = {"command", "file_path", "pattern", "query", "url"};
            for (String key : keys) {
                String search = "\"" + key + "\"";
                int start = args.indexOf(search);
                if (start < 0) continue;
                int colonPos = args.indexOf("\"", start + search.length());
                if (colonPos < 0) continue;
                int valStart = colonPos + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valEnd < 0 || valEnd <= valStart) continue;
                String val = args.substring(valStart, Math.min(valEnd, valStart + 60));
                return switch (key) {
                    case "command" -> "$ " + val;
                    case "pattern" -> "pattern: " + val;
                    case "query" -> "\"" + val + "\"";
                    default -> val;
                };
            }
        } catch (Exception ignored) {}
        return null;
    }
}
