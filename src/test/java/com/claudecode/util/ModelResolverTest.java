package com.claudecode.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModelResolver utility.
 */
class ModelResolverTest {

    @Test
    void resolveSkillModelOverride_inherit_returnsNull() {
        assertNull(ModelResolver.resolveSkillModelOverride("inherit"));
        assertNull(ModelResolver.resolveSkillModelOverride("INHERIT"));
        assertNull(ModelResolver.resolveSkillModelOverride("Inherit"));
    }

    @Test
    void resolveSkillModelOverride_null_returnsNull() {
        assertNull(ModelResolver.resolveSkillModelOverride(null));
        assertNull(ModelResolver.resolveSkillModelOverride(""));
        assertNull(ModelResolver.resolveSkillModelOverride("  "));
    }

    @Test
    void resolveSkillModelOverride_aliases() {
        assertEquals("claude-sonnet-4-20250514", ModelResolver.resolveSkillModelOverride("sonnet"));
        assertEquals("claude-sonnet-4-20250514", ModelResolver.resolveSkillModelOverride("Sonnet"));
        assertEquals("claude-opus-4-20250514", ModelResolver.resolveSkillModelOverride("opus"));
        assertEquals("claude-3-haiku-20240307", ModelResolver.resolveSkillModelOverride("haiku"));
    }

    @Test
    void resolveSkillModelOverride_versionedAliases() {
        assertEquals("claude-sonnet-4-20250514", ModelResolver.resolveSkillModelOverride("sonnet-4"));
        assertEquals("claude-opus-4-20250514", ModelResolver.resolveSkillModelOverride("opus-4"));
        assertEquals("claude-3-haiku-20240307", ModelResolver.resolveSkillModelOverride("haiku-3"));
        assertEquals("claude-3-5-sonnet-20241022", ModelResolver.resolveSkillModelOverride("sonnet-3.5"));
    }

    @Test
    void resolveSkillModelOverride_fullModelIds_passThrough() {
        assertEquals("claude-sonnet-4-20250514", ModelResolver.resolveSkillModelOverride("claude-sonnet-4-20250514"));
        assertEquals("gpt-4o", ModelResolver.resolveSkillModelOverride("gpt-4o"));
    }

    @Test
    void resolveSkillModelOverride_openAiAliases() {
        assertEquals("gpt-4", ModelResolver.resolveSkillModelOverride("gpt-4"));
        assertEquals("gpt-4o", ModelResolver.resolveSkillModelOverride("gpt-4o"));
        assertEquals("o3-mini", ModelResolver.resolveSkillModelOverride("o3-mini"));
    }

    @Test
    void isKnownAlias_works() {
        assertTrue(ModelResolver.isKnownAlias("sonnet"));
        assertTrue(ModelResolver.isKnownAlias("haiku"));
        assertTrue(ModelResolver.isKnownAlias("opus"));
        assertFalse(ModelResolver.isKnownAlias("unknown-model"));
        assertFalse(ModelResolver.isKnownAlias(null));
    }
}
