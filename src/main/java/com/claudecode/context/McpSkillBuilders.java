package com.claudecode.context;

import com.claudecode.context.SkillLoader.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * MCP Skill Builders 注册表 —— 对应 claude-code/src/skills/mcpSkillBuilders.ts。
 * <p>
 * 允许 MCP 服务器注册技能构建器，将 MCP prompts 转换为 Skills。
 * 使用注册表模式打破 MCP ↔ Skills 循环依赖。
 * <p>
 * 用法:
 * <pre>
 * // 在 MCP 模块中注册
 * McpSkillBuilders.register("my-server", () -> List.of(
 *     new Skill("mcp-lint", "Lint code", ...)
 * ));
 *
 * // 在 SkillLoader 中获取
 * List&lt;Skill&gt; mcpSkills = McpSkillBuilders.getAllMcpSkills();
 * </pre>
 */
public final class McpSkillBuilders {

    private static final Logger log = LoggerFactory.getLogger(McpSkillBuilders.class);

    /** Registry of MCP skill builders keyed by server name */
    private static final Map<String, Supplier<List<Skill>>> builders = new ConcurrentHashMap<>();

    /** Cache of built skills (invalidated when builders change) */
    private static volatile List<Skill> cachedSkills = null;

    private McpSkillBuilders() {}

    /**
     * Register an MCP skill builder for a given server.
     * Corresponds to TS registerMCPSkillBuilders().
     *
     * @param serverName unique MCP server identifier
     * @param builder    supplier that produces skills from MCP prompts
     */
    public static void register(String serverName, Supplier<List<Skill>> builder) {
        builders.put(serverName, builder);
        cachedSkills = null; // Invalidate cache
        log.debug("Registered MCP skill builder for server: {}", serverName);
    }

    /**
     * Unregister an MCP skill builder.
     */
    public static void unregister(String serverName) {
        builders.remove(serverName);
        cachedSkills = null;
        log.debug("Unregistered MCP skill builder for server: {}", serverName);
    }

    /**
     * Get all MCP skills from all registered builders.
     * Corresponds to TS getMCPSkillBuilders() result aggregation.
     *
     * @return combined list of skills from all MCP servers
     */
    public static List<Skill> getAllMcpSkills() {
        List<Skill> cached = cachedSkills;
        if (cached != null) return cached;

        List<Skill> result = new ArrayList<>();
        for (var entry : builders.entrySet()) {
            try {
                List<Skill> skills = entry.getValue().get();
                if (skills != null) {
                    result.addAll(skills);
                }
            } catch (Exception e) {
                log.warn("Failed to build MCP skills from server '{}': {}", entry.getKey(), e.getMessage());
            }
        }

        cachedSkills = Collections.unmodifiableList(result);
        return cachedSkills;
    }

    /**
     * Get registered server names.
     */
    public static Set<String> getRegisteredServers() {
        return Collections.unmodifiableSet(builders.keySet());
    }

    /**
     * Clear all registered builders and cache.
     */
    public static void clearAll() {
        builders.clear();
        cachedSkills = null;
    }

    /**
     * Invalidate the cache (force rebuild on next access).
     */
    public static void invalidateCache() {
        cachedSkills = null;
    }

    /**
     * Check if any builders are registered.
     */
    public static boolean hasBuilders() {
        return !builders.isEmpty();
    }
}
