package com.claudecode.context;

import com.claudecode.context.SkillLoader.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillFilters utility class.
 */
class SkillFiltersTest {

    private Skill makeSkill(String name, String source, String loadedFrom,
                            boolean disableModelInvocation, boolean hasUserDesc,
                            String whenToUse, boolean userInvocable) {
        return new Skill(name, null, "desc", hasUserDesc, whenToUse,
                "content", source, loadedFrom, null, null,
                null, null, disableModelInvocation, null, null,
                userInvocable, false, false, "inline", null, null,
                null, null, null, null, 7, "running", null);
    }

    @Test
    void getSkillToolCommands_filtersByLoadedFrom() {
        List<Skill> skills = List.of(
                makeSkill("bundled-skill", "bundled", "bundled", false, false, "", true),
                makeSkill("skills-skill", "user", "skills", false, false, "", true),
                makeSkill("commands-skill", "command", "commands_DEPRECATED", false, false, "", true),
                makeSkill("plugin-skill", "plugin", "plugin", false, false, "", true)
        );

        List<Skill> result = SkillFilters.getSkillToolCommands(skills);
        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(s -> s.name().equals("bundled-skill")));
        assertTrue(result.stream().anyMatch(s -> s.name().equals("skills-skill")));
        assertTrue(result.stream().anyMatch(s -> s.name().equals("commands-skill")));
    }

    @Test
    void getSkillToolCommands_includesWithUserDescription() {
        Skill pluginWithDesc = makeSkill("plugin-desc", "plugin", "plugin", false, true, "", true);
        List<Skill> result = SkillFilters.getSkillToolCommands(List.of(pluginWithDesc));
        assertEquals(1, result.size());
    }

    @Test
    void getSkillToolCommands_includesWithWhenToUse() {
        Skill pluginWithWhen = makeSkill("plugin-when", "plugin", "plugin", false, false, "When testing", true);
        List<Skill> result = SkillFilters.getSkillToolCommands(List.of(pluginWithWhen));
        assertEquals(1, result.size());
    }

    @Test
    void getSkillToolCommands_excludesDisableModelInvocation() {
        Skill disabled = makeSkill("no-model", "bundled", "bundled", true, false, "", true);
        List<Skill> result = SkillFilters.getSkillToolCommands(List.of(disabled));
        assertEquals(0, result.size());
    }

    @Test
    void getSkillToolCommands_excludesHidden() {
        Skill hidden = makeSkill("hidden", "bundled", "bundled", false, false, "", false);
        List<Skill> result = SkillFilters.getSkillToolCommands(List.of(hidden));
        assertEquals(0, result.size());
    }

    @Test
    void getSlashCommandToolSkills_includesSkillsPluginBundled() {
        List<Skill> skills = List.of(
                makeSkill("s1", "user", "skills", false, true, "", true),
                makeSkill("s2", "plugin", "plugin", false, true, "", true),
                makeSkill("s3", "bundled", "bundled", false, true, "", true),
                makeSkill("s4", "command", "commands_DEPRECATED", false, true, "", true)
        );

        List<Skill> result = SkillFilters.getSlashCommandToolSkills(skills);
        assertEquals(3, result.size());
        assertFalse(result.stream().anyMatch(s -> s.name().equals("s4")));
    }

    @Test
    void getSlashCommandToolSkills_includesDisableModelInvocation() {
        Skill userOnly = makeSkill("user-only", "command", "commands_DEPRECATED", true, true, "", true);
        List<Skill> result = SkillFilters.getSlashCommandToolSkills(List.of(userOnly));
        assertEquals(1, result.size());
    }

    @Test
    void formatDescriptionWithSource_bundledNoTag() {
        Skill bundled = makeSkill("test", "bundled", "bundled", false, true, "", true);
        assertEquals("desc", SkillFilters.formatDescriptionWithSource(bundled));
    }

    @Test
    void formatDescriptionWithSource_pluginTag() {
        Skill plugin = makeSkill("test", "plugin", "plugin", false, true, "", true);
        assertEquals("desc [plugin]", SkillFilters.formatDescriptionWithSource(plugin));
    }

    @Test
    void formatDescriptionWithSource_managedTag() {
        Skill managed = makeSkill("test", "policySettings", "managed", false, true, "", true);
        assertEquals("desc [managed]", SkillFilters.formatDescriptionWithSource(managed));
    }

    @Test
    void findCommand_exactAndCaseInsensitive() {
        Skill s = makeSkill("my-skill", "user", "skills", false, true, "", true);
        assertTrue(SkillFilters.findCommand(List.of(s), "my-skill").isPresent());
        assertTrue(SkillFilters.findCommand(List.of(s), "MY-SKILL").isPresent());
        assertFalse(SkillFilters.findCommand(List.of(s), "other").isPresent());
    }

    @Test
    void isOfficialMarketplaceName_matchesPrefixes() {
        assertTrue(SkillFilters.isOfficialMarketplaceName("anthropic/lint"));
        assertTrue(SkillFilters.isOfficialMarketplaceName("claude/verify"));
        assertTrue(SkillFilters.isOfficialMarketplaceName("official/test"));
        assertFalse(SkillFilters.isOfficialMarketplaceName("user/my-skill"));
        assertFalse(SkillFilters.isOfficialMarketplaceName(null));
    }

    @Test
    void groupBySource_groupsCorrectly() {
        List<Skill> skills = List.of(
                makeSkill("s1", "user", "skills", false, true, "", true),
                makeSkill("s2", "user", "skills", false, true, "", true),
                makeSkill("s3", "bundled", "bundled", false, true, "", true)
        );
        var grouped = SkillFilters.groupBySource(skills);
        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("skills").size());
        assertEquals(1, grouped.get("bundled").size());
    }
}
