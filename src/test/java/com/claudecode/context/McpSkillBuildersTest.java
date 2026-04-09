package com.claudecode.context;

import com.claudecode.context.SkillLoader.Skill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for McpSkillBuilders registry.
 */
class McpSkillBuildersTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        McpSkillBuilders.clearAll();
    }

    @Test
    void register_and_getAllMcpSkills() {
        Skill s1 = new Skill("mcp-lint", "Lint code", "", "content1", "mcp", null);
        Skill s2 = new Skill("mcp-format", "Format code", "", "content2", "mcp", null);

        McpSkillBuilders.register("server1", () -> List.of(s1));
        McpSkillBuilders.register("server2", () -> List.of(s2));

        List<Skill> result = McpSkillBuilders.getAllMcpSkills();
        assertEquals(2, result.size());
    }

    @Test
    void getAllMcpSkills_cachesResult() {
        McpSkillBuilders.register("server", () -> List.of(
                new Skill("mcp-test", "test", "", "content", "mcp", null)
        ));

        List<Skill> first = McpSkillBuilders.getAllMcpSkills();
        List<Skill> second = McpSkillBuilders.getAllMcpSkills();
        assertSame(first, second); // Same cached instance
    }

    @Test
    void register_invalidatesCache() {
        McpSkillBuilders.register("server1", () -> List.of(
                new Skill("s1", "test", "", "content", "mcp", null)
        ));
        McpSkillBuilders.getAllMcpSkills(); // populate cache

        McpSkillBuilders.register("server2", () -> List.of(
                new Skill("s2", "test2", "", "content2", "mcp", null)
        ));
        List<Skill> result = McpSkillBuilders.getAllMcpSkills();
        assertEquals(2, result.size()); // Cache was invalidated
    }

    @Test
    void unregister_removesServer() {
        McpSkillBuilders.register("server", () -> List.of(
                new Skill("s1", "test", "", "content", "mcp", null)
        ));
        McpSkillBuilders.unregister("server");
        assertTrue(McpSkillBuilders.getAllMcpSkills().isEmpty());
    }

    @Test
    void getRegisteredServers() {
        McpSkillBuilders.register("server1", List::of);
        McpSkillBuilders.register("server2", List::of);
        assertEquals(2, McpSkillBuilders.getRegisteredServers().size());
    }

    @Test
    void hasBuilders_returnsCorrectly() {
        assertFalse(McpSkillBuilders.hasBuilders());
        McpSkillBuilders.register("s", List::of);
        assertTrue(McpSkillBuilders.hasBuilders());
    }

    @Test
    void builderException_isHandledGracefully() {
        McpSkillBuilders.register("bad-server", () -> { throw new RuntimeException("fail"); });
        McpSkillBuilders.register("good-server", () -> List.of(
                new Skill("ok", "ok", "", "ok", "mcp", null)
        ));

        List<Skill> result = McpSkillBuilders.getAllMcpSkills();
        assertEquals(1, result.size());
        assertEquals("ok", result.getFirst().name());
    }
}
