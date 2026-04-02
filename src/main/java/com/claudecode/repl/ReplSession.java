package com.claudecode.repl;

import com.claudecode.config.AppConfig.ProviderInfo;
import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandRegistry;
import com.claudecode.console.*;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.ConversationPersistence;
import com.claudecode.permission.DangerousPatterns;
import com.claudecode.permission.PermissionTypes.PermissionChoice;
import com.claudecode.permission.PermissionTypes.PermissionDecision;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.AskUserQuestionTool;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.function.Function;

/**
 * REPL 会话管理器 —— 对应 claude-code/src/REPL.tsx。
 * <p>
 * 使用 JLine 3 提供丰富的终端交互体验：
 * <ul>
 *   <li>行编辑（光标移动、删除、粘贴）</li>
 *   <li>历史记录（上下箭头浏览、持久化到文件）</li>
 *   <li>Tab 补全（斜杠命令、工具名称）</li>
 *   <li>信号处理（Ctrl+C 取消当前输入、Ctrl+D 退出）</li>
 * </ul>
 * 当 JLine 初始化失败时自动降级到 Scanner 模式。
 */
public class ReplSession {

    private static final Logger log = LoggerFactory.getLogger(ReplSession.class);

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final CommandRegistry commandRegistry;
    private final ProviderInfo providerInfo;
    private final ConversationPersistence persistence;
    private final PrintStream out;
    private final ToolStatusRenderer toolStatusRenderer;
    private final MarkdownRenderer markdownRenderer;
    private final SpinnerAnimation spinner;
    private final ThinkingRenderer thinkingRenderer;
    private final StatusLine statusLine;

    /** 对话摘要（取第一次用户输入的前40字） */
    private String conversationSummary = "";
    private volatile boolean running = true;

    /** 流式输出换行跟踪：工具渲染和流式回调共享，保证缩进一致 */
    private volatile boolean streamNewLine = false;

    /** 流式行缓冲：累积 token 到换行再 Markdown 渲染输出 */
    private final StringBuilder streamLineBuffer = new StringBuilder();
    /** 流式 Markdown 渲染状态：跨行追踪代码块 */
    private MarkdownRenderer.StreamState streamMdState = new MarkdownRenderer.StreamState();

    /** 当前活跃的 LineReader（JLine 模式下用于 AskUser 和权限确认） */
    private volatile LineReader activeReader;
    /** 当前活跃的 Scanner（Scanner 模式下用于 AskUser 和权限确认） */
    private volatile Scanner activeScanner;

    public ReplSession(AgentLoop agentLoop,
                       ToolRegistry toolRegistry,
                       CommandRegistry commandRegistry,
                       ProviderInfo providerInfo) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.commandRegistry = commandRegistry;
        this.providerInfo = providerInfo;
        this.persistence = new ConversationPersistence();
        // 强制使用 UTF-8 编码输出，确保 emoji 等 Unicode 字符在 Windows 终端正常显示
        this.out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        this.toolStatusRenderer = new ToolStatusRenderer(out);
        this.markdownRenderer = new MarkdownRenderer(out);
        this.spinner = new SpinnerAnimation(out);
        this.thinkingRenderer = new ThinkingRenderer(out);
        this.statusLine = new StatusLine(out);

        setupAgentCallbacks();
        setupToolContextCallbacks();
    }

    /** 注册 ToolContext 回调（AskUser 用户输入） */
    private void setupToolContextCallbacks() {
        // 注册 AskUserQuestionTool 所需的用户输入回调
        var toolContext = agentLoop.getToolContext();
        if (toolContext != null) {
            toolContext.set(AskUserQuestionTool.USER_INPUT_CALLBACK,
                    (Function<String, String>) this::readUserInputDuringAgentLoop);
        }
    }

    /** 注册 AgentLoop 事件回调，驱动控制台 UI 渲染 */
    private void setupAgentCallbacks() {
        agentLoop.setOnToolEvent(event -> {
            switch (event.phase()) {
                case START -> {
                    spinner.stop();
                    // 刷新行缓冲（AI 文本可能在工具调用前没有换行结尾）
                    flushStreamLineBuffer();
                    if (!streamNewLine) {
                        out.println();
                    }
                    toolStatusRenderer.renderStart(event.toolName(), event.arguments());
                    streamNewLine = true; // 工具渲染输出以 println 结尾
                }
                case END -> {
                    toolStatusRenderer.renderEnd(event.toolName(), event.result());
                    streamNewLine = true; // 工具渲染输出以 println 结尾，标记下一个流式 token 需要缩进
                }
            }
        });

        // 流式输出第一个 token 到达时：停止 spinner → 打印 ● 前缀
        agentLoop.setOnStreamStart(() -> {
            spinner.stop();
            // 每个流式迭代开始时打印 ● 前缀（工具调用后的续文也会获得新的 ●）
            if (streamNewLine) {
                out.println(); // 与前面的输出之间留一个空行
            }
            out.print(AnsiStyle.BRIGHT_CYAN + "  ● " + AnsiStyle.RESET);
            streamNewLine = false;
        });

        agentLoop.setOnAssistantMessage(text -> {
            // 阻塞模式回调：流式模式下由 onToken 实时输出，此回调不触发
        });

        // 权限确认回调：非只读工具执行前请求用户确认
        agentLoop.setOnPermissionRequest(request -> {
            spinner.stop();
            return promptPermission(request);
        });

        // Thinking 内容回调：显示 AI 思考过程
        agentLoop.setOnThinkingContent(thinkingText -> {
            spinner.stop();
            thinkingRenderer.render(thinkingText);
        });
    }

    /**
     * 启动 REPL —— 优先使用 JLine，失败时降级到 Scanner。
     */
    public void start() {
        try {
            startWithJLine();
        } catch (Exception e) {
            log.warn("JLine initialization failed, downgrading to Scanner mode: {}", e.getMessage());
            startWithScanner();
        }
    }

    // ==================== JLine 模式 ====================

    private void startWithJLine() throws IOException {
        Path historyDir = Path.of(System.getProperty("user.home"), ".claude-code-java");
        Files.createDirectories(historyDir);

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .streams(System.in, System.out)
                .build()) {

            boolean isDumb = "dumb".equals(terminal.getType());
            if (isDumb) {
                log.info("Dumb terminal mode, use Windows Terminal / PowerShell / cmd for full experience");
            }

            // 配置 Parser：支持反斜杠续行 (\) 和 三引号块 (""")
            DefaultParser parser = new DefaultParser();
            parser.setEscapeChars(new char[]{'\\'}); // 反斜杠续行

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(parser)
                    .completer(new ClaudeCodeCompleter(commandRegistry, toolRegistry))
                    .variable(LineReader.HISTORY_FILE, historyDir.resolve("history"))
                    .variable(LineReader.HISTORY_SIZE, 1000)
                    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%P  ... ")
                    .option(LineReader.Option.CASE_INSENSITIVE, true)
                    .option(LineReader.Option.AUTO_LIST, true)
                    .build();

            // Vim 模式支持：通过环境变量 CLAUDE_CODE_VIM=1 或配置启用
            String vimMode = System.getenv("CLAUDE_CODE_VIM");
            if ("1".equals(vimMode) || "true".equalsIgnoreCase(vimMode)) {
                reader.setVariable(LineReader.EDITING_MODE, "vi");
                log.info("Vim editing mode enabled");
            }

            // 主提示符
            String prompt = new AttributedStringBuilder()
                    .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                    .append("❯ ")
                    .style(AttributedStyle.DEFAULT)
                    .toAnsi(terminal);

            printBanner(terminal);

            // 设置活跃的 reader，供 AskUser 和权限确认使用
            this.activeReader = reader;

            // 非 dumb 终端启用底部状态行
            if (!isDumb) {
                statusLine.enable(providerInfo.model(), agentLoop.getTokenTracker());
            }

            CommandContext cmdContext = new CommandContext(agentLoop, toolRegistry, commandRegistry, out, () -> running = false);

            while (running) {
                String input;
                try {
                    input = reader.readLine(prompt).strip();
                } catch (UserInterruptException e) {
                    spinner.stop();
                    out.println(AnsiStyle.dim("  ^C"));
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                if (input.isEmpty()) {
                    continue;
                }

                handleInput(input, cmdContext);
            }

            saveConversation();
            out.println(AnsiStyle.dim("\n  Goodbye! 👋\n"));
        }
    }

    /** 打印启动 Banner（JLine 模式） */
    private void printBanner(Terminal terminal) {
        boolean isDumb = "dumb".equals(terminal.getType());
        int w = terminal.getWidth();
        int h = terminal.getHeight();
        String termInfo = terminal.getType();
        if (w > 0 && h > 0) {
            termInfo += " (" + w + "×" + h + ")";
        }

        // Vim 模式标识
        String vimMode = System.getenv("CLAUDE_CODE_VIM");
        if ("1".equals(vimMode) || "true".equalsIgnoreCase(vimMode)) {
            termInfo += " [vim]";
        }

        if (isDumb || w < 60) {
            // 窄终端/dumb 模式用精简 Banner
            BannerPrinter.printCompact(out);
            out.println(AnsiStyle.dim("  Provider: ") + AnsiStyle.cyan(providerInfo.provider().toUpperCase())
                    + AnsiStyle.dim("  Model: ") + AnsiStyle.cyan(providerInfo.model()));
            out.println(AnsiStyle.dim("  Work Dir: " + System.getProperty("user.dir")));
            if (isDumb) {
                out.println(AnsiStyle.yellow("  ⚠ Dumb terminal mode: run in Windows Terminal / PowerShell for best experience"));
            }
        } else {
            // 标准终端用带边框的 Banner
            BannerPrinter.printBoxed(out,
                    providerInfo.provider(),
                    providerInfo.model(),
                    providerInfo.baseUrl(),
                    System.getProperty("user.dir"),
                    toolRegistry.size(),
                    commandRegistry.getCommands().size(),
                    termInfo);
        }

        out.println();
    }

    // ==================== Scanner 降级模式 ====================

    private void startWithScanner() {
        BannerPrinter.printCompact(out);
        out.println(AnsiStyle.dim("  Provider: ") + AnsiStyle.cyan(providerInfo.provider().toUpperCase())
                + AnsiStyle.dim("  Model: ") + AnsiStyle.cyan(providerInfo.model()));
        out.println(AnsiStyle.dim("  API URL:  ") + AnsiStyle.cyan(providerInfo.baseUrl()));
        out.println(AnsiStyle.dim("  Work Dir: " + System.getProperty("user.dir")));
        out.println(AnsiStyle.dim("  Tools: " + toolRegistry.size() + " registered"));
        out.println(AnsiStyle.dim("  Mode: Scanner (basic input)"));
        out.println();

        Scanner scanner = new Scanner(System.in);
        this.activeScanner = scanner;
        CommandContext cmdContext = new CommandContext(agentLoop, toolRegistry, commandRegistry, out, () -> running = false);

        while (running) {
            out.print(AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + "❯ " + AnsiStyle.RESET);
            out.flush();

            String input;
            try {
                if (!scanner.hasNextLine()) break;
                input = scanner.nextLine().strip();
            } catch (Exception e) {
                break;
            }

            if (input.isEmpty()) continue;

            handleInput(input, cmdContext);
        }

        saveConversation();
        out.println(AnsiStyle.dim("\n  Goodbye! 👋\n"));
    }

    /** 处理用户输入（命令分发或 Agent 调用） */
    private void handleInput(String input, CommandContext cmdContext) {
        // 斜杠命令
        if (commandRegistry.isCommand(input)) {
            var result = commandRegistry.dispatch(input, cmdContext);
            result.ifPresent(out::println);
            out.println();
            return;
        }

        // 记录对话摘要（取第一次用户输入前40字）
        if (conversationSummary.isEmpty()) {
            conversationSummary = input.length() > 40 ? input.substring(0, 40) : input;
        }

        // Agent 循环（流式输出 + 行缓冲 Markdown 渲染）
        try {
            spinner.start("Thinking...");
            streamNewLine = true; // spinner 停止后 onStreamStart 会打印 ● 前缀
            streamLineBuffer.setLength(0); // 重置行缓冲
            streamMdState = new MarkdownRenderer.StreamState(); // 重置 Markdown 状态

            long startTime = System.currentTimeMillis();

            String response = agentLoop.runStreaming(input, token -> {
                for (int i = 0; i < token.length(); i++) {
                    char c = token.charAt(i);
                    if (c == '\n') {
                        // 行完成 → 渲染 Markdown 并输出
                        if (streamNewLine) {
                            out.print("    "); // 续行缩进（与 ● 后文本对齐）
                            streamNewLine = false;
                        }
                        String rendered = markdownRenderer.renderStreamingLine(streamLineBuffer.toString(), streamMdState);
                        out.println(rendered);
                        streamLineBuffer.setLength(0);
                        streamNewLine = true;
                    } else {
                        streamLineBuffer.append(c);
                    }
                }
                out.flush();
            });

            // 刷新残留缓冲（最后一行可能无 \n 结尾）
            flushStreamLineBuffer();

            spinner.stop();
            out.println(); // 流式输出结束后换行

            // 显示耗时
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            if (elapsed > 0) {
                out.println(AnsiStyle.DIM + "  ✻ Worked for " + elapsed + "s" + AnsiStyle.RESET);
            }

            // 刷新底部状态行（显示最新 token 用量）
            if (statusLine.isEnabled()) {
                out.println(statusLine.renderInline());
            }
            out.println();
        } catch (Exception e) {
            spinner.stop();
            out.println(AnsiStyle.RED + "\n  ● Error: " + AnsiStyle.RESET + e.getMessage());
            log.error("Agent loop exception", e);
            out.println();
        }
    }

    /**
     * 刷新流式行缓冲 —— 将未输出的缓冲内容渲染并打印。
     * 在工具调用前、流式结束后调用，防止 AI 文本丢失。
     */
    private void flushStreamLineBuffer() {
        if (!streamLineBuffer.isEmpty()) {
            if (streamNewLine) {
                out.print("    ");
                streamNewLine = false;
            }
            String rendered = markdownRenderer.renderStreamingLine(streamLineBuffer.toString(), streamMdState);
            out.print(rendered);
            streamLineBuffer.setLength(0);
        }
    }

    /** 退出时保存对话历史 */
    private void saveConversation() {
        var history = agentLoop.getMessageHistory();
        // 只有有实际对话内容时才保存（至少包含系统提示+用户消息+助手回复）
        if (history.size() > 2) {
            var file = persistence.save(history, conversationSummary);
            if (file != null) {
                out.println(AnsiStyle.dim("  💾 Conversation saved: " + file.getFileName()));
            }
        }
    }

    /** 获取对话持久化管理器 */
    public ConversationPersistence getPersistence() {
        return persistence;
    }

    public void stop() {
        running = false;
    }

    // ==================== 权限确认 UI ====================

    /**
     * 显示权限确认提示并等待用户输入。
     * 支持 4 种选项：Y(允许一次) / A(始终允许) / N(拒绝) / D(始终拒绝)。
     */
    private PermissionChoice promptPermission(AgentLoop.PermissionRequest request) {
        out.println();

        // 检查是否为危险命令
        PermissionDecision decision = request.decision();
        boolean isDangerous = (decision != null && decision.reason() != null
                && decision.reason().startsWith("⚠ DANGEROUS"));

        if (isDangerous) {
            out.println(AnsiStyle.red("  ⚠ DANGEROUS Operation"));
        } else {
            out.println(AnsiStyle.yellow("  ⚠ Permission Required"));
        }
        out.println("  " + "─".repeat(50));
        out.println("  " + AnsiStyle.bold("Tool: ") + AnsiStyle.cyan(request.toolName()));
        out.println("  " + AnsiStyle.bold("Action: ") + request.activityDescription());

        // 显示参数摘要（截断过长的参数）
        String argsPreview = request.arguments();
        if (argsPreview != null && argsPreview.length() > 200) {
            argsPreview = argsPreview.substring(0, 200) + "...";
        }
        if (argsPreview != null && !argsPreview.isBlank()) {
            out.println("  " + AnsiStyle.dim("Args: " + argsPreview));
        }

        // 显示建议规则
        String suggestedRule = null;
        if (decision != null && decision.commandPrefix() != null) {
            suggestedRule = request.toolName() + "(" + decision.commandPrefix() + ":*)";
        }

        out.println("  " + "─".repeat(50));
        out.println("  " + AnsiStyle.green("[Y]") + " Allow once");
        if (suggestedRule != null && !isDangerous) {
            out.println("  " + AnsiStyle.green("[A]") + " Always allow " + AnsiStyle.cyan(suggestedRule));
        }
        out.println("  " + AnsiStyle.red("[N]") + " Deny");
        if (suggestedRule != null) {
            out.println("  " + AnsiStyle.red("[D]") + " Always deny this pattern");
        }
        out.print("  " + AnsiStyle.bold("Choice") + AnsiStyle.dim(" [Y/a/n/d] ") + AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + "→ " + AnsiStyle.RESET);
        out.flush();

        String answer = readLineForPermission();
        if (answer == null) return PermissionChoice.DENY_ONCE;

        answer = answer.strip().toLowerCase();

        return switch (answer) {
            case "a", "always" -> {
                out.println(AnsiStyle.green("  ✓ Rule saved: always allow " +
                        (suggestedRule != null ? suggestedRule : request.toolName())));
                yield PermissionChoice.ALWAYS_ALLOW;
            }
            case "d" -> {
                out.println(AnsiStyle.red("  ✗ Rule saved: always deny " +
                        (suggestedRule != null ? suggestedRule : request.toolName())));
                yield PermissionChoice.ALWAYS_DENY;
            }
            case "n", "no" -> {
                out.println(AnsiStyle.red("  ✗ Operation denied"));
                yield PermissionChoice.DENY_ONCE;
            }
            default -> PermissionChoice.ALLOW_ONCE; // 空字符串、y、yes → 允许
        };
    }

    /** 读取权限确认的用户输入（兼容 JLine 和 Scanner 模式） */
    private String readLineForPermission() {
        try {
            if (activeReader != null) {
                return activeReader.readLine();
            } else if (activeScanner != null && activeScanner.hasNextLine()) {
                return activeScanner.nextLine();
            }
        } catch (Exception e) {
            log.debug("Permission confirmation input exception: {}", e.getMessage());
        }
        return null;
    }

    // ==================== AskUser 工具回调 ====================

    /**
     * 在 Agent 循环执行过程中读取用户输入。
     * 被 AskUserQuestionTool 通过 ToolContext 回调使用。
     */
    private String readUserInputDuringAgentLoop(String prompt) {
        spinner.stop();
        out.print(prompt);
        out.print("  " + AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + "→ " + AnsiStyle.RESET);
        out.flush();

        try {
            if (activeReader != null) {
                return activeReader.readLine();
            } else if (activeScanner != null && activeScanner.hasNextLine()) {
                return activeScanner.nextLine();
            }
        } catch (UserInterruptException e) {
            return "(User cancelled)";
        } catch (Exception e) {
            log.debug("User input read exception: {}", e.getMessage());
        }
        return null;
    }
}
