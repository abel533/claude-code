package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.context.SkillLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * /skills 命令 —— 列出所有可用的技能。
 * <p>
 * 扫描并显示从用户级、项目级和命令目录加载的技能文件。
 */
public class SkillsCommand implements SlashCommand {

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "List available skills";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        SkillLoader loader = new SkillLoader(projectDir);
        List<SkillLoader.Skill> skills = loader.loadAll();

        // If args provided, show specific skill detail
        if (args != null && !args.isBlank()) {
            String query = args.strip().toLowerCase();
            var match = skills.stream()
                    .filter(s -> s.name().equalsIgnoreCase(query))
                    .findFirst();
            if (match.isEmpty()) {
                match = skills.stream()
                        .filter(s -> s.name().toLowerCase().contains(query))
                        .findFirst();
            }
            if (match.isPresent()) {
                return formatSkillDetail(match.get());
            }
            return AnsiStyle.red("  Skill not found: " + args.strip()) + "\n"
                    + AnsiStyle.dim("  Use /skills to list all available skills.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🎯 Available Skills\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        if (skills.isEmpty()) {
            sb.append(AnsiStyle.dim("  (No available skills)\n\n"));
            sb.append(AnsiStyle.dim("  Skill file locations:\n"));
            sb.append(AnsiStyle.dim("    User:    ~/.claude/skills/*.md\n"));
            sb.append(AnsiStyle.dim("    Project: ./.claude/skills/*.md\n"));
            sb.append(AnsiStyle.dim("    Command: ./.claude/commands/*.md\n"));
        } else {
            for (SkillLoader.Skill skill : skills) {
                sb.append("  ").append(AnsiStyle.cyan("▸ ")).append(AnsiStyle.bold(skill.name()));

                // 来源标签
                String sourceLabel = switch (skill.source()) {
                    case "user" -> AnsiStyle.dim(" [user]");
                    case "project" -> AnsiStyle.dim(" [project]");
                    case "command" -> AnsiStyle.dim(" [command]");
                    default -> AnsiStyle.dim(" [" + skill.source() + "]");
                };
                sb.append(sourceLabel).append("\n");

                if (!skill.description().isEmpty()) {
                    sb.append("    ").append(skill.description()).append("\n");
                }
                if (!skill.whenToUse().isEmpty()) {
                    sb.append("    ").append(AnsiStyle.dim("When: " + skill.whenToUse())).append("\n");
                }
                sb.append("\n");
            }
            sb.append(AnsiStyle.dim("  Total " + skills.size() + " skills"));
            sb.append(AnsiStyle.dim(" • Use /skills <name> for details\n"));
        }

        return sb.toString();
    }

    private String formatSkillDetail(SkillLoader.Skill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🎯 Skill: " + skill.name())).append("\n");
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        sb.append("  ").append(AnsiStyle.bold("Source: ")).append(skill.source()).append("\n");
        if (!skill.description().isEmpty()) {
            sb.append("  ").append(AnsiStyle.bold("Description: ")).append(skill.description()).append("\n");
        }
        if (!skill.whenToUse().isEmpty()) {
            sb.append("  ").append(AnsiStyle.bold("When to use: ")).append(skill.whenToUse()).append("\n");
        }
        sb.append("  ").append(AnsiStyle.bold("File: ")).append(skill.filePath()).append("\n");
        sb.append("\n");

        // Show content preview
        String content = skill.content();
        if (content.length() > 500) {
            content = content.substring(0, 497) + "...";
        }
        sb.append(AnsiStyle.dim("  Content:\n"));
        for (String line : content.lines().toList()) {
            sb.append(AnsiStyle.dim("  │ " + line)).append("\n");
        }
        sb.append("\n");
        sb.append(AnsiStyle.dim("  Tip: Ask AI to execute this skill or type: /verify, /debug, etc.\n"));

        return sb.toString();
    }
}
