package com.claudecode.console;

import java.io.PrintStream;

/**
 * Banner 打印器 —— 对应 claude-code/src/components/Banner.tsx。
 * <p>
 * 在启动时打印 ASCII Art Logo 和版本信息。
 */
public class BannerPrinter {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    /**
     * 打印 claude-code-java 启动 banner。
     */
    public static void print(PrintStream out) {
        String banner = """
            %s
              ██████╗██╗      █████╗ ██╗   ██╗██████╗ ███████╗
             ██╔════╝██║     ██╔══██╗██║   ██║██╔══██╗██╔════╝
             ██║     ██║     ███████║██║   ██║██║  ██║█████╗
             ██║     ██║     ██╔══██║██║   ██║██║  ██║██╔══╝
             ╚██████╗███████╗██║  ██║╚██████╔╝██████╔╝███████╗
              ╚═════╝╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚══════╝
                     %s  ██████╗ ██████╗ ██████╗ ███████╗
                     ██╔════╝██╔═══██╗██╔══██╗██╔════╝
                     ██║     ██║   ██║██║  ██║█████╗
                     ██║     ██║   ██║██║  ██║██╔══╝
                     ╚██████╗╚██████╔╝██████╔╝███████╗
                      ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝
            %s
            """.formatted(AnsiStyle.BRIGHT_CYAN, AnsiStyle.BRIGHT_MAGENTA, AnsiStyle.RESET);

        out.println(banner);
        out.println(AnsiStyle.bold("  Claude Code (Java)") + AnsiStyle.dim("  v" + VERSION));
        out.println(AnsiStyle.dim("  Powered by Spring AI  •  Type /help for commands"));
        out.println();
    }

    /**
     * 精简版 banner（用于窄终端）。
     */
    public static void printCompact(PrintStream out) {
        out.println();
        out.println(AnsiStyle.BRIGHT_CYAN + AnsiStyle.BOLD + "  ◆ Claude Code (Java)" + AnsiStyle.RESET
                + AnsiStyle.dim("  v" + VERSION));
        out.println(AnsiStyle.dim("  Type /help for commands  •  Ctrl+D to exit"));
        out.println();
    }
}
