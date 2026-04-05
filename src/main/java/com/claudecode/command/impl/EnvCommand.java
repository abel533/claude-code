package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * /env 命令 —— 显示环境变量和配置信息。
 */
public class EnvCommand implements SlashCommand {

    @Override
    public String name() { return "env"; }

    @Override
    public String description() { return "Show environment variables and configuration"; }

    @Override
    public String execute(String args, CommandContext context) {
        String trimmed = CommandUtils.parseArgs(args);

        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("🔧", "Environment"));

        sb.append(CommandUtils.subtitle("System")).append("\n");
        sb.append("  OS:       ").append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.version")).append("\n");
        sb.append("  Java:     ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("  JVM:      ").append(System.getProperty("java.vm.name")).append("\n");
        sb.append("  Heap:     ").append(CommandUtils.formatBytes(Runtime.getRuntime().totalMemory()))
                .append(" / ").append(CommandUtils.formatBytes(Runtime.getRuntime().maxMemory())).append("\n");
        sb.append("  PID:      ").append(ProcessHandle.current().pid()).append("\n\n");

        sb.append(CommandUtils.subtitle("Paths")).append("\n");
        sb.append("  WorkDir:  ").append(System.getProperty("user.dir")).append("\n");
        sb.append("  Home:     ").append(System.getProperty("user.home")).append("\n");
        sb.append("  Config:   ").append(System.getProperty("user.home"))
                .append(File.separator).append(".claude-code-java").append("\n\n");

        sb.append(CommandUtils.subtitle("Environment Variables")).append("\n");
        List<String> relevantVars = List.of(
                "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "CLAUDE_CODE_",
                "JAVA_HOME", "PATH", "SHELL", "TERM", "EDITOR"
        );

        Map<String, String> env = new TreeMap<>(System.getenv());
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            boolean show = relevantVars.stream().anyMatch(p -> key.startsWith(p) || key.equals(p));
            if (!show && !trimmed.equals("all")) continue;

            String value = entry.getValue();
            if (key.contains("KEY") || key.contains("SECRET") || key.contains("TOKEN")) {
                value = value.length() > 8 ? value.substring(0, 4) + "****" + value.substring(value.length() - 4) : "****";
            }
            value = CommandUtils.truncate(value, 80);
            sb.append("  ").append(AnsiStyle.cyan(key)).append("=").append(value).append("\n");
        }

        if (!trimmed.equals("all")) {
            sb.append("\n").append(AnsiStyle.dim("  Run /env all to show all environment variables"));
        }

        return sb.toString();
    }
}
