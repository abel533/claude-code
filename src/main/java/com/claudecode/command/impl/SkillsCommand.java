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

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🎯 Available Skills\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        if (skills.isEmpty()) {
            sb.append(AnsiStyle.dim("  (无可用技能)\n\n"));
            sb.append(AnsiStyle.dim("  技能文件放置位置：\n"));
            sb.append(AnsiStyle.dim("    用户级:  ~/.claude/skills/*.md\n"));
            sb.append(AnsiStyle.dim("    项目级:  ./.claude/skills/*.md\n"));
            sb.append(AnsiStyle.dim("    命令级:  ./.claude/commands/*.md\n"));
        } else {
            for (SkillLoader.Skill skill : skills) {
                sb.append("  ").append(AnsiStyle.cyan("▸ ")).append(AnsiStyle.bold(skill.name()));

                // 来源标签
                String sourceLabel = switch (skill.source()) {
                    case "user" -> AnsiStyle.dim(" [用户级]");
                    case "project" -> AnsiStyle.dim(" [项目级]");
                    case "command" -> AnsiStyle.dim(" [命令]");
                    default -> AnsiStyle.dim(" [" + skill.source() + "]");
                };
                sb.append(sourceLabel).append("\n");

                if (!skill.description().isEmpty()) {
                    sb.append("    ").append(skill.description()).append("\n");
                }
                if (!skill.whenToUse().isEmpty()) {
                    sb.append("    ").append(AnsiStyle.dim("When: " + skill.whenToUse())).append("\n");
                }
                sb.append("    ").append(AnsiStyle.dim("File: " + skill.filePath())).append("\n");
                sb.append("\n");
            }
            sb.append(AnsiStyle.dim("  共 " + skills.size() + " 个技能\n"));
        }

        return sb.toString();
    }
}
