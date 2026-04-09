package com.claudecode.context;

import com.claudecode.context.SkillLoader.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 过滤与查询工具 —— 对应 TS commands.ts 中的过滤函数。
 * <p>
 * 提供多种视图用于不同 UI 场景：
 * <ul>
 *   <li>{@link #getSkillToolCommands} — SkillTool（模型）可见的技能列表</li>
 *   <li>{@link #getSlashCommandToolSkills} — 斜杠命令可见的技能列表</li>
 *   <li>{@link #formatDescriptionWithSource} — 带来源标记的描述格式化</li>
 * </ul>
 */
public final class SkillFilters {

    private static final Logger log = LoggerFactory.getLogger(SkillFilters.class);

    /** Known official marketplace skill name prefixes */
    private static final Set<String> OFFICIAL_MARKETPLACE_PREFIXES = Set.of(
            "anthropic/", "claude/", "official/"
    );

    private SkillFilters() {}

    // ==================== Core Filter Functions ====================

    /**
     * Get skills visible to the SkillTool (model-invocable skills).
     * Corresponds to TS getSkillToolCommands().
     * <p>
     * Filter logic (matches TS exactly):
     * - Must be from loadedFrom: bundled, skills, commands_DEPRECATED
     * - OR has hasUserSpecifiedDescription=true
     * - OR has non-empty whenToUse
     * - AND NOT disableModelInvocation
     * - AND NOT isHidden
     *
     * @param skills all loaded skills
     * @return filtered skills visible to model
     */
    public static List<Skill> getSkillToolCommands(List<Skill> skills) {
        return skills.stream()
                .filter(s -> !s.disableModelInvocation())
                .filter(s -> !s.isHidden())
                .filter(s -> isSkillToolVisible(s))
                .toList();
    }

    /**
     * Get skills visible to the slash command tool (user-facing /<name> commands).
     * Corresponds to TS getSlashCommandToolSkills().
     * <p>
     * Filter logic:
     * - Must be from loadedFrom: skills, plugin, bundled
     * - OR has disableModelInvocation=true (user-only skills always show in slash)
     * - MUST have description or whenToUse
     * - AND NOT isHidden (unless disableModelInvocation gives visibility)
     *
     * @param skills all loaded skills
     * @return filtered skills visible as slash commands
     */
    public static List<Skill> getSlashCommandToolSkills(List<Skill> skills) {
        return skills.stream()
                .filter(s -> isSlashCommandVisible(s))
                .filter(s -> hasVisibleMetadata(s))
                .toList();
    }

    /**
     * Format a skill description with its source label.
     * Corresponds to TS formatDescriptionWithSource().
     * <p>
     * Examples:
     * - "Run tests" → "Run tests"
     * - "Run tests" (from plugin) → "Run tests [plugin]"
     * - "Run tests" (from managed) → "Run tests [managed]"
     *
     * @param skill the skill
     * @return formatted description with optional source tag
     */
    public static String formatDescriptionWithSource(Skill skill) {
        String desc = skill.description();
        if (desc == null || desc.isBlank()) {
            desc = skill.whenToUse();
        }
        if (desc == null) desc = "";

        String loadedFrom = skill.loadedFrom();
        if (loadedFrom != null && !Set.of("bundled", "skills", "commands_DEPRECATED").contains(loadedFrom)) {
            return desc.isBlank() ? "[" + loadedFrom + "]" : desc + " [" + loadedFrom + "]";
        }
        return desc;
    }

    // ==================== Command Lookup Functions ====================

    /**
     * Get the canonical command name for a skill.
     * Corresponds to TS getCommandName().
     */
    public static String getCommandName(Skill skill) {
        return skill.name();
    }

    /**
     * Find a command/skill by name (case-insensitive).
     * Corresponds to TS findCommand().
     */
    public static Optional<Skill> findCommand(List<Skill> skills, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return skills.stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Check if a command/skill exists by name.
     * Corresponds to TS hasCommand().
     */
    public static boolean hasCommand(List<Skill> skills, String name) {
        return findCommand(skills, name).isPresent();
    }

    /**
     * Get a command/skill by name, or null.
     * Corresponds to TS getCommand().
     */
    public static Skill getCommand(List<Skill> skills, String name) {
        return findCommand(skills, name).orElse(null);
    }

    /**
     * Get all skill names (for typeahead/autocomplete).
     */
    public static List<String> getAllSkillNames(List<Skill> skills) {
        return skills.stream()
                .filter(s -> !s.isHidden())
                .map(Skill::name)
                .sorted()
                .toList();
    }

    /**
     * Get skills grouped by source for display.
     */
    public static Map<String, List<Skill>> groupBySource(List<Skill> skills) {
        return skills.stream()
                .collect(Collectors.groupingBy(
                        s -> s.loadedFrom() != null ? s.loadedFrom() : "unknown",
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    // ==================== Marketplace ====================

    /**
     * Check if a skill name belongs to the official marketplace.
     * Corresponds to TS isOfficialMarketplaceName().
     *
     * @param skillName the skill name to check
     * @return true if the skill is from the official marketplace
     */
    public static boolean isOfficialMarketplaceName(String skillName) {
        if (skillName == null) return false;
        String lower = skillName.toLowerCase();
        return OFFICIAL_MARKETPLACE_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /**
     * Check if a skill is from the official marketplace.
     */
    public static boolean isOfficialMarketplace(Skill skill) {
        return isOfficialMarketplaceName(skill.name())
                || "plugin".equals(skill.loadedFrom())
                || "managed".equals(skill.loadedFrom());
    }

    // ==================== Internal Filter Logic ====================

    /**
     * Check if a skill should be visible to the SkillTool (model).
     * Matches TS getSkillToolCommands() filter logic.
     */
    private static boolean isSkillToolVisible(Skill skill) {
        // Always show bundled, skills-dir, and commands-dir skills
        String loadedFrom = skill.loadedFrom();
        if (loadedFrom != null) {
            if ("bundled".equals(loadedFrom) || "skills".equals(loadedFrom)
                    || "commands_DEPRECATED".equals(loadedFrom)) {
                return true;
            }
        }

        // Show if user specified a description
        if (skill.hasUserSpecifiedDescription()) return true;

        // Show if has whenToUse hint
        return skill.whenToUse() != null && !skill.whenToUse().isBlank();
    }

    /**
     * Check if a skill should be visible as a slash command.
     * Matches TS getSlashCommandToolSkills() filter logic.
     */
    private static boolean isSlashCommandVisible(Skill skill) {
        String loadedFrom = skill.loadedFrom();

        // Skills with disableModelInvocation are user-only → always show in slash
        if (skill.disableModelInvocation()) return true;

        // Standard visibility sources
        if (loadedFrom != null) {
            return "skills".equals(loadedFrom)
                    || "plugin".equals(loadedFrom)
                    || "bundled".equals(loadedFrom);
        }

        return false;
    }

    /**
     * Check if a skill has enough metadata to be displayed.
     */
    private static boolean hasVisibleMetadata(Skill skill) {
        return (skill.description() != null && !skill.description().isBlank())
                || (skill.whenToUse() != null && !skill.whenToUse().isBlank());
    }
}
