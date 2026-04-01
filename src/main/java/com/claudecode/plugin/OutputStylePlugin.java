package com.claudecode.plugin;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 输出样式插件 —— 提供 /style 命令来切换输出样式。
 * <p>
 * 这是一个内建的示例插件，演示了插件系统的使用方式。
 * 用户可以通过 /style 命令在不同的输出样式之间切换。
 *
 * <h3>可用样式</h3>
 * <ul>
 *   <li><b>default</b> —— 默认彩色输出，包含 ANSI 颜色和格式</li>
 *   <li><b>minimal</b> —— 精简输出，无颜色无装饰</li>
 *   <li><b>verbose</b> —— 详细输出，包含额外的调试和时间戳信息</li>
 *   <li><b>markdown</b> —— 纯 Markdown 输出，适合导出和分享</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>当前样式使用 {@link AtomicReference} 存储，支持并发读写。</p>
 */
public class OutputStylePlugin implements Plugin {

    /** 所有支持的样式名称 */
    private static final Set<String> SUPPORTED_STYLES = Set.of(
            "default", "minimal", "verbose", "markdown"
    );

    /** 当前激活的输出样式，使用原子引用保证线程安全 */
    private final AtomicReference<String> currentStyle = new AtomicReference<>("default");

    /** 插件上下文引用 */
    private PluginContext context;

    @Override
    public String id() {
        return "output-style";
    }

    @Override
    public String name() {
        return "Output Style";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String description() {
        return "自定义输出样式";
    }

    @Override
    public void initialize(PluginContext context) {
        this.context = context;
        context.getLogger().info("输出样式插件已初始化，当前样式: {}", currentStyle.get());
    }

    @Override
    public List<SlashCommand> getCommands() {
        return List.of(new StyleCommand());
    }

    /**
     * 获取当前激活的输出样式名称。
     *
     * @return 样式名称（"default"、"minimal"、"verbose" 或 "markdown"）
     */
    public String getCurrentStyle() {
        return currentStyle.get();
    }

    /**
     * 以编程方式设置输出样式。
     *
     * @param style 样式名称
     * @return 是否设置成功（样式名称有效）
     */
    public boolean setStyle(String style) {
        if (style != null && SUPPORTED_STYLES.contains(style.toLowerCase())) {
            currentStyle.set(style.toLowerCase());
            return true;
        }
        return false;
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.getLogger().info("输出样式插件已销毁");
        }
    }

    // ========================================================================
    // 内部类：/style 命令
    // ========================================================================

    /**
     * /style 命令 —— 查看或切换输出样式。
     * <p>
     * 用法：
     * <ul>
     *   <li>{@code /style} —— 显示当前样式和所有可用样式</li>
     *   <li>{@code /style <name>} —— 切换到指定样式</li>
     * </ul>
     */
    private class StyleCommand implements SlashCommand {

        @Override
        public String name() {
            return "style";
        }

        @Override
        public String description() {
            return "Switch output style (default/minimal/verbose/markdown)";
        }

        @Override
        public String execute(String args, CommandContext commandContext) {
            String trimmed = (args == null) ? "" : args.trim().toLowerCase();

            // 无参数：显示当前样式和所有可用选项
            if (trimmed.isEmpty()) {
                return showStyles();
            }

            // 切换样式
            if (!SUPPORTED_STYLES.contains(trimmed)) {
                return AnsiStyle.red("  ✗ 未知样式: " + trimmed) + "\n"
                        + AnsiStyle.dim("  可用样式: default, minimal, verbose, markdown");
            }

            String oldStyle = currentStyle.getAndSet(trimmed);

            // 将当前样式存入 ToolContext 共享状态，供其他组件读取
            if (commandContext.agentLoop() != null) {
                try {
                    commandContext.agentLoop().getToolContext().set("OUTPUT_STYLE", trimmed);
                } catch (Exception ignored) {
                    // 兼容性处理
                }
            }

            if (context != null) {
                context.getLogger().info("输出样式切换: {} → {}", oldStyle, trimmed);
            }

            return AnsiStyle.green("  ✓ 输出样式已切换: ")
                    + AnsiStyle.bold(oldStyle)
                    + " → "
                    + AnsiStyle.bold(AnsiStyle.cyan(trimmed))
                    + "\n" + getStyleDescription(trimmed);
        }

        /**
         * 显示当前样式和所有可用样式。
         */
        private String showStyles() {
            String active = currentStyle.get();
            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append(AnsiStyle.bold("  🎨 Output Styles")).append("\n");
            sb.append("  ").append("─".repeat(40)).append("\n\n");

            for (String style : List.of("default", "minimal", "verbose", "markdown")) {
                boolean isActive = style.equals(active);
                String indicator = isActive ? AnsiStyle.green("● ") : AnsiStyle.dim("○ ");
                String styleName = isActive
                        ? AnsiStyle.bold(AnsiStyle.cyan(style))
                        : style;
                String desc = getStyleBrief(style);
                sb.append("  ").append(indicator).append(styleName)
                        .append(AnsiStyle.dim(" - " + desc)).append("\n");
            }

            sb.append("\n").append(AnsiStyle.dim("  用法: /style <name>")).append("\n");
            return sb.toString();
        }

        /**
         * 获取样式的简短描述。
         */
        private String getStyleBrief(String style) {
            return switch (style) {
                case "default" -> "默认彩色输出";
                case "minimal" -> "精简输出，无颜色";
                case "verbose" -> "详细输出，含调试信息";
                case "markdown" -> "纯 Markdown 输出";
                default -> "未知样式";
            };
        }

        /**
         * 获取切换后的样式说明。
         */
        private String getStyleDescription(String style) {
            return switch (style) {
                case "default" -> AnsiStyle.dim("  使用 ANSI 颜色和格式的标准输出模式");
                case "minimal" -> AnsiStyle.dim("  无颜色无装饰的精简输出，适合管道和日志");
                case "verbose" -> AnsiStyle.dim("  包含时间戳、调试信息的详细输出模式");
                case "markdown" -> AnsiStyle.dim("  纯 Markdown 格式，适合导出到文档");
                default -> "";
            };
        }
    }
}
