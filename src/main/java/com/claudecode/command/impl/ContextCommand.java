package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.context.ClaudeMdLoader;
import com.claudecode.context.GitContext;
import com.claudecode.context.SkillLoader;

import java.nio.file.Path;

/**
 * /context 命令 —— 显示当前上下文信息。
 * <p>
 * 展示已加载的 CLAUDE.md、Skills、Git 上下文和 Token 预算使用情况。
 */
public class ContextCommand implements SlashCommand {

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "Show current context (CLAUDE.md, skills, git)";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📋 Context Overview\n"));
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        // CLAUDE.md 状态
        sb.append(AnsiStyle.bold("  CLAUDE.md:\n"));
        ClaudeMdLoader loader = new ClaudeMdLoader(projectDir);
        String claudeMd = loader.load();
        if (claudeMd.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none loaded) — run /init to create one\n"));
        } else {
            // 显示摘要（前 200 字符）
            int lines = claudeMd.split("\n").length;
            sb.append("    ").append(AnsiStyle.green(lines + " lines loaded")).append("\n");
            String preview = claudeMd.length() > 200
                    ? claudeMd.substring(0, 200) + "..."
                    : claudeMd;
            for (String line : preview.split("\n")) {
                sb.append(AnsiStyle.dim("    │ " + line)).append("\n");
            }
        }
        sb.append("\n");

        // Skills 状态
        sb.append(AnsiStyle.bold("  Skills:\n"));
        SkillLoader skillLoader = new SkillLoader(projectDir);
        var skills = skillLoader.loadAll();
        if (skills.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none loaded) — add .md files to .claude/skills/\n"));
        } else {
            for (var skill : skills) {
                sb.append("    • ").append(AnsiStyle.cyan(skill.name()));
                if (!skill.description().isEmpty()) {
                    sb.append(AnsiStyle.dim(" — " + skill.description()));
                }
                sb.append(AnsiStyle.dim(" [" + skill.source() + "]")).append("\n");
            }
        }
        sb.append("\n");

        // Git 上下文
        sb.append(AnsiStyle.bold("  Git:\n"));
        GitContext git = new GitContext(projectDir).collect();
        if (!git.isGitRepo()) {
            sb.append(AnsiStyle.dim("    (not a git repository)\n"));
        } else {
            sb.append("    Branch: ").append(AnsiStyle.cyan(git.getBranch() != null ? git.getBranch() : "unknown")).append("\n");
            if (git.getStatus() != null) {
                long modifiedCount = git.getStatus().lines()
                        .filter(l -> !l.startsWith("##"))
                        .count();
                if (modifiedCount > 0) {
                    sb.append("    Modified: ").append(AnsiStyle.yellow(modifiedCount + " file(s)")).append("\n");
                } else {
                    sb.append("    Working tree: ").append(AnsiStyle.green("clean")).append("\n");
                }
            }
        }
        sb.append("\n");

        // Token 预算
        sb.append(AnsiStyle.bold("  System Prompt:\n"));
        String sysPrompt = context.agentLoop().getSystemPrompt();
        int charCount = sysPrompt.length();
        int estimatedTokens = charCount / 4; // 粗略估算
        sb.append("    Size: ").append(charCount).append(" chars (~").append(estimatedTokens).append(" tokens)\n");

        return sb.toString();
    }
}
