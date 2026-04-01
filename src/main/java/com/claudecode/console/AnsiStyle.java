package com.claudecode.console;

/**
 * ANSI 终端样式工具类 —— 对应 claude-code/src/utils/terminal.ts 的颜色输出功能。
 * <p>
 * 提供终端颜色和样式的便捷方法。
 */
public final class AnsiStyle {

    private AnsiStyle() {}

    // 重置
    public static final String RESET = "\033[0m";

    // 基本样式
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";
    public static final String ITALIC = "\033[3m";
    public static final String UNDERLINE = "\033[4m";

    // 前景色
    public static final String BLACK = "\033[30m";
    public static final String RED = "\033[31m";
    public static final String GREEN = "\033[32m";
    public static final String YELLOW = "\033[33m";
    public static final String BLUE = "\033[34m";
    public static final String MAGENTA = "\033[35m";
    public static final String CYAN = "\033[36m";
    public static final String WHITE = "\033[37m";

    // 亮色前景
    public static final String BRIGHT_BLACK = "\033[90m";
    public static final String BRIGHT_RED = "\033[91m";
    public static final String BRIGHT_GREEN = "\033[92m";
    public static final String BRIGHT_YELLOW = "\033[93m";
    public static final String BRIGHT_BLUE = "\033[94m";
    public static final String BRIGHT_MAGENTA = "\033[95m";
    public static final String BRIGHT_CYAN = "\033[96m";
    public static final String BRIGHT_WHITE = "\033[97m";

    // 背景色
    public static final String BG_RED = "\033[41m";
    public static final String BG_GREEN = "\033[42m";
    public static final String BG_YELLOW = "\033[43m";
    public static final String BG_BLUE = "\033[44m";

    // ---- 便捷方法 ----

    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    public static String dim(String text) {
        return DIM + text + RESET;
    }

    public static String italic(String text) {
        return ITALIC + text + RESET;
    }

    public static String red(String text) {
        return RED + text + RESET;
    }

    public static String green(String text) {
        return GREEN + text + RESET;
    }

    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }

    public static String blue(String text) {
        return BLUE + text + RESET;
    }

    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    public static String magenta(String text) {
        return MAGENTA + text + RESET;
    }

    public static String brightBlack(String text) {
        return BRIGHT_BLACK + text + RESET;
    }

    /** 带颜色的前缀标签 */
    public static String tag(String label, String color) {
        return color + BOLD + "[" + label + "]" + RESET;
    }

    /** 带背景色的标签 */
    public static String badge(String label, String bgColor) {
        return bgColor + WHITE + BOLD + " " + label + " " + RESET;
    }

    /** 清除当前行 */
    public static String clearLine() {
        return "\033[2K\r";
    }

    /** 光标上移 n 行 */
    public static String cursorUp(int n) {
        return "\033[" + n + "A";
    }
}
