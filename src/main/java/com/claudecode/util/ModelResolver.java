package com.claudecode.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 模型别名解析器 —— 对应 TS resolveSkillModelOverride()。
 * <p>
 * 解析技能 frontmatter 中的 model 字段，支持：
 * <ul>
 *   <li>别名解析：haiku → claude-3-haiku, sonnet → claude-sonnet-4, opus → claude-opus-4</li>
 *   <li>特殊值：inherit → null（继承父级模型）</li>
 *   <li>完整模型 ID：直接透传</li>
 * </ul>
 */
public final class ModelResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelResolver.class);

    /** Model alias mapping (short name → full model ID) */
    private static final Map<String, String> MODEL_ALIASES = Map.ofEntries(
            // Claude 3 family
            Map.entry("haiku", "claude-3-haiku-20240307"),
            Map.entry("claude-3-haiku", "claude-3-haiku-20240307"),
            Map.entry("haiku-3", "claude-3-haiku-20240307"),

            // Claude 3.5 family
            Map.entry("sonnet-3.5", "claude-3-5-sonnet-20241022"),
            Map.entry("claude-3.5-sonnet", "claude-3-5-sonnet-20241022"),
            Map.entry("haiku-3.5", "claude-3-5-haiku-20241022"),
            Map.entry("claude-3.5-haiku", "claude-3-5-haiku-20241022"),

            // Claude 4 family (latest)
            Map.entry("sonnet", "claude-sonnet-4-20250514"),
            Map.entry("claude-sonnet", "claude-sonnet-4-20250514"),
            Map.entry("sonnet-4", "claude-sonnet-4-20250514"),
            Map.entry("claude-sonnet-4", "claude-sonnet-4-20250514"),

            Map.entry("opus", "claude-opus-4-20250514"),
            Map.entry("claude-opus", "claude-opus-4-20250514"),
            Map.entry("opus-4", "claude-opus-4-20250514"),
            Map.entry("claude-opus-4", "claude-opus-4-20250514"),

            // OpenAI aliases (for OpenAI-compatible providers)
            Map.entry("gpt-4", "gpt-4"),
            Map.entry("gpt-4o", "gpt-4o"),
            Map.entry("gpt-4o-mini", "gpt-4o-mini"),
            Map.entry("o1", "o1"),
            Map.entry("o1-mini", "o1-mini"),
            Map.entry("o3", "o3"),
            Map.entry("o3-mini", "o3-mini"),
            Map.entry("o4-mini", "o4-mini")
    );

    private ModelResolver() {}

    /**
     * Resolve a model override from skill frontmatter.
     * Corresponds to TS resolveSkillModelOverride().
     *
     * @param modelValue raw model value from frontmatter (may be alias, "inherit", or full ID)
     * @return resolved model ID, or null if "inherit" or invalid
     */
    public static String resolveSkillModelOverride(String modelValue) {
        if (modelValue == null || modelValue.isBlank()) {
            return null;
        }

        String normalized = modelValue.strip().toLowerCase();

        // "inherit" means use parent model
        if ("inherit".equals(normalized)) {
            return null;
        }

        // Check aliases
        String resolved = MODEL_ALIASES.get(normalized);
        if (resolved != null) {
            log.debug("Resolved model alias '{}' → '{}'", modelValue, resolved);
            return resolved;
        }

        // If it looks like a full model ID (contains a dash and has enough chars), pass through
        if (modelValue.contains("-") || modelValue.contains("/") || modelValue.length() > 10) {
            return modelValue.strip();
        }

        // Unknown short name — log warning but still pass through
        log.debug("Unknown model alias '{}', passing through as-is", modelValue);
        return modelValue.strip();
    }

    /**
     * Check if a model value is a known alias.
     */
    public static boolean isKnownAlias(String modelValue) {
        if (modelValue == null) return false;
        return MODEL_ALIASES.containsKey(modelValue.strip().toLowerCase());
    }

    /**
     * Get all known model aliases.
     */
    public static Map<String, String> getAllAliases() {
        return MODEL_ALIASES;
    }
}
