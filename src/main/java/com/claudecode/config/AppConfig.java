package com.claudecode.config;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.impl.*;
import com.claudecode.context.ClaudeMdLoader;
import com.claudecode.context.GitContext;
import com.claudecode.context.SkillLoader;
import com.claudecode.context.SystemPromptBuilder;
import com.claudecode.core.AgentLoop;
import com.claudecode.core.TokenTracker;
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
import java.util.Map;

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
    public ToolRegistry toolRegistry() {
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
                new NotebookEditTool()
        );
        return registry;
    }

    @Bean
    public CommandRegistry commandRegistry() {
        CommandRegistry registry = new CommandRegistry();
        registry.registerAll(
                new HelpCommand(),
                new ClearCommand(),
                new CompactCommand(),
                new CostCommand(),
                new ModelCommand(),
                new StatusCommand(),
                new ContextCommand(),
                new InitCommand(),
                new ConfigCommand(),
                new ExitCommand()
        );
        return registry;
    }

    /**
     * 根据 claude-code.provider 配置选择 ChatModel。
     * - "anthropic" → 使用 anthropicChatModel
     * - "openai"（默认）→ 使用 openAiChatModel
     */
    @Bean
    public ChatModel activeChatModel(
            @Qualifier("openAiChatModel") ChatModel openAiModel,
            @Qualifier("anthropicChatModel") ChatModel anthropicModel) {

        if ("anthropic".equalsIgnoreCase(provider)) {
            log.info("使用 Anthropic 原生 API");
            return anthropicModel;
        } else {
            log.info("使用 OpenAI 兼容 API");
            return openAiModel;
        }
    }

    @Bean
    public ProviderInfo providerInfo() {
        String baseUrl;
        String model;

        if ("anthropic".equalsIgnoreCase(provider)) {
            baseUrl = System.getenv().getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
            model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-4-20250514");
        } else {
            baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
            model = System.getenv().getOrDefault("AI_OPENAI_MODEL", "gpt-4o");
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
                               ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker) {
        AgentLoop mainLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt, tokenTracker);

        // 注册子 Agent 工厂
        toolContext.set(AgentTool.AGENT_FACTORY_KEY,
                (java.util.function.Function<String, String>) prompt -> {
                    AgentLoop subLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt);
                    return subLoop.run(prompt);
                });

        return mainLoop;
    }

    @Bean
    public ReplSession replSession(AgentLoop agentLoop, ToolRegistry toolRegistry,
                                   CommandRegistry commandRegistry, ProviderInfo providerInfo) {
        return new ReplSession(agentLoop, toolRegistry, commandRegistry, providerInfo);
    }

    /** API 提供者信息，供 Banner 和命令显示 */
    public record ProviderInfo(String provider, String baseUrl, String model) {
    }
}
