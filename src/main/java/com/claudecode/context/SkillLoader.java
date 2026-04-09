package com.claudecode.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Skills 技能加载器 —— 对应 claude-code/src/skills/loadSkillsDir.ts。
 * <p>
 * 从多个来源扫描和加载技能：
 * <ol>
 *   <li>用户级: ~/.claude/skills/ — 目录格式 (skill-name/SKILL.md)</li>
 *   <li>项目级: ./.claude/skills/ — 目录格式 (skill-name/SKILL.md)</li>
 *   <li>命令目录: ./.claude/commands/ — 目录格式 (SKILL.md) 或单文件 (.md)，支持递归子目录</li>
 * </ol>
 * <p>
 * /skills/ 目录仅支持目录格式（与原版 claude-code 一致）：
 * <pre>
 * .claude/skills/
 * └── verify-tests/
 *     └── SKILL.md      ← 技能名 = "verify-tests"
 * </pre>
 * <p>
 * /commands/ 目录支持两种格式：
 * <pre>
 * .claude/commands/
 * ├── my-cmd.md          ← 命令名 = "my-cmd"（单文件格式）
 * ├── my-skill/
 * │   └── SKILL.md       ← 命令名 = "my-skill"（目录格式，优先）
 * └── sub/
 *     └── nested-cmd.md  ← 命令名 = "sub:nested-cmd"（命名空间）
 * </pre>
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

        // 1. 用户级技能（目录格式: skill-name/SKILL.md）
        Path userSkillsDir = Path.of(System.getProperty("user.home"), ".claude", "skills");
        loadFromSkillsDirectory(userSkillsDir, "user");

        // 2. 项目级技能（目录格式: skill-name/SKILL.md）
        Path projectSkillsDir = projectDir.resolve(".claude").resolve("skills");
        loadFromSkillsDirectory(projectSkillsDir, "project");

        // 3. 命令目录（支持目录格式 + 单文件格式 + 递归子目录）
        Path commandsDir = projectDir.resolve(".claude").resolve("commands");
        loadFromCommandsDirectory(commandsDir, "command");

        log.debug("Loaded {} skills in total", skills.size());
        return Collections.unmodifiableList(skills);
    }

    /**
     * 从 skills 目录加载技能 —— 仅支持目录格式: skill-name/SKILL.md
     * <p>
     * 对应 TS loadSkillsFromSkillsDir():
     * - 每个技能是一个子目录，内含 SKILL.md
     * - 单独的 .md 文件不被加载（与原版一致）
     * - 目录名即技能名
     */
    private void loadFromSkillsDirectory(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .forEach(subDir -> {
                        Path skillFile = subDir.resolve("SKILL.md");
                        if (Files.isRegularFile(skillFile)) {
                            try {
                                Skill skill = parseSkillFile(skillFile, source, subDir.getFileName().toString());
                                skills.add(skill);
                                log.debug("Loaded skill: {} [{}] from {}/SKILL.md", skill.name(), source, subDir.getFileName());
                            } catch (IOException e) {
                                log.warn("Failed to load skill file: {}: {}", skillFile, e.getMessage());
                            }
                        } else {
                            log.debug("Skipping skill directory without SKILL.md: {}", subDir.getFileName());
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to scan skills directory: {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 从 commands 目录加载技能 —— 支持两种格式:
     * <ol>
     *   <li>目录格式: command-name/SKILL.md（优先）</li>
     *   <li>单文件格式: command-name.md</li>
     *   <li>递归子目录: sub/command-name.md → 名称 "sub:command-name"</li>
     * </ol>
     * <p>
     * 对应 TS loadSkillsFromCommandsDir()
     */
    private void loadFromCommandsDirectory(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }

        loadCommandsRecursive(dir, dir, source);
    }

    /**
     * 递归加载 commands 目录
     */
    private void loadCommandsRecursive(Path currentDir, Path baseDir, String source) {
        try (var stream = Files.list(currentDir)) {
            stream.sorted().forEach(entry -> {
                try {
                    if (Files.isDirectory(entry)) {
                        // 检查目录内是否有 SKILL.md（目录格式技能）
                        Path skillFile = entry.resolve("SKILL.md");
                        if (Files.isRegularFile(skillFile)) {
                            String name = buildCommandName(entry, baseDir, true);
                            Skill skill = parseSkillFile(skillFile, source, name);
                            skills.add(skill);
                            log.debug("Loaded command skill: {} [{}] from {}/SKILL.md", name, source, entry.getFileName());
                        } else {
                            // 递归进入子目录
                            loadCommandsRecursive(entry, baseDir, source);
                        }
                    } else if (entry.toString().endsWith(".md")) {
                        // 单文件格式
                        String name = buildCommandName(entry, baseDir, false);
                        Skill skill = parseSkillFile(entry, source, name);
                        skills.add(skill);
                        log.debug("Loaded command: {} [{}] from {}", name, source, entry.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("Failed to load command file: {}: {}", entry, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Failed to scan commands directory: {}: {}", currentDir, e.getMessage());
        }
    }

    /**
     * 构建命令名称，支持命名空间（子目录用 : 分隔）。
     * <p>
     * 例: baseDir=commands, entry=commands/sub/my-cmd.md → "sub:my-cmd"
     *     baseDir=commands, entry=commands/my-skill/SKILL.md (isDir=true) → "my-skill"
     */
    private String buildCommandName(Path entry, Path baseDir, boolean isDirectory) {
        Path relative;
        if (isDirectory) {
            // 目录格式：取目录名相对于 baseDir 的路径
            relative = baseDir.relativize(entry);
        } else {
            // 文件格式：取文件路径（去掉 .md）相对于 baseDir
            relative = baseDir.relativize(entry);
        }

        // 用 : 替换路径分隔符，去掉 .md 后缀
        String name = relative.toString()
                .replace('\\', ':')
                .replace('/', ':');
        if (!isDirectory && name.endsWith(".md")) {
            name = name.substring(0, name.length() - 3);
        }
        return name;
    }

    /**
     * 解析单个技能文件，提取 frontmatter 和内容。
     * 当 overrideName 非 null 时，用它作为默认技能名（目录名或命令名）。
     */
    private Skill parseSkillFile(Path path, String source, String overrideName) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8).strip();
        String fileName = path.getFileName().toString().replace(".md", "");

        // 默认名称：优先使用 overrideName（目录名/命名空间名），否则用文件名
        String name = overrideName != null ? overrideName : fileName;
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
