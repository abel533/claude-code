package com.claudecode.tool.impl;

import com.claudecode.context.SkillFilters;
import com.claudecode.context.SkillLoader;
import com.claudecode.context.SkillLoader.Skill;
import com.claudecode.permission.PermissionRuleEngine;
import com.claudecode.permission.PermissionTypes.PermissionDecision;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import com.claudecode.util.ArgumentSubstitution;
import com.claudecode.util.PromptShellExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 执行工具 —— 对应 claude-code/src/tools/SkillTool/SkillTool.ts。
 * <p>
 * 通过名称调用已加载的 Skill。Skill 会作为 forked sub-agent 执行，
 * 注入 Skill 的 markdown 内容作为上下文指导。
 * <p>
 * Skills 来源：
 * <ul>
 *   <li>用户级: ~/.claude/skills/</li>
 *   <li>项目级: ./.claude/skills/</li>
 *   <li>命令目录: ./.claude/commands/</li>
 *   <li>内置 Skills: verify, debug 等</li>
 * </ul>
 */
public class SkillTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    /** ToolContext key for SkillLoader */
    public static final String SKILL_LOADER_KEY = "SKILL_LOADER";

    /** ToolContext key for PermissionRuleEngine */
    public static final String PERMISSION_ENGINE_KEY = "PERMISSION_ENGINE";

    /** Max progress messages to show in non-verbose mode (matches TS) */
    private static final int MAX_PROGRESS_MESSAGES = 3;

    @Override
    public String name() {
        return "Skill";
    }

    @Override
    public String description() {
        return """
                Execute a registered skill by name. Skills are reusable, structured workflows \
                defined in markdown files that guide a specialized sub-agent.
                
                When to use:
                - User invokes a skill by name (e.g., /verify, /debug)
                - You identify a task that matches a registered skill's "whenToUse" criteria
                - Complex workflows that benefit from structured guidance
                
                Available skills are listed in the system prompt under "Available Skills".
                
                The skill runs as an isolated sub-agent with its own context. Provide any relevant \
                arguments or context from the current conversation.
                """;
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "skill_name": {
                      "type": "string",
                      "description": "Name of the skill to execute (case-insensitive)"
                    },
                    "arguments": {
                      "type": "string",
                      "description": "Arguments or context to pass to the skill"
                    }
                  },
                  "required": ["skill_name"]
                }
                """;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String skillName = (String) input.get("skill_name");
        String arguments = (String) input.getOrDefault("arguments", "");

        if (skillName == null || skillName.isBlank()) {
            return "Error: 'skill_name' is required";
        }

        // Normalize leading slash (matches TS behavior)
        boolean hasLeadingSlash = skillName.startsWith("/");
        if (hasLeadingSlash) {
            skillName = skillName.substring(1);
            logEvent("tengu_skill_tool_slash_prefix", Map.of());
        }

        // Get SkillLoader from context
        SkillLoader skillLoader = context.get(SKILL_LOADER_KEY);
        if (skillLoader == null) {
            return "Error: SkillLoader not configured. No skills available.";
        }

        // Find skill by name (using filtered list for model-invocable skills)
        List<Skill> allSkills = skillLoader.getSkills();
        List<Skill> visibleSkills = SkillFilters.getSkillToolCommands(allSkills);

        Optional<Skill> skillOpt = SkillFilters.findCommand(allSkills, skillName);
        if (skillOpt.isEmpty()) {
            // Try partial match across all skills
            skillOpt = findByPartialName(allSkills, skillName);
        }

        if (skillOpt.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Skill '").append(skillName).append("' not found.\n\n");
            msg.append("Available skills:\n");
            for (Skill s : visibleSkills) {
                msg.append("  - ").append(s.userFacingName());
                String desc = SkillFilters.formatDescriptionWithSource(s);
                if (!desc.isEmpty()) {
                    msg.append(": ").append(desc);
                }
                msg.append("\n");
            }
            return msg.toString();
        }

        Skill skill = skillOpt.get();

        // Check if model invocation is disabled for this skill
        if (skill.disableModelInvocation()) {
            return renderError(skill, "Skill '" + skill.userFacingName()
                    + "' cannot be invoked by the model. It has disable-model-invocation: true.");
        }

        // Permission check (corresponds to TS checkPermissions)
        String permissionError = checkPermissions(skill, skillName, context);
        if (permissionError != null) {
            return renderRejected(skill, permissionError);
        }

        // Analytics: log skill invocation
        logSkillInvocation(skill, skillName, arguments);

        log.info("Executing skill: {} [{}] context={}", skill.name(), skill.source(), skill.context());

        // Build skill execution prompt
        String skillPrompt = buildSkillPrompt(skill, arguments, context);

        // Check if skill should be forked (sub-agent) or inline
        if (!skill.isForked()) {
            // Inline execution: return the skill prompt for the current agent to follow
            return renderInlineResult(skill, skillPrompt);
        }

        // Forked execution: execute via agent factory (same as AgentTool)
        @SuppressWarnings("unchecked")
        java.util.function.Function<String, String> agentFactory =
                context.getOrDefault(AgentTool.AGENT_FACTORY_KEY, null);

        if (agentFactory == null) {
            // Fallback: return skill content for manual execution guidance
            return "⚠️ Sub-agent not available. Skill content for manual execution:\n\n"
                    + "# Skill: " + skill.name() + "\n"
                    + skill.content();
        }

        try {
            String result = agentFactory.apply(skillPrompt);
            log.info("Skill '{}' completed, result: {} chars", skill.name(), result.length());
            return renderForkedResult(skill, result);
        } catch (Exception e) {
            log.debug("Skill execution failed", e);
            return renderError(skill, "Error executing skill '" + skill.name() + "': " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly() {
        return false; // Skills may modify files
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String name = input != null ? (String) input.get("skill_name") : null;
        return name != null ? "Running skill: " + name + "..." : "Running skill...";
    }

    // ==================== Permission Checking ====================

    /**
     * Check permissions for skill execution.
     * Corresponds to TS SkillTool.checkPermissions().
     *
     * @return error message if denied, null if allowed
     */
    private String checkPermissions(Skill skill, String commandName, ToolContext context) {
        PermissionRuleEngine engine = context.get(PERMISSION_ENGINE_KEY);
        if (engine == null) return null; // No permission engine → allow

        // Check permission using the tool name "Skill" and skill name as command
        PermissionDecision decision = engine.evaluate("Skill",
                Map.of("skill_name", commandName), false, context);

        return switch (decision.behavior()) {
            case DENY -> decision.reason() != null ? decision.reason() : "Skill execution blocked by permission rules";
            case ASK -> null; // ASK = let it through (interactive confirm handled elsewhere)
            default -> null;  // ALLOW
        };
    }

    // ==================== UI Rendering ====================

    /**
     * Render result for inline skill execution.
     * Corresponds to TS renderToolResultMessage() for inline skills.
     */
    private String renderInlineResult(Skill skill, String prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 Skill '").append(skill.userFacingName()).append("' loaded (inline mode).");

        // Show tools count if restricted
        if (skill.allowedTools() != null && !skill.allowedTools().isEmpty()) {
            sb.append(" [").append(skill.allowedTools().size()).append(" tools allowed]");
        }

        // Show model if non-default
        if (skill.model() != null) {
            sb.append(" [model: ").append(skill.model()).append("]");
        }

        sb.append("\n\nFollow these instructions:\n\n").append(prompt);
        return sb.toString();
    }

    /**
     * Render result for forked skill execution.
     * Corresponds to TS renderToolResultMessage() for forked skills — shows "Done".
     */
    private String renderForkedResult(Skill skill, String result) {
        return result;
    }

    /**
     * Render rejection message.
     * Corresponds to TS renderToolUseRejectedMessage().
     */
    private String renderRejected(Skill skill, String reason) {
        return "⛔ Skill '" + skill.userFacingName() + "' rejected: " + reason;
    }

    /**
     * Render error message.
     * Corresponds to TS renderToolUseErrorMessage().
     */
    private String renderError(Skill skill, String error) {
        return "❌ " + error;
    }

    /**
     * Render tool use message (for display during execution).
     * Corresponds to TS renderToolUseMessage() — shows legacy /commands/ marker.
     */
    public static String renderToolUseMessage(Skill skill) {
        if ("commands_DEPRECATED".equals(skill.loadedFrom())) {
            return "/" + skill.name();
        }
        return skill.userFacingName();
    }

    /**
     * Render progress message during skill execution.
     * Corresponds to TS renderToolUseProgressMessage().
     */
    public static String renderProgressMessage(Skill skill) {
        String msg = skill.progressMessage();
        return msg != null ? msg : "running";
    }

    // ==================== Analytics ====================

    /**
     * Log skill invocation telemetry event.
     * Corresponds to TS logEvent('tengu_skill_tool_invocation', ...).
     */
    private void logSkillInvocation(Skill skill, String commandName, String arguments) {
        boolean isOfficial = SkillFilters.isOfficialMarketplace(skill);
        String executionContext = skill.isForked() ? "fork" : "inline";

        log.info("SKILL_INVOKED: name={}, source={}, loadedFrom={}, context={}, official={}, argsLen={}",
                commandName, skill.source(), skill.loadedFrom(), executionContext,
                isOfficial, arguments != null ? arguments.length() : 0);

        logEvent("tengu_skill_tool_invocation", Map.of(
                "command_name", sanitizeSkillName(commandName),
                "execution_context", executionContext,
                "skill_source", skill.source() != null ? skill.source() : "",
                "skill_loaded_from", skill.loadedFrom() != null ? skill.loadedFrom() : "",
                "is_official_marketplace", String.valueOf(isOfficial)
        ));
    }

    /**
     * Sanitize skill name for telemetry (remove PII).
     */
    private String sanitizeSkillName(String name) {
        if (name == null) return "unknown";
        // Replace user-specific paths with generic markers
        return name.replaceAll("[^a-zA-Z0-9_:-]", "_");
    }

    /**
     * Log a telemetry event (stub — integrates with existing TelemetryService if available).
     */
    private void logEvent(String eventName, Map<String, String> properties) {
        // Log to SLF4J for now; when TelemetryService is wired, delegate there
        log.debug("TELEMETRY: {} {}", eventName, properties);
    }

    // ==================== Prompt Building ====================

    /**
     * Build the full prompt for skill execution.
     * Supports argument substitution ($ARGUMENTS, $n, $name, ${CLAUDE_SKILL_DIR}, ${CLAUDE_SESSION_ID}).
     * Supports embedded shell command execution (!`cmd` and ```! cmd ```).
     */
    private String buildSkillPrompt(Skill skill, String arguments, ToolContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are executing a skill: ").append(skill.userFacingName()).append("\n\n");

        if (!skill.description().isEmpty()) {
            sb.append("Description: ").append(skill.description()).append("\n");
        }
        if (!skill.whenToUse().isEmpty()) {
            sb.append("When to use: ").append(skill.whenToUse()).append("\n");
        }
        if (skill.model() != null) {
            sb.append("Preferred model: ").append(skill.model()).append("\n");
        }
        if (skill.effort() != null) {
            sb.append("Effort level: ").append(skill.effort()).append("\n");
        }
        sb.append("\n");

        // Tool restrictions
        if (skill.allowedTools() != null && !skill.allowedTools().isEmpty()) {
            sb.append("Allowed tools: ").append(String.join(", ", skill.allowedTools())).append("\n");
        }
        if (skill.disallowedTools() != null && !skill.disallowedTools().isEmpty()) {
            sb.append("Disallowed tools: ").append(String.join(", ", skill.disallowedTools())).append("\n");
        }

        // Build content with argument substitution
        String content = skill.content();

        // Prepend base directory if available (matches TS behavior)
        Path skillDir = skill.skillRoot();
        if (skillDir == null && skill.filePath() != null) {
            skillDir = skill.filePath().getParent();
        }
        if (skillDir != null) {
            content = "Base directory for this skill: " + skillDir + "\n\n" + content;
        }

        // Argument substitution using new utility (matches TS substituteArguments)
        List<String> argNames = skill.arguments() != null ? skill.arguments() : List.of();
        content = ArgumentSubstitution.substituteArguments(content, arguments, true, argNames);

        // Replace ${CLAUDE_SKILL_DIR} with normalized path
        if (skillDir != null) {
            String skillDirStr = skillDir.toString();
            // Normalize backslashes to forward slashes on Windows (matches TS)
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                skillDirStr = skillDirStr.replace('\\', '/');
            }
            content = content.replace("${CLAUDE_SKILL_DIR}", skillDirStr);
        }

        // Replace ${CLAUDE_SESSION_ID}
        content = content.replace("${CLAUDE_SESSION_ID}",
                System.getProperty("claude.session.id", "default-session"));

        // Execute embedded shell commands (!`cmd` and ```! cmd ```)
        // Security: skip for MCP skills (remote/untrusted)
        if (!"mcp".equals(skill.source())) {
            Path workDir = context != null ? context.getWorkDir() : skillDir;
            content = PromptShellExecution.executeShellCommandsInPrompt(content, skill.shell(), workDir);
        }

        sb.append("## Skill Instructions\n\n");
        sb.append(content).append("\n\n");

        // Inject arguments section (for forked context only, inline already has content)
        if (arguments != null && !arguments.isBlank() && skill.isForked()) {
            sb.append("## User Arguments\n\n");
            sb.append(arguments).append("\n\n");
        }

        sb.append("""
                ## Execution Guidelines
                - Follow the skill instructions above carefully
                - Use the available tools to complete the task
                - Report results concisely when done
                - If the skill requires user input, use AskUserQuestion
                """);

        return sb.toString();
    }

    /**
     * Partial name match for skills.
     */
    private Optional<Skill> findByPartialName(List<Skill> skills, String name) {
        String lower = name.toLowerCase();
        return skills.stream()
                .filter(s -> s.name().toLowerCase().contains(lower))
                .findFirst();
    }
}
