package com.claudecode.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Skills 技能加载器 —— 对应 claude-code/src/skills/ 模块。
 * <p>
 * 从多个来源扫描和加载 .md 格式的技能文件：
 * <ol>
 *   <li>用户级: ~/.claude/skills/</li>
 *   <li>项目级: ./.claude/skills/</li>
 *   <li>命令目录: ./.claude/commands/ (自动转换为技能)</li>
 * </ol>
 * <p>
 * 每个技能文件支持 YAML frontmatter 元数据：
 * <pre>
 * ---
 * name: verify-tests
 * description: Run all tests after changes
 * whenToUse: After modifying code
 * ---
 * [技能内容 markdown]
 * </pre>
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final Path projectDir;
    private final List<Skill> skills = new ArrayList<>();

    public SkillLoader(Path projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * 扫描并加载所有技能文件
     */
    public List<Skill> loadAll() {
        skills.clear();

        // 0. 内置技能
        skills.addAll(BundledSkills.getAll());
        log.debug("Loaded {} bundled skills", BundledSkills.getAll().size());

        // 1. 用户级技能
        Path userSkillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");
        loadFromDirectory(userSkillsDir, "user");

        // 2. 项目级技能
        Path projectSkillsDir = projectDir.resolve(".claude").resolve("skills");
        loadFromDirectory(projectSkillsDir, "project");

        // 3. 命令目录（自动转换为技能）
        Path commandsDir = projectDir.resolve(".claude").resolve("commands");
        loadFromDirectory(commandsDir, "command");

        log.debug("Loaded {} skills in total", skills.size());
        return Collections.unmodifiableList(skills);
    }

    /**
     * 从指定目录加载 .md 技能文件
     */
    private void loadFromDirectory(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            Skill skill = parseSkillFile(p, source);
                            skills.add(skill);
                            log.debug("Loaded skill: {} [{}] from {}", skill.name(), source, p.getFileName());
                        } catch (IOException e) {
                            log.warn("Failed to load skill file: {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to scan skill directory: {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 解析单个技能文件，提取 frontmatter 和内容
     */
    private Skill parseSkillFile(Path path, String source) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8).strip();
        String fileName = path.getFileName().toString().replace(".md", "");

        // 尝试提取 YAML frontmatter
        String name = fileName;
        String description = "";
        String whenToUse = "";
        String content = raw;

        if (raw.startsWith("---")) {
            int endIdx = raw.indexOf("---", 3);
            if (endIdx > 0) {
                String frontmatter = raw.substring(3, endIdx).strip();
                content = raw.substring(endIdx + 3).strip();

                // 简单的 YAML 解析（key: value 格式）
                for (String line : frontmatter.split("\n")) {
                    line = line.strip();
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).strip();
                        String value = line.substring(colonIdx + 1).strip();
                        // 去掉引号
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        switch (key) {
                            case "name" -> name = value;
                            case "description" -> description = value;
                            case "whenToUse" -> whenToUse = value;
                        }
                    }
                }
            }
        }

        return new Skill(name, description, whenToUse, content, source, path);
    }

    /**
     * 获取已加载的技能列表
     */
    public List<Skill> getSkills() {
        return Collections.unmodifiableList(skills);
    }

    /**
     * 按名称查找技能
     */
    public Optional<Skill> findByName(String name) {
        return skills.stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * 构建技能上下文摘要（注入系统提示词）。
     * 支持预算控制，确保不超过上下文窗口的 1%。
     */
    public String buildSkillsSummary() {
        return buildSkillsSummary(8000); // Default 8K chars budget
    }

    /**
     * 构建技能上下文摘要（带预算控制）。
     * 对应 TS formatCommandsWithinBudget()。
     *
     * @param charBudget 最大字符预算
     */
    public String buildSkillsSummary(int charBudget) {
        if (skills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Available Skills\n\n");
        sb.append("Skills can be invoked by name using the Skill tool or by typing /<name>.\n\n");

        int budgetUsed = sb.length();
        int perEntryMax = 250; // Per-entry cap for cache efficiency

        for (Skill skill : skills) {
            StringBuilder entry = new StringBuilder();
            entry.append("- **").append(skill.name()).append("**");
            if (!skill.description().isEmpty()) {
                String desc = skill.description();
                if (desc.length() > perEntryMax - skill.name().length() - 10) {
                    desc = desc.substring(0, perEntryMax - skill.name().length() - 13) + "...";
                }
                entry.append(": ").append(desc);
            }
            if (!skill.whenToUse().isEmpty()) {
                entry.append(" (use when: ").append(skill.whenToUse()).append(")");
            }
            entry.append("\n");

            // Check budget
            if (budgetUsed + entry.length() > charBudget) {
                // Add truncation notice
                sb.append("- ... and ").append(skills.size() - skills.indexOf(skill))
                        .append(" more skills (use /skills to see all)\n");
                break;
            }

            sb.append(entry);
            budgetUsed += entry.length();
        }

        return sb.toString();
    }

    /**
     * 技能数据记录
     */
    public record Skill(String name, String description, String whenToUse,
                         String content, String source, Path filePath) {
    }
}
