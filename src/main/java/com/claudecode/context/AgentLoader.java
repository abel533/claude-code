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
 * Agent 定义加载器 —— 对应 claude-code/src/tools/AgentTool/loadAgentsDir.ts。
 * <p>
 * 从 .claude/agents/ 目录加载 Agent 定义文件（AGENT.md 或 .md）。
 * Agent 定义支持特殊的 frontmatter 字段（tools, maxTurns, memory, isolation 等）。
 * <p>
 * 目录结构：
 * <pre>
 * .claude/agents/
 * ├── reviewer/
 * │   └── AGENT.md        ← Agent 名 = "reviewer"
 * └── code-generator.md   ← Agent 名 = "code-generator"
 * </pre>
 */
public class AgentLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentLoader.class);

    private final Path projectDir;
    private final List<AgentDefinition> agents = new ArrayList<>();

    public AgentLoader(Path projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * 扫描并加载所有 Agent 定义
     */
    public List<AgentDefinition> loadAll() {
        agents.clear();

        // 1. 用户级 agents
        Path userAgentsDir = Path.of(System.getProperty("user.home"), ".claude", "agents");
        loadFromDirectory(userAgentsDir, "user");

        // 2. 项目级 agents
        Path projectAgentsDir = projectDir.resolve(".claude").resolve("agents");
        loadFromDirectory(projectAgentsDir, "project");

        log.debug("Loaded {} agent definitions in total", agents.size());
        return Collections.unmodifiableList(agents);
    }

    /**
     * 从目录加载 Agent 定义。
     * 支持两种格式：
     * - 目录格式: agent-name/AGENT.md
     * - 单文件格式: agent-name.md
     */
    private void loadFromDirectory(Path dir, String source) {
        if (!Files.isDirectory(dir)) return;

        try (var stream = Files.list(dir)) {
            stream.sorted().forEach(entry -> {
                try {
                    if (Files.isDirectory(entry)) {
                        // 目录格式: agent-name/AGENT.md
                        Path agentFile = entry.resolve("AGENT.md");
                        if (Files.isRegularFile(agentFile)) {
                            AgentDefinition agent = parseAgentFile(agentFile, source, entry.getFileName().toString());
                            agents.add(agent);
                            log.debug("Loaded agent: {} [{}] from {}/AGENT.md", agent.name(), source, entry.getFileName());
                        }
                    } else if (entry.toString().endsWith(".md")) {
                        // 单文件格式: agent-name.md
                        String name = entry.getFileName().toString().replace(".md", "");
                        AgentDefinition agent = parseAgentFile(entry, source, name);
                        agents.add(agent);
                        log.debug("Loaded agent: {} [{}] from {}", agent.name(), source, entry.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("Failed to load agent file: {}: {}", entry, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Failed to scan agents directory: {}: {}", dir, e.getMessage());
        }
    }

    /**
     * 解析 Agent 定义文件
     */
    @SuppressWarnings("unchecked")
    private AgentDefinition parseAgentFile(Path path, String source, String defaultName) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8).strip();

        String name = defaultName;
        String description = "";
        String content = raw;
        Map<String, Object> fm = Collections.emptyMap();

        // YAML frontmatter
        if (raw.startsWith("---")) {
            int endIdx = raw.indexOf("---", 3);
            if (endIdx > 0) {
                String fmRaw = raw.substring(3, endIdx).strip();
                content = raw.substring(endIdx + 3).strip();
                try {
                    Yaml yaml = new Yaml();
                    Object result = yaml.load(fmRaw);
                    if (result instanceof Map) {
                        fm = (Map<String, Object>) result;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse agent frontmatter in {}: {}", path, e.getMessage());
                }
            }
        }

        // 解析字段
        if (fm.containsKey("name")) name = fm.get("name").toString();
        if (fm.containsKey("description")) description = fm.get("description").toString();

        List<String> tools = getStringList(fm, "tools");
        List<String> disallowedTools = getStringList(fm, "disallowed-tools");
        int maxTurns = getInt(fm, "max-turns", 25);
        boolean memory = getBoolean(fm, "memory", false);
        String isolation = getString(fm, "isolation", "fork");
        boolean background = getBoolean(fm, "background", false);
        String model = getString(fm, "model", null);
        String effort = getString(fm, "effort", null);

        return new AgentDefinition(name, description, content, source, path,
                tools, disallowedTools, maxTurns, memory, isolation, background, model, effort);
    }

    public List<AgentDefinition> getAgents() {
        return Collections.unmodifiableList(agents);
    }

    public Optional<AgentDefinition> findByName(String name) {
        return agents.stream()
                .filter(a -> a.name().equalsIgnoreCase(name))
                .findFirst();
    }

    // ==================== 辅助方法 ====================

    private String getString(Map<String, Object> fm, String key, String defaultValue) {
        Object val = fm.get(key);
        return val != null ? val.toString().strip() : defaultValue;
    }

    private int getInt(Map<String, Object> fm, String key, int defaultValue) {
        Object val = fm.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val != null) {
            try { return Integer.parseInt(val.toString().strip()); }
            catch (NumberFormatException e) { /* ignore */ }
        }
        return defaultValue;
    }

    private boolean getBoolean(Map<String, Object> fm, String key, boolean defaultValue) {
        Object val = fm.get(key);
        if (val instanceof Boolean b) return b;
        if (val != null) {
            String s = val.toString().strip().toLowerCase();
            return "true".equals(s) || "yes".equals(s) || "1".equals(s);
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> fm, String key) {
        Object val = fm.get(key);
        if (val == null) return null;
        if (val instanceof List) {
            return ((List<Object>) val).stream().map(Object::toString).toList();
        }
        String s = val.toString().strip();
        if (s.isEmpty()) return null;
        return Arrays.stream(s.split(",")).map(String::strip).filter(v -> !v.isEmpty()).toList();
    }

    /**
     * Agent 定义数据记录
     */
    public record AgentDefinition(
            String name,
            String description,
            String content,
            String source,
            Path filePath,
            List<String> tools,
            List<String> disallowedTools,
            int maxTurns,
            boolean memory,
            String isolation,
            boolean background,
            String model,
            String effort
    ) {}
}
