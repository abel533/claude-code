package com.claudecode.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

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
    /** 已加载文件的规范路径集合，用于 symlink 去重 */
    private final Set<Path> loadedCanonicalPaths = new HashSet<>();

    public SkillLoader(Path projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * 扫描并加载所有技能文件
     */
    public List<Skill> loadAll() {
        skills.clear();
        loadedCanonicalPaths.clear();

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
            stream.filter(p -> Files.isDirectory(p) || Files.isSymbolicLink(p))
                    .sorted()
                    .forEach(subDir -> {
                        // Gitignore 过滤
                        if (isGitignored(subDir)) {
                            log.debug("Skipping gitignored skill directory: {}", subDir);
                            return;
                        }
                        Path skillFile = subDir.resolve("SKILL.md");
                        if (Files.isRegularFile(skillFile)) {
                            // Symlink 去重
                            if (!trackAndCheckDuplicate(skillFile)) {
                                log.debug("Skipping duplicate skill (symlink): {}", skillFile);
                                return;
                            }
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
        // Gitignore 过滤（对整个目录）
        if (!currentDir.equals(baseDir) && isGitignored(currentDir)) {
            log.debug("Skipping gitignored commands directory: {}", currentDir);
            return;
        }

        try (var stream = Files.list(currentDir)) {
            stream.sorted().forEach(entry -> {
                try {
                    if (Files.isDirectory(entry) || Files.isSymbolicLink(entry)) {
                        // 检查目录内是否有 SKILL.md（目录格式技能）
                        Path skillFile = entry.resolve("SKILL.md");
                        if (Files.isRegularFile(skillFile)) {
                            if (!trackAndCheckDuplicate(skillFile)) return;
                            String name = buildCommandName(entry, baseDir, true);
                            Skill skill = parseSkillFile(skillFile, source, name);
                            skills.add(skill);
                            log.debug("Loaded command skill: {} [{}] from {}/SKILL.md", name, source, entry.getFileName());
                        } else {
                            // 递归进入子目录
                            loadCommandsRecursive(entry, baseDir, source);
                        }
                    } else if (entry.toString().endsWith(".md")) {
                        if (!trackAndCheckDuplicate(entry)) return;
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
     * 使用 SnakeYAML 解析 frontmatter，支持所有 YAML 数据类型。
     * 当 overrideName 非 null 时，用它作为默认技能名（目录名或命令名）。
     */
    private Skill parseSkillFile(Path path, String source, String overrideName) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8).strip();
        String fileName = path.getFileName().toString().replace(".md", "");

        // 默认名称：优先使用 overrideName（目录名/命名空间名），否则用文件名
        String name = overrideName != null ? overrideName : fileName;
        String content = raw;
        Map<String, Object> fm = Collections.emptyMap();

        // 提取 YAML frontmatter
        if (raw.startsWith("---")) {
            int endIdx = raw.indexOf("---", 3);
            if (endIdx > 0) {
                String fmRaw = raw.substring(3, endIdx).strip();
                content = raw.substring(endIdx + 3).strip();
                fm = parseFrontmatterYaml(fmRaw, path);
            }
        }

        // 解析所有 frontmatter 字段
        String displayName = fmString(fm, "display-name", fmString(fm, "name", null));
        if (fm.containsKey("name") && displayName != null && !displayName.isBlank()) {
            // display-name 优先于 name（与 TS 一致）
        }
        String fmName = fmString(fm, "name", null);
        if (fmName != null && !fmName.isBlank() && overrideName == null) {
            name = fmName;
        }

        String description = fmString(fm, "description", "");
        // 描述 fallback：从 markdown 第一行提取
        if (description.isEmpty() && !content.isEmpty()) {
            description = extractDescriptionFromMarkdown(content);
        }

        String whenToUse = fmString(fm, "when-to-use", fmString(fm, "whenToUse", ""));
        List<String> allowedTools = fmStringList(fm, "allowed-tools");
        List<String> disallowedTools = fmStringList(fm, "disallowed-tools");
        if (disallowedTools == null) {
            // TS 也支持 disable-model-invocation 作为别名
            disallowedTools = fmStringList(fm, "disable-model-invocation");
        }
        String model = fmString(fm, "model", null);
        String effort = fmString(fm, "effort", null);
        boolean userInvocable = fmBoolean(fm, "user-invocable", true);
        String context = fmString(fm, "context", "inline");
        String agent = fmString(fm, "agent", null);
        String shell = fmString(fm, "shell", null);
        // shell 校验（仅 bash / powershell）
        if (shell != null && !"bash".equals(shell) && !"powershell".equals(shell)) {
            log.warn("Invalid shell '{}' in {}, ignoring (use 'bash' or 'powershell')", shell, path);
            shell = null;
        }
        List<String> paths = fmStringList(fm, "paths");
        String argumentHint = fmString(fm, "argument-hint", null);
        List<String> arguments = fmStringList(fm, "arguments");
        String version = fmString(fm, "version", null);

        return new Skill(name, displayName, description, whenToUse, content, source, path,
                allowedTools, disallowedTools, model, effort, userInvocable,
                context, agent, shell, paths, argumentHint, arguments, version);
    }

    // ==================== Frontmatter YAML 解析工具方法 ====================

    /**
     * 使用 SnakeYAML 解析 frontmatter 文本。
     * 处理特殊字符自动引号包裹（对应 TS quoteProblematicValues）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatterYaml(String yamlText, Path path) {
        Yaml yaml = new Yaml();
        try {
            Object result = yaml.load(yamlText);
            if (result instanceof Map) {
                return (Map<String, Object>) result;
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            // 首次解析失败 → 尝试自动引号处理后重试（对应 TS quoteProblematicValues）
            log.debug("YAML parse failed for {}, retrying with quoted values: {}", path, e.getMessage());
            try {
                String quoted = quoteProblematicValues(yamlText);
                Object result = yaml.load(quoted);
                if (result instanceof Map) {
                    return (Map<String, Object>) result;
                }
            } catch (Exception e2) {
                log.warn("Failed to parse frontmatter in {}: {}", path, e2.getMessage());
            }
            return Collections.emptyMap();
        }
    }

    /**
     * 对应 TS quoteProblematicValues()：为包含 YAML 特殊字符的值自动加引号。
     * 处理 glob 模式、特殊符号等会导致 YAML 解析失败的值。
     */
    private String quoteProblematicValues(String yamlText) {
        String[] specialChars = {"{", "}", "[", "]", "*", " &", "#", "!", "|", ">", "%", "@", "\"", "`"};
        StringBuilder result = new StringBuilder();
        for (String line : yamlText.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0 && colonIdx < line.length() - 1) {
                String key = line.substring(0, colonIdx + 1);
                String value = line.substring(colonIdx + 1).strip();
                // 如果值未被引号包裹且包含特殊字符
                if (!value.isEmpty() && !value.startsWith("\"") && !value.startsWith("'")) {
                    boolean hasSpecial = false;
                    for (String sc : specialChars) {
                        if (value.contains(sc)) {
                            hasSpecial = true;
                            break;
                        }
                    }
                    if (hasSpecial) {
                        value = "\"" + value.replace("\"", "\\\"") + "\"";
                        result.append(key).append(" ").append(value).append("\n");
                        continue;
                    }
                }
            }
            result.append(line).append("\n");
        }
        return result.toString();
    }

    /** 从 frontmatter map 中安全获取字符串值 */
    private String fmString(Map<String, Object> fm, String key, String defaultValue) {
        Object val = fm.get(key);
        if (val == null) return defaultValue;
        return val.toString().strip();
    }

    /** 从 frontmatter map 中获取字符串列表（支持逗号分隔字符串或 YAML 数组） */
    @SuppressWarnings("unchecked")
    private List<String> fmStringList(Map<String, Object> fm, String key) {
        Object val = fm.get(key);
        if (val == null) return null;
        if (val instanceof List) {
            return ((List<Object>) val).stream()
                    .map(Object::toString)
                    .map(String::strip)
                    .toList();
        }
        // 逗号分隔字符串
        String s = val.toString().strip();
        if (s.isEmpty()) return null;
        return Arrays.stream(s.split(","))
                .map(String::strip)
                .filter(v -> !v.isEmpty())
                .toList();
    }

    /** 从 frontmatter map 中获取布尔值 */
    private boolean fmBoolean(Map<String, Object> fm, String key, boolean defaultValue) {
        Object val = fm.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean b) return b;
        String s = val.toString().strip().toLowerCase();
        return "true".equals(s) || "yes".equals(s) || "1".equals(s);
    }

    /**
     * 从 Markdown 内容提取描述（第一个非空行，去掉 # 前缀）。
     * 对应 TS extractDescriptionFromMarkdown()。
     */
    private String extractDescriptionFromMarkdown(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                // 去掉 markdown 标题前缀
                if (trimmed.startsWith("#")) {
                    trimmed = trimmed.replaceFirst("^#+\\s*", "");
                }
                return trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed;
            }
        }
        return "";
    }

    // ==================== Gitignore 过滤 & Symlink 去重 ====================

    /**
     * 检查路径是否被 gitignore 忽略。
     * 对应 TS isPathGitignored()，使用 git check-ignore 命令。
     */
    private boolean isGitignored(Path path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "check-ignore", "-q", path.toString());
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            // exit code 0 = ignored, 1 = not ignored, 128 = not a git repo
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("git check-ignore failed for {}: {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * 检查文件是否已通过 symlink 或其他路径加载过（去重）。
     * 对应 TS deduplication by resolved canonical path。
     *
     * @return true 如果是新文件（未加载过），false 如果已加载过
     */
    private boolean trackAndCheckDuplicate(Path path) {
        try {
            Path canonical = path.toRealPath();
            return loadedCanonicalPaths.add(canonical);
        } catch (IOException e) {
            // toRealPath 失败（如断开的 symlink）→ 用原始路径
            return loadedCanonicalPaths.add(path.toAbsolutePath().normalize());
        }
    }

    /**
     * 获取已加载的技能列表
     */
    public List<Skill> getSkills() {
        return Collections.unmodifiableList(skills);
    }

    /**
     * 按名称查找技能（精确匹配，不区分大小写）
     */
    public Optional<Skill> findByName(String name) {
        return skills.stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * 获取非条件技能（始终激活的技能）
     */
    public List<Skill> getUnconditionalSkills() {
        return skills.stream()
                .filter(s -> !s.isConditional())
                .toList();
    }

    /**
     * 获取匹配指定文件路径的条件技能。
     * 对应 TS discoverSkillDirsForPaths()。
     *
     * @param filePaths 当前编辑的文件路径列表
     * @return 匹配的条件技能
     */
    public List<Skill> getConditionalSkillsForPaths(List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return List.of();

        return skills.stream()
                .filter(Skill::isConditional)
                .filter(skill -> {
                    for (String pattern : skill.paths()) {
                        for (String filePath : filePaths) {
                            if (matchGlob(pattern, filePath)) {
                                return true;
                            }
                        }
                    }
                    return false;
                })
                .toList();
    }

    /**
     * 简单 glob 匹配（支持 * 和 **）
     */
    private boolean matchGlob(String pattern, String path) {
        // 将 glob 转换为正则
        String regex = pattern
                .replace(".", "\\.")
                .replace("**/", "(.+/)?")
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("?", "[^/]");
        try {
            return path.matches(regex) || path.replace('\\', '/').matches(regex);
        } catch (Exception e) {
            return false;
        }
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
     * 技能数据记录 —— 对应 TS Command 类型中与 Skill 相关的字段。
     *
     * @param name            技能名称（目录名或 frontmatter name）
     * @param displayName     显示名称（frontmatter display-name，可为 null）
     * @param description     技能描述
     * @param whenToUse       何时使用提示
     * @param content         Markdown 内容体
     * @param source          来源（user/project/command/bundled）
     * @param filePath        文件路径
     * @param allowedTools    允许使用的工具列表（null = 不限制）
     * @param disallowedTools 禁止使用的工具列表（null = 不限制）
     * @param model           模型覆盖（null = 使用默认，"inherit" = 继承父级）
     * @param effort          Effort 级别（low/medium/high/max 或整数）
     * @param userInvocable   是否可由用户通过 /name 调用（默认 true）
     * @param context         执行上下文（"inline" = 当前上下文, "fork" = 子 Agent）
     * @param agent           子 Agent 类型（当 context=fork 时使用）
     * @param shell           Shell 类型（"bash" / "powershell"，可为 null）
     * @param paths           条件激活路径（glob 模式列表，null = 始终激活）
     * @param argumentHint    参数提示文本
     * @param arguments       参数名列表
     * @param version         技能版本
     */
    public record Skill(
            String name,
            String displayName,
            String description,
            String whenToUse,
            String content,
            String source,
            Path filePath,
            List<String> allowedTools,
            List<String> disallowedTools,
            String model,
            String effort,
            boolean userInvocable,
            String context,
            String agent,
            String shell,
            List<String> paths,
            String argumentHint,
            List<String> arguments,
            String version
    ) {
        /** 便捷构造（向后兼容旧代码） */
        public Skill(String name, String description, String whenToUse,
                     String content, String source, Path filePath) {
            this(name, null, description, whenToUse, content, source, filePath,
                    null, null, null, null, true, "inline", null, null, null, null, null, null);
        }

        /** 是否为条件技能（仅在匹配路径时激活） */
        public boolean isConditional() {
            return paths != null && !paths.isEmpty();
        }

        /** 是否应在子 Agent 中执行 */
        public boolean isForked() {
            return "fork".equalsIgnoreCase(context);
        }

        /** 用户可见名称（displayName 优先，否则 name） */
        public String userFacingName() {
            return displayName != null && !displayName.isBlank() ? displayName : name;
        }
    }
}
