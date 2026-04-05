package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /help 命令 —— 动态展示所有已注册的斜杠命令。
 * <p>
 * 支持：
 * <ul>
 *   <li>/help —— 列出所有命令</li>
 *   <li>/help [keyword] —— 搜索匹配的命令</li>
 *   <li>/help [command] —— 显示特定命令详情</li>
 * </ul>
 */
public class HelpCommand implements SlashCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "Show available commands. Use /help [keyword] to search or /help [command] for details.";
    }

    @Override
    public List<String> aliases() {
        return List.of("?");
    }

    @Override
    public String execute(String args, CommandContext context) {
        String query = (args == null) ? "" : args.strip();
        var allCommands = context.commandRegistry().getCommands();

        // If a specific command name is given, show details
        if (!query.isEmpty()) {
            String queryClean = query.startsWith("/") ? query.substring(1) : query;

            // Exact match → show detail
            var exactMatch = allCommands.stream()
                    .filter(cmd -> cmd.name().equalsIgnoreCase(queryClean)
                            || cmd.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(queryClean)))
                    .findFirst();

            if (exactMatch.isPresent()) {
                return formatCommandDetail(exactMatch.get());
            }

            // Fuzzy search by keyword
            String queryLower = queryClean.toLowerCase();
            var matches = allCommands.stream()
                    .filter(cmd -> cmd.name().toLowerCase().contains(queryLower)
                            || cmd.description().toLowerCase().contains(queryLower))
                    .collect(Collectors.toList());

            if (matches.isEmpty()) {
                return AnsiStyle.yellow("  No commands matching \"" + query + "\"\n")
                        + AnsiStyle.dim("  Use /help to see all commands");
            }

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.bold("\n  Commands matching \"" + query + "\":\n\n"));
            appendCommandList(sb, matches);
            return sb.toString();
        }

        // No args → show all
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  Available Commands:\n\n"));
        appendCommandList(sb, allCommands);
        sb.append("\n");
        sb.append(AnsiStyle.dim("  💡 Use /help [command] for details, /help [keyword] to search\n"));
        sb.append(AnsiStyle.dim("  Shortcuts: Tab to autocomplete, ↑↓ to browse history, Ctrl+D to exit\n"));
        return sb.toString();
    }

    private void appendCommandList(StringBuilder sb, List<? extends SlashCommand> commands) {
        int maxNameLen = commands.stream()
                .mapToInt(cmd -> cmd.name().length())
                .max().orElse(12);
        maxNameLen = Math.max(maxNameLen, 12);

        for (SlashCommand cmd : commands) {
            String nameStr = "/" + cmd.name();
            String aliasStr = "";
            if (!cmd.aliases().isEmpty()) {
                aliasStr = AnsiStyle.DIM + " (also: "
                        + String.join(", ", cmd.aliases().stream().map(a -> "/" + a).toList())
                        + ")" + AnsiStyle.RESET;
            }
            sb.append(String.format("  %s%-" + (maxNameLen + 2) + "s%s %s%s%n",
                    AnsiStyle.CYAN, nameStr, AnsiStyle.RESET, cmd.description(), aliasStr));
        }
    }

    private String formatCommandDetail(SlashCommand cmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  ").append(AnsiStyle.bold("/" + cmd.name())).append("\n");
        sb.append("  ").append("─".repeat(40)).append("\n");
        sb.append("  ").append(cmd.description()).append("\n");
        if (!cmd.aliases().isEmpty()) {
            sb.append("  ").append(AnsiStyle.dim("Aliases: "
                    + cmd.aliases().stream().map(a -> "/" + a).collect(Collectors.joining(", ")))).append("\n");
        }
        return sb.toString();
    }
}
