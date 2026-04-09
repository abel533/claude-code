package com.claudecode.config;

import com.claudecode.command.CommandRegistry;
import com.claudecode.context.AgentLoader;
import com.claudecode.context.ClaudeMdLoader;
import com.claudecode.context.GitContext;
import com.claudecode.context.SkillChangeDetector;
import com.claudecode.context.SkillLoader;
import com.claudecode.context.SystemPromptBuilder;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.CoordinatorMode;
import com.claudecode.core.SessionMemoryService;
import com.claudecode.core.TaskManager;
import com.claudecode.core.TokenTracker;
import com.claudecode.core.compact.AutoCompactManager;
import com.claudecode.mcp.McpManager;
import com.claudecode.permission.PermissionRuleEngine;
import com.claudecode.permission.PermissionSettings;
import com.claudecode.plugin.OutputStylePlugin;
import com.claudecode.plugin.PluginManager;
import com.claudecode.repl.ReplSession;
import com.claudecode.tui.JinkReplSession;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.AgentTool;
import com.claudecode.tool.impl.SkillTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 核心应用配置 —— 基础设施 Bean 和跨切关注点。
 * <p>
 * 工具注册见 {@link ToolConfiguration}，
 * 命令注册见 {@link CommandConfiguration}。
 */

/**
 * 应用配置类 —— Spring Bean 装配。
 * <p>
 * 集中管理所有组件的创建和依赖注入。
 * 通过 claude-code.provider 配置切换 API 提供者（openai / anthropic）。
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Value("${claude-code.provider:openai}")
    private String provider;

    @Bean
    public ToolContext toolContext() {
        return ToolContext.defaultContext();
    }

    @Bean
    public TaskManager taskManager() {
        return new TaskManager();
    }

    @Bean
    public McpManager mcpManager() {
        McpManager manager = new McpManager();
        try {
            manager.loadFromConfig();
        } catch (Exception e) {
            log.warn("MCP config loading failed (ignorable): {}", e.getMessage());
        }
        return manager;
    }

    @Bean
    public PluginManager pluginManager(ToolContext toolContext) {
        PluginManager manager = new PluginManager(toolContext);
        // 注册内置插件
        var stylePlugin = new OutputStylePlugin();
        stylePlugin.initialize(new com.claudecode.plugin.PluginContext(
                toolContext, System.getProperty("user.dir"), stylePlugin.id()));
        // 加载外部插件
        manager.loadAll();
        return manager;
    }

    /**
     * 根据 claude-code.provider 配置选择 ChatModel。
     */
    @Bean
    public ChatModel activeChatModel(
            @Qualifier("openAiChatModel") ChatModel openAiModel,
            @Qualifier("anthropicChatModel") ChatModel anthropicModel) {

        if ("anthropic".equalsIgnoreCase(provider)) {
            log.info("Using Anthropic native API");
            return anthropicModel;
        } else {
            log.info("Using OpenAI compatible API");
            return openAiModel;
        }
    }

    @Bean
    public ProviderInfo providerInfo() {
        String baseUrl;
        String model;

        if ("anthropic".equalsIgnoreCase(provider)) {
            baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.anthropic.com");
            model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-4-20250514");
        } else {
            baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
            model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o");
        }

        return new ProviderInfo(provider, baseUrl, model);
    }

    @Bean
    public PermissionSettings permissionSettings() {
        PermissionSettings settings = new PermissionSettings();
        settings.load();
        return settings;
    }

    @Bean
    public PermissionRuleEngine permissionRuleEngine(PermissionSettings permissionSettings) {
        return new PermissionRuleEngine(permissionSettings);
    }

    @Bean
    public AutoCompactManager autoCompactManager(ChatModel activeChatModel, TokenTracker tokenTracker) {
        return new AutoCompactManager(activeChatModel, tokenTracker);
    }

    @Bean
    public SessionMemoryService sessionMemoryService() {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        return new SessionMemoryService(projectDir);
    }

    @Bean
    public TokenTracker tokenTracker(ProviderInfo info) {
        TokenTracker tracker = new TokenTracker();
        tracker.setModel(info.model());
        return tracker;
    }

    @Bean
    public AgentLoader agentLoader() {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        AgentLoader loader = new AgentLoader(projectDir);
        loader.loadAll();
        log.info("Loaded {} agent definitions", loader.getAgents().size());
        return loader;
    }

    @Bean
    public String systemPrompt(ToolContext toolContext, SessionMemoryService sessionMemoryService,
                               PermissionRuleEngine permissionRuleEngine, AgentLoader agentLoader) {
        Path projectDir = Path.of(System.getProperty("user.dir"));

        ClaudeMdLoader claudeLoader = new ClaudeMdLoader(projectDir);
        String claudeMd = claudeLoader.load();

        SkillLoader skillLoader = new SkillLoader(projectDir);
        skillLoader.loadAll();
        String skillsSummary = skillLoader.buildSkillsSummary();

        // Inject SkillLoader into ToolContext for SkillTool
        toolContext.set(SkillTool.SKILL_LOADER_KEY, skillLoader);

        // Inject PermissionRuleEngine into ToolContext for SkillTool permission checks
        toolContext.set(SkillTool.PERMISSION_ENGINE_KEY, permissionRuleEngine);

        // Inject AgentLoader into ToolContext for AgentTool
        toolContext.set("AGENT_LOADER", agentLoader);

        // Inject SessionMemoryService into ToolContext
        toolContext.set("SESSION_MEMORY_SERVICE", sessionMemoryService);

        // Start skill file watcher for hot reload
        SkillChangeDetector changeDetector = new SkillChangeDetector(skillLoader, projectDir);
        changeDetector.start();
        toolContext.set("SKILL_CHANGE_DETECTOR", changeDetector);

        GitContext gitContext = new GitContext(projectDir).collect();
        String gitSummary = gitContext.buildSummary();

        // Load existing session memory
        String sessionMemory = sessionMemoryService.getMemoryContent();

        // Check if coordinator mode is enabled
        if (CoordinatorMode.isCoordinatorMode()) {
            log.info("Coordinator mode enabled via CLAUDE_CODE_COORDINATOR_MODE env var");
            String coordinatorPrompt = CoordinatorMode.getCoordinatorSystemPrompt();
            String userContext = CoordinatorMode.getCoordinatorUserContext();
            return coordinatorPrompt + "\n\n" + userContext;
        }

        return new SystemPromptBuilder()
                .claudeMd(claudeMd)
                .skills(skillsSummary)
                .git(gitSummary)
                .sessionMemory(sessionMemory)
                .build();
    }

    @Bean
    public AgentLoop agentLoop(ChatModel activeChatModel, ToolRegistry toolRegistry,
                               ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker,
                               PluginManager pluginManager, PermissionRuleEngine permissionRuleEngine,
                               AutoCompactManager autoCompactManager, SessionMemoryService sessionMemoryService) {
        AgentLoop mainLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt, tokenTracker);

        // 注入权限引擎和自动压缩管理器
        mainLoop.setPermissionEngine(permissionRuleEngine);
        mainLoop.setAutoCompactManager(autoCompactManager);

        // 注册子 Agent 工厂
        java.util.function.Function<String, String> agentFactory =
                (java.util.function.Function<String, String>) prompt -> {
                    AgentLoop subLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt);
                    return subLoop.run(prompt);
                };
        toolContext.set(AgentTool.AGENT_FACTORY_KEY, agentFactory);

        // Wire SessionMemoryService with agent factory and agent loop
        sessionMemoryService.setAgentFactory(agentFactory);
        mainLoop.setSessionMemoryService(sessionMemoryService);

        // 注册 PluginManager 到 ToolContext
        toolContext.set("PLUGIN_MANAGER", pluginManager);

        return mainLoop;
    }

    @Bean
    public JinkReplSession jinkReplSession(AgentLoop agentLoop, ToolRegistry toolRegistry,
                                           CommandRegistry commandRegistry, ProviderInfo providerInfo,
                                           TokenTracker tokenTracker) {
        return new JinkReplSession(agentLoop, toolRegistry, commandRegistry, providerInfo, tokenTracker);
    }

    @Bean
    public ReplSession replSession(AgentLoop agentLoop, ToolRegistry toolRegistry,
                                   CommandRegistry commandRegistry, ProviderInfo providerInfo) {
        return new ReplSession(agentLoop, toolRegistry, commandRegistry, providerInfo);
    }

    /** API 提供者信息 */
    public record ProviderInfo(String provider, String baseUrl, String model) {
    }
}
