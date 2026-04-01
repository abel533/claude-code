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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 应用配置类 —— Spring Bean 装配。
 * <p>
 * 集中管理所有组件的创建和依赖注入。
 */
@Configuration
public class AppConfig {

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

    @Bean
    public TokenTracker tokenTracker() {
        TokenTracker tracker = new TokenTracker();
        String model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-4-20250514");
        tracker.setModel(model);
        return tracker;
    }

    @Bean
    public String systemPrompt() {
        Path projectDir = Path.of(System.getProperty("user.dir"));

        // 加载 CLAUDE.md
        ClaudeMdLoader claudeLoader = new ClaudeMdLoader(projectDir);
        String claudeMd = claudeLoader.load();

        // 加载 Skills
        SkillLoader skillLoader = new SkillLoader(projectDir);
        skillLoader.loadAll();
        String skillsSummary = skillLoader.buildSkillsSummary();

        // 收集 Git 上下文
        GitContext gitContext = new GitContext(projectDir).collect();
        String gitSummary = gitContext.buildSummary();

        return new SystemPromptBuilder()
                .claudeMd(claudeMd)
                .skills(skillsSummary)
                .git(gitSummary)
                .build();
    }

    @Bean
    public AgentLoop agentLoop(@Qualifier("anthropicChatModel") ChatModel chatModel, ToolRegistry toolRegistry,
                               ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker) {
        AgentLoop mainLoop = new AgentLoop(chatModel, toolRegistry, toolContext, systemPrompt, tokenTracker);

        // 注册子 Agent 工厂到 ToolContext，使 AgentTool 能创建独立的 AgentLoop
        toolContext.set(AgentTool.AGENT_FACTORY_KEY,
                (java.util.function.Function<String, String>) prompt -> {
                    AgentLoop subLoop = new AgentLoop(chatModel, toolRegistry, toolContext, systemPrompt);
                    return subLoop.run(prompt);
                });

        return mainLoop;
    }

    @Bean
    public ReplSession replSession(AgentLoop agentLoop, ToolRegistry toolRegistry,
                                   CommandRegistry commandRegistry) {
        return new ReplSession(agentLoop, toolRegistry, commandRegistry);
    }
}
