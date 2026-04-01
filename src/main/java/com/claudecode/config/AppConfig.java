package com.claudecode.config;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.impl.ClearCommand;
import com.claudecode.command.impl.CompactCommand;
import com.claudecode.command.impl.CostCommand;
import com.claudecode.command.impl.ExitCommand;
import com.claudecode.command.impl.HelpCommand;
import com.claudecode.command.impl.ModelCommand;
import com.claudecode.context.ClaudeMdLoader;
import com.claudecode.context.SystemPromptBuilder;
import com.claudecode.core.AgentLoop;
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
                new ExitCommand()
        );
        return registry;
    }

    @Bean
    public String systemPrompt() {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        ClaudeMdLoader loader = new ClaudeMdLoader(projectDir);
        String claudeMd = loader.load();

        return new SystemPromptBuilder()
                .claudeMd(claudeMd)
                .build();
    }

    @Bean
    public AgentLoop agentLoop(@Qualifier("anthropicChatModel") ChatModel chatModel, ToolRegistry toolRegistry,
                               ToolContext toolContext, String systemPrompt) {
        AgentLoop mainLoop = new AgentLoop(chatModel, toolRegistry, toolContext, systemPrompt);

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
