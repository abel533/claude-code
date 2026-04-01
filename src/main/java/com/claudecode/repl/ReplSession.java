package com.claudecode.repl;

import com.claudecode.config.AppConfig.ProviderInfo;
import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandRegistry;
import com.claudecode.console.*;
import com.claudecode.core.AgentLoop;
import com.claudecode.tool.ToolRegistry;
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
    private final PrintStream out;
    private final ToolStatusRenderer toolStatusRenderer;
    private final MarkdownRenderer markdownRenderer;
    private final SpinnerAnimation spinner;

    private volatile boolean running = true;

    public ReplSession(AgentLoop agentLoop,
                       ToolRegistry toolRegistry,
                       CommandRegistry commandRegistry,
                       ProviderInfo providerInfo) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.commandRegistry = commandRegistry;
        this.providerInfo = providerInfo;
        // 强制使用 UTF-8 编码输出，确保 emoji 等 Unicode 字符在 Windows 终端正常显示
        this.out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        this.toolStatusRenderer = new ToolStatusRenderer(out);
        this.markdownRenderer = new MarkdownRenderer(out);
        this.spinner = new SpinnerAnimation(out);

        setupAgentCallbacks();
    }

    /** 注册 AgentLoop 事件回调，驱动控制台 UI 渲染 */
    private void setupAgentCallbacks() {
        agentLoop.setOnToolEvent(event -> {
            switch (event.phase()) {
                case START -> {
                    spinner.stop();
                    toolStatusRenderer.renderStart(event.toolName(), event.arguments());
                }
                case END -> toolStatusRenderer.renderEnd(event.toolName(), event.result());
            }
        });

        // 流式输出第一个 token 到达时停止 spinner
        agentLoop.setOnStreamStart(() -> spinner.stop());

        agentLoop.setOnAssistantMessage(text -> {
            // 阻塞模式回调：流式模式下由 onToken 实时输出，此回调不触发
        });
    }

    /**
     * 启动 REPL —— 优先使用 JLine，失败时降级到 Scanner。
     */
    public void start() {
        try {
            startWithJLine();
        } catch (Exception e) {
            log.warn("JLine 初始化失败，降级到 Scanner 模式: {}", e.getMessage());
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

            // 检测是否为 dumb 终端并提示
            boolean isDumb = "dumb".equals(terminal.getType());
            if (isDumb) {
                log.info("当前为 dumb 终端模式，建议使用 Windows Terminal / PowerShell / cmd 获得完整体验");
            }

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new DefaultParser())
                    .completer(new ClaudeCodeCompleter(commandRegistry, toolRegistry))
                    .variable(LineReader.HISTORY_FILE, historyDir.resolve("history"))
                    .variable(LineReader.HISTORY_SIZE, 1000)
                    .option(LineReader.Option.CASE_INSENSITIVE, true)
                    .option(LineReader.Option.AUTO_LIST, true)
                    .build();

            // 构建彩色提示符
            String prompt = new AttributedStringBuilder()
                    .style(AttributedStyle.BOLD.foreground(AttributedStyle.CYAN))
                    .append("❯ ")
                    .style(AttributedStyle.DEFAULT)
                    .toAnsi(terminal);

            // 续行提示符（多行输入时显示）
            String rightPrompt = new AttributedStringBuilder()
                    .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT))
                    .append("")
                    .toAnsi(terminal);

            printBanner(terminal);

            CommandContext cmdContext = new CommandContext(agentLoop, toolRegistry, out, () -> running = false);

            while (running) {
                String input;
                try {
                    input = reader.readLine(prompt).strip();
                } catch (UserInterruptException e) {
                    // Ctrl+C —— 取消当前输入，继续等待
                    spinner.stop();
                    out.println(AnsiStyle.dim("  ^C"));
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D —— 退出
                    break;
                }

                if (input.isEmpty()) {
                    continue;
                }

                handleInput(input, cmdContext);
            }

            out.println(AnsiStyle.dim("\n  Goodbye! 👋\n"));
        }
    }

    /** 打印启动 Banner（JLine 模式） */
    private void printBanner(Terminal terminal) {
        BannerPrinter.printCompact(out);

        // 显示 API 提供者、模型和 URL
        out.println(AnsiStyle.dim("  Provider: ") + AnsiStyle.cyan(providerInfo.provider().toUpperCase())
                + AnsiStyle.dim("  Model: ") + AnsiStyle.cyan(providerInfo.model()));
        out.println(AnsiStyle.dim("  API URL:  ") + AnsiStyle.cyan(providerInfo.baseUrl()));

        out.println(AnsiStyle.dim("  Work Dir: " + System.getProperty("user.dir")));
        out.println(AnsiStyle.dim("  Tools: " + toolRegistry.size() + " registered"));

        boolean isDumb = "dumb".equals(terminal.getType());
        int w = terminal.getWidth();
        int h = terminal.getHeight();
        String termInfo = terminal.getType();
        if (w > 0 && h > 0) {
            termInfo += " (" + w + "×" + h + ")";
        }
        out.println(AnsiStyle.dim("  Terminal: " + termInfo));

        if (isDumb) {
            out.println(AnsiStyle.yellow("  ⚠ Dumb 终端模式：Tab补全和行编辑可能受限"));
            out.println(AnsiStyle.yellow("    建议在 Windows Terminal / PowerShell / cmd.exe 中运行"));
        } else {
            out.println(AnsiStyle.dim("  Tip: Tab to complete commands, ↑↓ to browse history, Ctrl+D to exit"));
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
        CommandContext cmdContext = new CommandContext(agentLoop, toolRegistry, out, () -> running = false);

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

        out.println(AnsiStyle.dim("\n  Goodbye! 👋\n"));
    }

    // ==================== 公共输入处理 ====================

    /** 处理用户输入（命令分发或 Agent 调用） */
    private void handleInput(String input, CommandContext cmdContext) {
        // 斜杠命令
        if (commandRegistry.isCommand(input)) {
            var result = commandRegistry.dispatch(input, cmdContext);
            result.ifPresent(out::println);
            out.println();
            return;
        }

        // Agent 循环（流式输出）
        try {
            spinner.start("Thinking...");
            out.println(); // 换行准备输出区域

            // 流式回调：逐 token 输出到终端
            String response = agentLoop.runStreaming(input, token -> {
                out.print(token);
                out.flush();
            });

            spinner.stop();
            out.println(); // 流式输出结束后换行
            out.println();
        } catch (Exception e) {
            spinner.stop();
            out.println(AnsiStyle.red("\n  ✗ Error: " + e.getMessage()));
            log.error("Agent 循环异常", e);
            out.println();
        }
    }

    public void stop() {
        running = false;
    }
}
