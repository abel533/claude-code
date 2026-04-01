package com.claudecode.repl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandRegistry;
import com.claudecode.console.*;
import com.claudecode.core.AgentLoop;
import com.claudecode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.Scanner;

/**
 * REPL 会话管理器 —— 对应 claude-code/src/REPL.tsx。
 * <p>
 * 管理用户输入循环、命令分发、Agent 调用和输出渲染。
 * 当前版本使用 Scanner 作为输入方式（Phase 2 会升级到 JLine）。
 */
public class ReplSession {

    private static final Logger log = LoggerFactory.getLogger(ReplSession.class);

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final CommandRegistry commandRegistry;
    private final PrintStream out;
    private final ToolStatusRenderer toolStatusRenderer;
    private final MarkdownRenderer markdownRenderer;
    private final SpinnerAnimation spinner;

    private volatile boolean running = true;

    public ReplSession(AgentLoop agentLoop,
                       ToolRegistry toolRegistry,
                       CommandRegistry commandRegistry) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.commandRegistry = commandRegistry;
        this.out = System.out;
        this.toolStatusRenderer = new ToolStatusRenderer(out);
        this.markdownRenderer = new MarkdownRenderer(out);
        this.spinner = new SpinnerAnimation(out);

        // 注册 AgentLoop 事件回调
        agentLoop.setOnToolEvent(event -> {
            switch (event.phase()) {
                case START -> {
                    spinner.stop();
                    toolStatusRenderer.renderStart(event.toolName(), event.arguments());
                }
                case END -> {
                    toolStatusRenderer.renderEnd(event.toolName(), event.result());
                }
            }
        });

        agentLoop.setOnAssistantMessage(text -> {
            // 助手文本在 agent 循环结束后由 REPL 统一渲染
        });
    }

    /**
     * 启动 REPL 主循环。
     */
    public void start() {
        BannerPrinter.printCompact(out);
        out.println(AnsiStyle.dim("  Working directory: " + System.getProperty("user.dir")));
        out.println(AnsiStyle.dim("  Tools: " + toolRegistry.size() + " registered"));
        out.println();

        Scanner scanner = new Scanner(System.in);
        CommandContext cmdContext = new CommandContext(agentLoop, toolRegistry, out, () -> running = false);

        while (running) {
            // 输入提示符
            out.print(AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + "  ❯ " + AnsiStyle.RESET);
            out.flush();

            String input;
            try {
                if (!scanner.hasNextLine()) {
                    break; // EOF (Ctrl+D)
                }
                input = scanner.nextLine().strip();
            } catch (Exception e) {
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            // 检查斜杠命令
            if (commandRegistry.isCommand(input)) {
                var result = commandRegistry.dispatch(input, cmdContext);
                result.ifPresent(out::println);
                out.println();
                continue;
            }

            // 调用 Agent 循环
            try {
                spinner.start("Thinking...");
                String response = agentLoop.run(input);
                spinner.stop();

                out.println();
                markdownRenderer.render(response);
                out.println();
            } catch (Exception e) {
                spinner.stop();
                out.println(AnsiStyle.red("\n  ✗ Error: " + e.getMessage()));
                log.error("Agent 循环异常", e);
                out.println();
            }
        }

        out.println(AnsiStyle.dim("\n  Goodbye! 👋\n"));
    }

    public void stop() {
        running = false;
    }
}
