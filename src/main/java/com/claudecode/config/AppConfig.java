package com.claudecode.config;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.impl.*;
import com.claudecode.context.ClaudeMdLoader;
import com.claudecode.context.GitContext;
import com.claudecode.context.SkillLoader;
import com.claudecode.context.SystemPromptBuilder;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.TaskManager;
import com.claudecode.core.TokenTracker;
import com.claudecode.mcp.McpManager;
import com.claudecode.plugin.OutputStylePlugin;
import com.claudecode.plugin.PluginManager;
import com.claudecode.repl.ReplSession;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

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

    @Bean
    public ToolRegistry toolRegistry(TaskManager taskManager, McpManager mcpManager,
                                     ToolContext toolContext) {
        // 将 TaskManager 和 McpManager 注册到 ToolContext 供工具使用
        toolContext.set("TASK_MANAGER", taskManager);
        toolContext.set("MCP_MANAGER", mcpManager);

        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(
                new BashTool(),
                new FileReadTool(),
                new FileWriteTool(),
                new FileEditTool(),
                new GlobTool(),
                new GrepTool(),
                new ListFilesTool(),
                new WebFetchTool(),
                new TodoWriteTool(),
                new AgentTool(),
                new NotebookEditTool(),
                new WebSearchTool(),
                new AskUserQuestionTool(),
                // P2: 任务管理工具
                new TaskCreateTool(),
                new TaskGetTool(),
                new TaskListTool(),
                new TaskUpdateTool(),
                // P2: 配置工具
                new ConfigTool()
        );

        // P2: 注册 MCP 工具桥接（将远程 MCP 工具映射为本地工具）
        for (var client : mcpManager.getClients().values()) {
            for (var mcpTool : client.getTools()) {
                registry.register(new McpToolBridge(client.getServerName(), mcpTool));
            }
        }

        return registry;
    }

    @Bean
    public CommandRegistry commandRegistry(PluginManager pluginManager) {
        CommandRegistry registry = new CommandRegistry();
        registry.registerAll(
                // 基础命令
                new HelpCommand(),
                new ClearCommand(),
                new CompactCommand(),
                new CostCommand(),
                new ModelCommand(),
                new StatusCommand(),
                new ContextCommand(),
                new InitCommand(),
                new ConfigCommand(),
                new HistoryCommand(),
                // P0 命令
                new DiffCommand(),
                new VersionCommand(),
                new SkillsCommand(),
                new MemoryCommand(),
                new CopyCommand(),
                // P1 命令
                new ResumeCommand(),
                new ExportCommand(),
                new CommitCommand(),
                // P2 命令
                new HooksCommand(),
                new ReviewCommand(),
                new StatsCommand(),
                new BranchCommand(),
                new RewindCommand(),
                new TagCommand(),
                new SecurityReviewCommand(),
                new McpCommand(),
                new PluginCommand(),
                // Exit 放最后
                new ExitCommand()
        );

        // P2: 注册插件提供的命令
        pluginManager.registerCommands(registry);

        return registry;
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
    public TokenTracker tokenTracker(ProviderInfo info) {
        TokenTracker tracker = new TokenTracker();
        tracker.setModel(info.model());
        return tracker;
    }

    @Bean
    public String systemPrompt() {
        Path projectDir = Path.of(System.getProperty("user.dir"));

        ClaudeMdLoader claudeLoader = new ClaudeMdLoader(projectDir);
        String claudeMd = claudeLoader.load();

        SkillLoader skillLoader = new SkillLoader(projectDir);
        skillLoader.loadAll();
        String skillsSummary = skillLoader.buildSkillsSummary();

        GitContext gitContext = new GitContext(projectDir).collect();
        String gitSummary = gitContext.buildSummary();

        return new SystemPromptBuilder()
                .claudeMd(claudeMd)
                .skills(skillsSummary)
                .git(gitSummary)
                .build();
    }

    @Bean
    public AgentLoop agentLoop(ChatModel activeChatModel, ToolRegistry toolRegistry,
                               ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker,
                               PluginManager pluginManager) {
        AgentLoop mainLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt, tokenTracker);

        // 注册子 Agent 工厂
        toolContext.set(AgentTool.AGENT_FACTORY_KEY,
                (java.util.function.Function<String, String>) prompt -> {
                    AgentLoop subLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt);
                    return subLoop.run(prompt);
                });

        // 注册 PluginManager 到 ToolContext
        toolContext.set("PLUGIN_MANAGER", pluginManager);

        return mainLoop;
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
