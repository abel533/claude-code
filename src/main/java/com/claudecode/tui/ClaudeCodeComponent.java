package com.claudecode.tui;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandRegistry;
import com.claudecode.console.BannerPrinter;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.TokenTracker;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.BashTool;
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
    private final TokenTracker tokenTracker;
    private final Runnable onExit;

    // --- 内部状态 ---
    private final Object stateLock = new Object(); // 保护 getState/setState 的读-改-写操作
    private final Object spinnerLock = new Object(); // 保护 spinner 线程的启停
    private final List<String> inputHistory = new ArrayList<>();
    private int historyIndex = -1;
    private String savedInput = "";
    private final AtomicBoolean agentRunning = new AtomicBoolean(false);

    /** 思考动画帧 */
    private static final String[] SPINNER_FRAMES = {"◐", "◓", "◑", "◒"};
    /** 终端标题动画帧（匹配官方 TITLE_ANIMATION_FRAMES） */
    private static final String[] TITLE_ANIMATION_FRAMES = {"⠂", "⠐"};
    private static final String TITLE_STATIC_PREFIX = "✳";
    private volatile int spinnerFrame = 0;
    private volatile Thread spinnerThread;
    /** 终端标题（从首条用户消息推断） */
    private volatile String sessionTitle = null;

    /** 权限确认回调（由权限请求设置，用户输入后调用） */
    private volatile Consumer<String> permissionCallback;

    /** AskUser 交互模式状态 */
    private volatile List<String> askOptions;       // 可选项列表
    private volatile int askSelectedIndex = 0;      // 当前选中索引
    private volatile boolean askInputMode = false;  // 是否在自由输入模式（选择"其他"后）
    private volatile String askQuestion;            // 当前问题文本

    /** 权限确认交互模式状态 */
    private volatile List<String> permissionOptions;    // 权限选项列表
    private volatile int permissionSelectedIndex = 0;   // 当前选中索引

    /** 最近一次渲染的总行数（用于滚动限制） */
    private volatile int lastRenderedItemCount = 0;
    private volatile int lastMaxVisibleLines = 20;

    /** Ctrl+C 双击退出：上次按下时间 */
    private volatile long lastCtrlCTime = 0;
    private static final long CTRL_C_EXIT_WINDOW_MS = 800; // 800ms内再按一次退出（匹配官方）

    /** Thinking 开始时间（用于显示耗时） */
    private volatile long thinkingStartTime = 0;

    /** Tab 自动补全状态 */
    private volatile List<String> commandSuggestions = List.of(); // 当前匹配的命令名列表
    private volatile int tabCompletionIndex = -1;                  // 当前 Tab 循环索引，-1 表示未开始

    /** Ctrl+R 反向历史搜索状态 */
    private volatile boolean historySearchMode = false;    // 是否在搜索模式
    private volatile String historySearchQuery = "";       // 搜索关键词
    private volatile String historySearchResult = "";      // 当前匹配结果
    private volatile int historySearchIndex = -1;          // 匹配到的历史记录索引

    /** 首次用户输入回调（用于 conversation summary） */
    private Consumer<String> onFirstUserInput;

    public ClaudeCodeComponent(AgentLoop agentLoop,
                               CommandRegistry commandRegistry,
                               ToolRegistry toolRegistry,
                               String provider, String model, String baseUrl,
                               int toolCount,
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
        this.tokenTracker = tokenTracker;
        this.onExit = onExit;
        // 设置初始终端标题（匹配官方 process.title = 'claude'）
        setTerminalTitle(TITLE_STATIC_PREFIX + " Claude Code");
    }

    // ==================== 渲染 ====================

    @Override
    public Renderable render() {
        TuiState s = getState();
        int w = getColumns();
        int h = getRows();

        // 快照 volatile 字段（避免 render 过程中被其他线程修改）
        final List<String> snapAskOptions;
        final int snapAskSelected;
        final boolean snapAskInputMode;
        final boolean snapHasCallback;
        final List<String> snapPermOptions;
        final int snapPermSelected;
        synchronized (stateLock) {
            snapAskOptions = askOptions != null ? List.copyOf(askOptions) : null;
            snapAskSelected = askSelectedIndex;
            snapAskInputMode = askInputMode;
            snapHasCallback = permissionCallback != null;
            snapPermOptions = permissionOptions != null ? List.copyOf(permissionOptions) : null;
            snapPermSelected = permissionSelectedIndex;
        }

        // 计算输入区行数
        int inputLineCount = 1;
        String lastLine = s.inputText;
        if (snapAskOptions != null && !snapAskOptions.isEmpty() && snapHasCallback) {
            // AskUser 模式：选项数 + 提示行
            inputLineCount = snapAskOptions.size() + 1;
        } else if (snapPermOptions != null && !snapPermOptions.isEmpty() && snapHasCallback) {
            // 权限选择模式：标题行 + 选项数 + 提示行
            inputLineCount = snapPermOptions.size() + 2;
        } else if (!s.inputText.isEmpty()) {
            String[] inputLines = s.inputText.split("\n", -1);
            inputLineCount = inputLines.length;
            lastLine = inputLines[inputLines.length - 1];
        }

        // 光标定位（clamp 到 >= 0 防止小终端越界）
        if (snapAskOptions != null && !snapAskOptions.isEmpty() && snapHasCallback) {
            if (snapAskInputMode) {
                int askCursorRow = h - 2 - (snapAskOptions.size() - snapAskSelected);
                setCursorPosition(Math.max(0, askCursorRow), 7 + StringWidth.width(s.inputText));
            } else {
                int askCursorRow = h - 2 - (snapAskOptions.size() - snapAskSelected);
                setCursorPosition(Math.max(0, askCursorRow), 6);
            }
        } else if (snapPermOptions != null && !snapPermOptions.isEmpty() && snapHasCallback) {
            // 权限选择模式：光标在选中选项的 ❯ 位置
            int permCursorRow = h - 2 - (snapPermOptions.size() - snapPermSelected);
            setCursorPosition(Math.max(0, permCursorRow), 3);
        } else if (historySearchMode) {
            // 搜索模式：光标在搜索词 █ 的位置
            // "(reverse-i-search)`" = 20 chars, then query, then "█"
            int cursorRow = Math.max(0, h - 3);
            int cursorCol = 1 + 20 + StringWidth.width(historySearchQuery);
            setCursorPosition(cursorRow, cursorCol);
        } else {
            // 正常模式：光标隐藏在块光标 █ 的位置
            int cursorRow = Math.max(0, h - 3);
            int cursorCol = 1 + PROMPT_WIDTH + StringWidth.width(lastLine);
            setCursorPosition(cursorRow, cursorCol);
        }

        int bottomHeight = 4 + inputLineCount;
        int messagePaddingTop = 1;
        // 标题框现在是消息区的一部分，会随消息一起滚动（匹配官方 LogoHeader 行为）
        int maxMessageLines = Math.max(1, h - bottomHeight - messagePaddingTop);

        List<Renderable> layout = new ArrayList<>();
        layout.add(messagesArea(s, maxMessageLines));
        layout.add(Spacer.create());
        layout.add(statusBar(w, h));
        layout.add(separator(w));
        layout.add(inputArea(s, w));
        layout.add(separator(w));
        layout.add(shortcutBar(w));

        return Box.of(layout.toArray(new Renderable[0]))
                .flexDirection(FlexDirection.COLUMN).width(w).height(h);
    }

    /** 标题框行列表 — ASCII Logo + 信息（作为消息区首部随消息滚动） */
    private List<Renderable> headerLines() {
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
                        Text.of(" │ Shell: ").dimmed(),
                        Text.of(BashTool.getDetectedShellName()).color(Color.BRIGHT_CYAN)
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
        return rows;
    }

    /** 消息列表（带虚拟滚动） */
    private Renderable messagesArea(TuiState s, int maxLines) {
        List<Renderable> allItems = new ArrayList<>();

        // 标题框内容（作为消息区首部，随消息一起滚动 — 匹配官方 LogoHeader 行为）
        allItems.addAll(headerLines());
        allItems.add(Text.of(" ")); // 空行分隔

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

        // Thinking / Processing 状态动画（显示在消息区底部）
        if (agentRunning.get()) {
            String spinner = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length];
            if (s.thinking) {
                // 计算 thinking 耗时
                long elapsed = thinkingStartTime > 0
                        ? (System.currentTimeMillis() - thinkingStartTime) / 1000
                        : 0;
                String durationText = elapsed >= 2
                        ? String.format("Thinking (%ds)...", elapsed)
                        : "Thinking...";
                allItems.add(Text.of(
                        Text.of(spinner + " ").color(Color.BRIGHT_YELLOW),
                        Text.of(durationText).color(Color.BRIGHT_YELLOW).italic()
                ));
            } else {
                // Agent 运行中但未进入 thinking（执行工具、流式输出等）
                // 显示输出 token 计数
                String tokenInfo = "";
                if (tokenTracker != null && tokenTracker.getOutputTokens() > 0) {
                    tokenInfo = " (" + tokenTracker.getOutputTokens() + " tokens)";
                }
                allItems.add(Text.of(
                        Text.of(spinner + " ").color(Color.BRIGHT_CYAN),
                        Text.of("Processing..." + tokenInfo).color(Color.BRIGHT_CYAN).italic()
                ));
            }
        }

        // 记录总行数和可见行数（供 scroll() 使用）
        lastRenderedItemCount = allItems.size();
        lastMaxVisibleLines = maxLines;

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
                    // 流式输出预览（最后几行）
                    if (m.outputLines() != null && !m.outputLines().isEmpty()) {
                        for (String line : m.outputLines()) {
                            lines.add(Text.of(
                                    Text.of("  ⎿  ").dimmed(),
                                    Text.of(line.length() > 120 ? line.substring(0, 117) + "..." : line)
                                            .color(Color.BRIGHT_BLACK)
                            ));
                        }
                    }
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
                // 蓝色分隔线
                lines.add(Text.of("  ─────────────────────────────────────────").color(Color.BRIGHT_BLUE));
                lines.add(Text.of(
                        Text.of("  Tool use").color(Color.BRIGHT_RED).bold()
                ));
                // 工具名 + 参数
                String argSummary = extractToolSummary(m.toolName(), m.args());
                lines.add(Text.of(
                        Text.of("    "),
                        Text.of(m.toolName()).color(Color.WHITE).bold(),
                        argSummary != null ? Text.of("(" + argSummary + ")").dimmed() : Text.of("")
                ));
                // 动作描述
                lines.add(Text.of(
                        Text.of("    "),
                        Text.of(m.action()).dimmed()
                ));
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

        // 权限确认模式 — 也使用选项列表
        if (permissionCallback != null && permissionOptions != null && !permissionOptions.isEmpty()) {
            return renderPermissionSelectArea(s, w);
        }

        // Ctrl+R 历史搜索模式
        if (historySearchMode) {
            String query = historySearchQuery;
            String result = historySearchResult;
            Text searchPrompt = Text.of(
                    Text.of("(reverse-i-search)`").color(Color.BRIGHT_CYAN),
                    Text.of(query).color(Color.BRIGHT_YELLOW),
                    Text.of("█").color(Color.BRIGHT_WHITE),
                    Text.of("': ").color(Color.BRIGHT_CYAN),
                    Text.of(result.isEmpty() ? "" : result).color(Color.WHITE)
            );
            return Box.of(searchPrompt).paddingX(1);
        }

        Text prompt = Text.of("❯ ").color(Color.BRIGHT_GREEN).bold();
        Text content;

        if (agentRunning.get()) {
            // AI 运行中 — 输入区只显示提示符 + 块光标
            content = Text.of("█").color(Color.BRIGHT_WHITE);
        } else if (s.inputText.isEmpty()) {
            // 空输入 — 块光标 + 占位提示
            content = Text.of(
                    Text.of("█").color(Color.BRIGHT_WHITE),
                    Text.of(" Type a message, / for commands").dimmed()
            );
        } else {
            // 有文字 — 文字 + 块光标 + ghost text（命令补全提示）
            String indent = " ".repeat(PROMPT_WIDTH);
            String displayText = s.inputText.replace("\n", "\n" + indent);
            String ghost = getGhostText(s.inputText);
            if (!ghost.isEmpty()) {
                content = Text.of(
                        Text.of(displayText).color(Color.WHITE),
                        Text.of(ghost).dimmed(),
                        Text.of("█").color(Color.BRIGHT_WHITE)
                );
            } else {
                content = Text.of(
                        Text.of(displayText).color(Color.WHITE),
                        Text.of("█").color(Color.BRIGHT_WHITE)
                );
            }
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

    /** 渲染权限确认选项列表 */
    private Renderable renderPermissionSelectArea(TuiState s, int w) {
        List<Renderable> lines = new ArrayList<>();

        // "Do you want to proceed?" 提示
        lines.add(Text.of("Do you want to proceed?").bold());

        for (int i = 0; i < permissionOptions.size(); i++) {
            boolean selected = (i == permissionSelectedIndex);
            String prefix = selected ? "❯ " : "  ";
            lines.add(Text.of(
                    Text.of(prefix).color(selected ? Color.BRIGHT_CYAN : null),
                    Text.of((i + 1) + ". ").color(selected ? Color.BRIGHT_CYAN : null),
                    Text.of(permissionOptions.get(i)).color(selected ? Color.BRIGHT_CYAN : null)
            ));
        }

        // 提示行
        lines.add(Text.of("Esc to cancel · Tab to amend").dimmed());

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

        // 根据当前模式显示不同的快捷键提示
        boolean ctrlCPending = (System.currentTimeMillis() - lastCtrlCTime) < CTRL_C_EXIT_WINDOW_MS;
        Renderable leftText;
        if (ctrlCPending) {
            leftText = Text.of("Press Ctrl-C again to exit").color(Color.BRIGHT_YELLOW);
        } else if (historySearchMode) {
            leftText = Text.of("Ctrl+R next · Enter select · Esc cancel").dimmed();
        } else if (agentRunning.get()) {
            leftText = Text.of("esc to interrupt").dimmed();
        } else {
            // 检查是否在输入斜杠命令 — 显示匹配的命令列表
            List<String> suggestions = commandSuggestions;
            if (!suggestions.isEmpty()) {
                // 在快捷键栏显示匹配命令（最多显示 5 个）
                int maxShow = Math.min(suggestions.size(), 5);
                List<Renderable> parts = new ArrayList<>();
                parts.add(Text.of("Tab ").color(Color.BRIGHT_CYAN));
                for (int i = 0; i < maxShow; i++) {
                    if (i > 0) parts.add(Text.of("  ").dimmed());
                    String cmd = "/" + suggestions.get(i);
                    if (i == tabCompletionIndex) {
                        parts.add(Text.of(cmd).color(Color.BRIGHT_CYAN).bold());
                    } else {
                        parts.add(Text.of(cmd).dimmed());
                    }
                }
                if (suggestions.size() > maxShow) {
                    parts.add(Text.of("  +" + (suggestions.size() - maxShow) + " more").dimmed());
                }
                leftText = Text.of(parts.toArray(new Renderable[0]));
            } else {
                leftText = Text.of("↑↓ history  Esc interrupt").dimmed();
            }
        }

        return Box.of(
                leftText,
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

            // Ctrl+L: 强制重绘
            if (key.ctrl() && "l".equals(input)) {
                setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
                return;
            }

            // Ctrl+C: 中断 Agent 或双击退出
            if (key.ctrl() && "c".equals(input)) {
                if (agentRunning.get()) {
                    // Agent 运行中 → 仅取消任务，不启动退出窗口
                    // 官方行为：中断和退出是独立流程，中断不影响 double-press-to-exit
                    agentLoop.cancel();
                    addMessageInternal(new SystemMsg("^C (interrupt)", Color.BRIGHT_YELLOW), s);
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastCtrlCTime < CTRL_C_EXIT_WINDOW_MS) {
                        // 第二次 Ctrl+C → 退出
                        if (onExit != null) onExit.run();
                    } else {
                        // 第一次 Ctrl+C → 清空输入 + 提示再按一次退出
                        lastCtrlCTime = now;
                        setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
                        // 启动定时器，超时后清除提示
                        Thread.startVirtualThread(() -> {
                            try { Thread.sleep(CTRL_C_EXIT_WINDOW_MS); } catch (InterruptedException ignored) {}
                            // 超时后刷新显示（清除 "Press Ctrl-C again to exit" 提示）
                            synchronized (stateLock) {
                                if (System.currentTimeMillis() - lastCtrlCTime >= CTRL_C_EXIT_WINDOW_MS) {
                                    TuiState cur = getState();
                                    setState(new TuiState(cur.inputText, cur.messages, cur.scrollOffset, cur.thinking, cur.thinkingText));
                                }
                            }
                        });
                    }
                }
                return;
            }

            // 权限确认模式 / AskUser 模式 / 简单文本输入
            if (permissionCallback != null) {
                if (askOptions != null && !askOptions.isEmpty()) {
                    handleAskUserInput(input, key, s);
                } else if (permissionOptions != null && !permissionOptions.isEmpty()) {
                    handlePermissionInput(input, key, s);
                } else {
                    handleTextInput(input, key, s);
                }
                return;
            }

            // Ctrl+R: 进入历史搜索模式
            if (key.ctrl() && "r".equals(input) && !agentRunning.get()) {
                historySearchMode = true;
                historySearchQuery = "";
                historySearchResult = "";
                historySearchIndex = -1;
                setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
                return;
            }

            // 历史搜索模式的输入处理
            if (historySearchMode) {
                handleHistorySearchInput(input, key, s);
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
            } else if (key.tab() && !key.shift()) {
                // Tab: 命令自动补全
                handleTabCompletion(s);
            } else if (key.return_()) {
                // Enter: 发送
                if (!s.inputText.isEmpty()) {
                    submitInput(s.inputText, s);
                }
            } else if (key.backspace()) {
                if (!s.inputText.isEmpty()) {
                    abandonHistoryPreview();
                    String newText = s.inputText.substring(0, s.inputText.length() - 1);
                    updateCommandSuggestions(newText);
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
            } else if (key.ctrl() && key.home()) {
                // Ctrl+Home: 跳到顶部
                scrollToTop(s);
            } else if (key.ctrl() && key.end()) {
                // Ctrl+End: 跳到底部
                scrollToBottom(s);
            } else if (key.pageUp()) {
                scroll(s, 10);
            } else if (key.pageDown()) {
                scroll(s, -10);
            } else if (key.escape()) {
                // Esc: 清空输入
                updateCommandSuggestions("");
                setState(new TuiState("", s.messages, s.scrollOffset, false, ""));
            } else if (!input.isEmpty() && isPrintableInput(input, key)) {
                abandonHistoryPreview();
                String newText = s.inputText + input;
                updateCommandSuggestions(newText);
                setState(new TuiState(newText, s.messages, s.scrollOffset, false, ""));
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

    /** 处理简单文本输入（无选项的 AskUser） */
    private void handleTextInput(String input, Key key, TuiState s) {
        if (key.return_()) {
            String answer = s.inputText;
            Consumer<String> cb = permissionCallback;
            permissionCallback = null;
            setState(new TuiState("", s.messages, 0, false, ""));
            if (cb != null && !answer.isEmpty()) {
                Thread.startVirtualThread(() -> cb.accept(answer));
            }
        } else if (key.escape()) {
            Consumer<String> cb = permissionCallback;
            permissionCallback = null;
            setState(new TuiState("", s.messages, 0, false, ""));
            if (cb != null) {
                Thread.startVirtualThread(() -> cb.accept("(User cancelled)"));
            }
        } else if (key.backspace() && !s.inputText.isEmpty()) {
            setState(new TuiState(s.inputText.substring(0, s.inputText.length() - 1),
                    s.messages, s.scrollOffset, false, ""));
        } else if (!input.isEmpty() && isPrintableInput(input, key)) {
            setState(new TuiState(s.inputText + input, s.messages, s.scrollOffset, false, ""));
        }
    }

    /** 处理权限确认输入（交互选择模式） */
    private void handlePermissionInput(String input, Key key, TuiState s) {
        if (permissionOptions == null || permissionOptions.isEmpty()) return;

        if (key.return_()) {
            // 确认选择 → 将选中项映射为 y/a/n
            String answer = switch (permissionSelectedIndex) {
                case 0 -> "y";   // Yes
                case 1 -> "a";   // Yes, and don't ask again (always allow)
                case 2 -> "n";   // No
                default -> "y";
            };
            Consumer<String> cb = permissionCallback;
            permissionCallback = null;
            permissionOptions = null;
            setState(new TuiState("", s.messages, 0, false, ""));
            if (cb != null) {
                Thread.startVirtualThread(() -> cb.accept(answer));
            }
        } else if (key.escape()) {
            // Esc: 取消 → 等同于 No
            Consumer<String> cb = permissionCallback;
            permissionCallback = null;
            permissionOptions = null;
            setState(new TuiState("", s.messages, 0, false, ""));
            if (cb != null) {
                Thread.startVirtualThread(() -> cb.accept("n"));
            }
        } else if (key.upArrow()) {
            permissionSelectedIndex = Math.max(0, permissionSelectedIndex - 1);
            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
        } else if (key.downArrow()) {
            permissionSelectedIndex = Math.min(permissionOptions.size() - 1, permissionSelectedIndex + 1);
            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
        } else if ("1".equals(input) || "2".equals(input) || "3".equals(input)) {
            int idx = Integer.parseInt(input) - 1;
            if (idx >= 0 && idx < permissionOptions.size()) {
                permissionSelectedIndex = idx;
                setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
            }
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
        else if (key.ctrl() && key.home()) scrollToTop(s);
        else if (key.ctrl() && key.end()) scrollToBottom(s);
        else if (key.pageUp()) scroll(s, 10);
        else if (key.pageDown()) scroll(s, -10);
    }

    /** Ctrl+R 反向历史搜索输入处理 */
    private void handleHistorySearchInput(String input, Key key, TuiState s) {
        if (key.escape() || (key.ctrl() && "c".equals(input))) {
            // Esc/Ctrl+C: 退出搜索，恢复原输入
            historySearchMode = false;
            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, false, ""));
        } else if (key.return_()) {
            // Enter: 选定搜索结果，放入输入框
            String result = historySearchResult;
            historySearchMode = false;
            setState(new TuiState(result, s.messages, s.scrollOffset, false, ""));
        } else if (key.ctrl() && "r".equals(input)) {
            // 再次 Ctrl+R: 搜索下一个匹配（更旧的）
            searchHistoryBackward(historySearchQuery, historySearchIndex - 1);
            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, false, ""));
        } else if (key.backspace()) {
            // 退格: 缩短搜索词
            if (!historySearchQuery.isEmpty()) {
                historySearchQuery = historySearchQuery.substring(0, historySearchQuery.length() - 1);
                searchHistoryBackward(historySearchQuery, inputHistory.size() - 1);
            }
            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, false, ""));
        } else if (!input.isEmpty() && isPrintableInput(input, key)) {
            // 输入字符: 追加到搜索词并搜索
            historySearchQuery += input;
            searchHistoryBackward(historySearchQuery, inputHistory.size() - 1);
            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, false, ""));
        }
    }

    /** 从指定位置向前搜索历史记录 */
    private void searchHistoryBackward(String query, int startIdx) {
        if (query.isEmpty()) {
            historySearchResult = "";
            historySearchIndex = -1;
            return;
        }
        String lowerQuery = query.toLowerCase();
        for (int i = Math.min(startIdx, inputHistory.size() - 1); i >= 0; i--) {
            if (inputHistory.get(i).toLowerCase().contains(lowerQuery)) {
                historySearchResult = inputHistory.get(i);
                historySearchIndex = i;
                return;
            }
        }
        // 没找到 — 保留之前的结果（或清空）
        historySearchResult = "";
        historySearchIndex = -1;
    }

    /** Tab 自动补全处理 */
    private void handleTabCompletion(TuiState s) {
        if (!s.inputText.startsWith("/")) return;

        List<String> suggestions = commandSuggestions;
        if (suggestions.isEmpty()) {
            // 第一次按 Tab 时可能还没计算建议
            updateCommandSuggestions(s.inputText);
            suggestions = commandSuggestions;
            if (suggestions.isEmpty()) return;
        }

        if (suggestions.size() == 1) {
            // 唯一匹配 → 直接补全 + 空格（准备输入参数）
            String completed = "/" + suggestions.getFirst();
            updateCommandSuggestions(completed);
            setState(new TuiState(completed, s.messages, s.scrollOffset, false, ""));
        } else {
            // 多个匹配 → 循环选择
            tabCompletionIndex = (tabCompletionIndex + 1) % suggestions.size();
            String completed = "/" + suggestions.get(tabCompletionIndex);
            setState(new TuiState(completed, s.messages, s.scrollOffset, false, ""));
        }
    }

    /** 提交用户输入 */
    private void submitInput(String text, TuiState s) {
        inputHistory.add(text);
        historyIndex = -1;
        savedInput = "";
        updateCommandSuggestions(""); // 清除命令建议

        // 斜杠命令
        if (commandRegistry != null && commandRegistry.isCommand(text)) {
            addMessage(new UserMsg(text));
            // 捕获命令输出到 ByteArrayOutputStream
            var baos = new ByteArrayOutputStream();
            try (var capturedOut = new PrintStream(baos, true, StandardCharsets.UTF_8)) {
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
            }
            setState(new TuiState("", getState().messages, 0, false, ""));
            return;
        }

        // Agent 调用
        if (onFirstUserInput != null) {
            onFirstUserInput.accept(text);
            onFirstUserInput = null; // 只触发一次
        }
        inferSessionTitle(text); // 从首条用户消息推断终端标题
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
        synchronized (spinnerLock) {
            stopSpinnerInternal();
            spinnerFrame = 0;
            setTerminalTitle(computeTerminalTitle()); // 立即更新标题
            Thread t = Thread.startVirtualThread(() -> {
                try {
                    int titleUpdateCounter = 0;
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(120);
                        spinnerFrame++;
                        titleUpdateCounter++;
                        // 每 ~960ms 更新一次终端标题（匹配官方 TITLE_ANIMATION_INTERVAL_MS=960）
                        if (titleUpdateCounter >= 8) {
                            titleUpdateCounter = 0;
                            setTerminalTitle(computeTerminalTitle());
                        }
                        synchronized (stateLock) {
                            TuiState s = getState();
                            setState(new TuiState(s.inputText, s.messages, s.scrollOffset, s.thinking, s.thinkingText));
                        }
                    }
                } catch (InterruptedException ignored) {}
            });
            spinnerThread = t;
        }
    }

    /** 停止思考动画 */
    private void stopSpinner() {
        synchronized (spinnerLock) {
            stopSpinnerInternal();
        }
        setTerminalTitle(computeTerminalTitle()); // 恢复静态标题
    }

    private void stopSpinnerInternal() {
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
        // 匹配官方 sticky-scroll 行为：
        // - 用户在底部（scrollOffset=0）→ 新消息保持在底部
        // - 用户已上滚（scrollOffset>0）→ 保持当前位置，不自动跳到底部
        int newOffset = s.scrollOffset;
        setState(new TuiState(s.inputText, Collections.unmodifiableList(newMsgs),
                newOffset, s.thinking, s.thinkingText));
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

    /** 追加工具执行的流式输出行到最后一个运行中的 ToolCallMsg */
    public void appendToolOutput(String line) {
        synchronized (stateLock) {
            TuiState s = getState();
            List<UIMessage> msgs = new ArrayList<>(s.messages);

            for (int i = msgs.size() - 1; i >= 0; i--) {
                if (msgs.get(i) instanceof ToolCallMsg tcm && tcm.running()) {
                    msgs.set(i, tcm.appendOutput(line));
                    break;
                }
            }

            setState(new TuiState(s.inputText, Collections.unmodifiableList(msgs),
                    s.scrollOffset, s.thinking, s.thinkingText));
        }
    }

    /** 设置简单文本输入回调（用于无选项的 AskUser） */
    public void requestTextInput(Consumer<String> callback) {
        this.askOptions = null;
        this.askInputMode = false;
        this.askQuestion = null;
        this.permissionOptions = null;
        this.permissionCallback = callback;
    }

    /** 设置权限确认回调（交互选择模式） */
    public void requestPermission(String toolName, String suggestedRule, Consumer<String> callback) {
        this.askOptions = null;
        this.askInputMode = false;
        this.askQuestion = null;
        // 构建权限选项（匹配原版 Claude Code 格式）
        List<String> options = new ArrayList<>();
        options.add("Yes");
        if (suggestedRule != null) {
            options.add("Yes, and don't ask again for " + toolName + " commands in " + System.getProperty("user.dir", "."));
        } else {
            options.add("Yes, and don't ask again for " + toolName);
        }
        options.add("No");
        this.permissionOptions = options;
        this.permissionSelectedIndex = 0;
        this.permissionCallback = callback;
        // 触发重绘
        synchronized (stateLock) {
            TuiState s = getState();
            setState(new TuiState("", s.messages, s.scrollOffset, s.thinking, s.thinkingText));
        }
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
        if (thinking && thinkingStartTime == 0) {
            thinkingStartTime = System.currentTimeMillis();
        } else if (!thinking) {
            thinkingStartTime = 0;
        }
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
        int totalItems = lastRenderedItemCount;
        int visibleLines = lastMaxVisibleLines;
        // 最大偏移 = 超出可见范围的行数
        int maxOffset = Math.max(0, totalItems - visibleLines);
        int newOffset = Math.max(0, Math.min(s.scrollOffset + delta, maxOffset));
        setState(new TuiState(s.inputText, s.messages, newOffset, s.thinking, s.thinkingText));
    }

    private void scrollToTop(TuiState s) {
        int maxOffset = Math.max(0, lastRenderedItemCount - lastMaxVisibleLines);
        setState(new TuiState(s.inputText, s.messages, maxOffset, s.thinking, s.thinkingText));
    }

    private void scrollToBottom(TuiState s) {
        setState(new TuiState(s.inputText, s.messages, 0, s.thinking, s.thinkingText));
    }

    // ==================== 工具方法 ====================

    /** 计算匹配的斜杠命令（前缀匹配） */
    private List<String> computeCommandSuggestions(String inputText) {
        if (commandRegistry == null || !inputText.startsWith("/")) {
            return List.of();
        }
        // 如果已经有空格，说明在输入参数，不再补全命令名
        if (inputText.contains(" ")) {
            return List.of();
        }
        String query = inputText.substring(1).toLowerCase();
        if (query.isEmpty()) {
            // 只输入了 "/"，返回所有命令
            return commandRegistry.getCommands().stream()
                    .map(cmd -> cmd.name())
                    .distinct()
                    .sorted()
                    .toList();
        }
        // 前缀匹配
        return commandRegistry.getCommandNames().stream()
                .filter(name -> name.startsWith(query))
                .sorted()
                .toList();
    }

    /** 更新命令建议列表（输入变化时调用） */
    private void updateCommandSuggestions(String inputText) {
        commandSuggestions = computeCommandSuggestions(inputText);
        tabCompletionIndex = -1; // 重置 Tab 循环
    }

    /** 获取第一个建议的补全后缀（用于 ghost text 显示） */
    private String getGhostText(String inputText) {
        if (!inputText.startsWith("/") || inputText.contains(" ")) return "";
        String query = inputText.substring(1).toLowerCase();
        if (query.isEmpty()) return "";
        List<String> suggestions = commandSuggestions;
        if (suggestions.isEmpty()) return "";
        // 返回第一个匹配项的剩余部分
        String firstMatch = suggestions.getFirst();
        if (firstMatch.startsWith(query) && firstMatch.length() > query.length()) {
            return firstMatch.substring(query.length());
        }
        return "";
    }

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

    // ==================== 终端标题 ====================

    /**
     * 设置终端标题（OSC 0 escape sequence）。
     * 匹配官方 Claude Code 的 useTerminalTitle hook 行为。
     * 必须绕过 jink 的 ConsolePatcher（它会拦截 System.out/err），
     * 直接写入原始输出流。
     */
    private static void setTerminalTitle(String title) {
        if (title == null || title.isBlank()) return;
        try {
            // 绕过 ConsolePatcher 拦截，直接写入终端
            PrintStream raw = io.mybatis.jink.util.ConsolePatcher.getOriginalOut();
            // OSC 0: Set window title and icon name
            // Format: ESC ] 0 ; <title> BEL
            raw.print("\033]0;" + title + "\007");
            raw.flush();
        } catch (Exception ignored) {}
    }

    /**
     * 根据当前状态生成终端标题文本。
     * 匹配官方 AnimatedTerminalTitle 组件的行为：
     * - 空闲: "✳ Claude Code" 或 "✳ <sessionTitle>"
     * - 工作中: "⠂ <title>" / "⠐ <title>" (交替动画)
     */
    private String computeTerminalTitle() {
        String title = sessionTitle != null ? sessionTitle : "Claude Code";
        if (agentRunning.get()) {
            String frame = TITLE_ANIMATION_FRAMES[spinnerFrame % TITLE_ANIMATION_FRAMES.length];
            return frame + " " + title;
        }
        return TITLE_STATIC_PREFIX + " " + title;
    }

    /**
     * 从首条用户消息推断会话标题（简化版，不调用 AI）。
     * 官方使用 Haiku 生成 3-7 词标题，这里取前 40 字符作为简化实现。
     */
    private void inferSessionTitle(String userInput) {
        if (sessionTitle != null || userInput == null || userInput.isBlank()) return;
        if (userInput.startsWith("/")) return; // 跳过斜杠命令
        String trimmed = userInput.strip();
        if (trimmed.length() > 40) {
            trimmed = trimmed.substring(0, 40) + "…";
        }
        sessionTitle = trimmed;
    }
}
