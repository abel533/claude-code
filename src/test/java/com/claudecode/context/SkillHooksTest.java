package com.claudecode.context;

import com.claudecode.context.SkillLoader.Skill;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Skill record hooks field and computed methods.
 */
class SkillHooksTest {

    @Test
    void skill_withHooks_hasHooksTrue() {
        Map<String, Object> hooks = Map.of(
                "PreToolUse", Map.of("command", "echo pre"),
                "PostToolUse", Map.of("command", "echo post")
        );
        Skill skill = new Skill("test", null, "desc", false, "",
                "content", "user", "skills", null, null,
                null, null, false, null, null,
                true, false, false, "inline", null, null,
                null, null, null, null, 7, "running", hooks);
        assertTrue(skill.hasHooks());
    }

    @Test
    void skill_withoutHooks_hasHooksFalse() {
        Skill skill = new Skill("test", null, "desc", false, "",
                "content", "user", "skills", null, null,
                null, null, false, null, null,
                true, false, false, "inline", null, null,
                null, null, null, null, 7, "running", null);
        assertFalse(skill.hasHooks());
    }

    @Test
    void skill_emptyHooks_hasHooksFalse() {
        Skill skill = new Skill("test", null, "desc", false, "",
                "content", "user", "skills", null, null,
                null, null, false, null, null,
                true, false, false, "inline", null, null,
                null, null, null, null, 7, "running", Map.of());
        assertFalse(skill.hasHooks());
    }

    @Test
    void skill_backwardCompat_28arg_noHooks() {
        Skill skill = new Skill("test", null, "desc", false, "",
                "content", "user", "skills", null, null,
                null, null, false, null, null,
                true, false, false, "inline", null, null,
                null, null, null, null, 7, "running");
        assertFalse(skill.hasHooks());
        assertNull(skill.hooks());
    }

    @Test
    void skill_backwardCompat_19arg() {
        Skill skill = new Skill("test", null, "desc", "",
                "content", "user", null,
                null, null, null, null, true,
                "inline", null, null, null, null, null, null);
        assertFalse(skill.hasHooks());
    }

    @Test
    void skill_backwardCompat_6arg() {
        Skill skill = new Skill("test", "desc", "", "content", "bundled", null);
        assertFalse(skill.hasHooks());
    }

    @Test
    void skill_isMcp() {
        Skill mcp1 = new Skill("test", "desc", "", "content", "mcp", null);
        assertTrue(mcp1.isMcp());

        Skill mcp2 = new Skill("test", null, "desc", false, "",
                "content", "user", "mcp", null, null,
                null, null, false, null, null,
                true, false, false, "inline", null, null,
                null, null, null, null, 7, "running", null);
        assertTrue(mcp2.isMcp());

        Skill notMcp = new Skill("test", "desc", "", "content", "user", null);
        assertFalse(notMcp.isMcp());
    }
}
