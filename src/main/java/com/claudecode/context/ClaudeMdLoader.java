package com.claudecode.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLAUDE.md 加载器 —— 对应 claude-code/src/context.ts 中的 CLAUDE.md 加载逻辑。
 * <p>
 * 按优先级从低到高加载：
 * <ol>
 *   <li>系统级: /etc/claude-code/CLAUDE.md (Unix) 或默认模板</li>
 *   <li>用户级: ~/.claude/CLAUDE.md</li>
 *   <li>项目级: ./CLAUDE.md 或 ./.claude/CLAUDE.md</li>
 *   <li>本地级: ./CLAUDE.local.md</li>
 * </ol>
 */
public class ClaudeMdLoader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdLoader.class);

    private final Path projectDir;

    public ClaudeMdLoader(Path projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * 加载并合并所有 CLAUDE.md 内容。
     */
    public String load() {
        List<String> sections = new ArrayList<>();

        // 1. 用户级
        Path userMd = Path.of(System.getProperty("user.home"), ".claude", "CLAUDE.md");
        loadFile(userMd, "user").ifPresent(sections::add);

        // 2. 项目级 —— 优先检查 .claude/CLAUDE.md，然后 CLAUDE.md
        Path projectClaudeDir = projectDir.resolve(".claude").resolve("CLAUDE.md");
        Path projectRoot = projectDir.resolve("CLAUDE.md");
        if (Files.exists(projectClaudeDir)) {
            loadFile(projectClaudeDir, "project").ifPresent(sections::add);
        } else {
            loadFile(projectRoot, "project").ifPresent(sections::add);
        }

        // 3. 本地级
        Path localMd = projectDir.resolve("CLAUDE.local.md");
        loadFile(localMd, "local").ifPresent(sections::add);

        // 4. 加载 .claude/rules/*.md 目录
        Path rulesDir = projectDir.resolve(".claude").resolve("rules");
        if (Files.isDirectory(rulesDir)) {
            try (var stream = Files.list(rulesDir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .forEach(p -> loadFile(p, "rule").ifPresent(sections::add));
            } catch (IOException e) {
                log.debug("Failed to load rules directory: {}", e.getMessage());
            }
        }

        if (sections.isEmpty()) {
            return "";
        }

        return String.join("\n\n---\n\n", sections);
    }

    private java.util.Optional<String> loadFile(Path path, String level) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (!content.isEmpty()) {
                log.debug("Loaded {} level CLAUDE.md: {}", level, path);
                return java.util.Optional.of(content);
            }
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", path, e.getMessage());
        }
        return java.util.Optional.empty();
    }
}
