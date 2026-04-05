package com.claudecode.config;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.impl.*;
import com.claudecode.core.TaskManager;
import com.claudecode.permission.PermissionSettings;
import com.claudecode.plugin.PluginManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 命令注册配置 —— 从 AppConfig 拆分出来，专注于 SlashCommand 注册。
 */
@Configuration
public class CommandConfiguration {

    @Bean
    public CommandRegistry commandRegistry(PluginManager pluginManager, PermissionSettings permissionSettings,
                                           TaskManager taskManager) {
        ConfigCommand configCommand = new ConfigCommand(permissionSettings);
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
                configCommand,
                new HistoryCommand(),
                // 文件/Git 命令
                new DiffCommand(),
                new VersionCommand(),
                new SkillsCommand(),
                new MemoryCommand(),
                new CopyCommand(),
                new ResumeCommand(),
                new ExportCommand(),
                new CommitCommand(),
                new FilesCommand(),
                new PermissionsCommand(permissionSettings),
                new TasksCommand(taskManager),
                new PlanCommand(permissionSettings),
                // 协作/审查命令
                new HooksCommand(),
                new ReviewCommand(),
                new StatsCommand(),
                new BranchCommand(),
                new RewindCommand(),
                new TagCommand(),
                new SecurityReviewCommand(),
                new McpCommand(),
                new PluginCommand(),
                // 会话/Agent 命令
                new DoctorCommand(),
                new SessionCommand(),
                new AgentCommand(),
                new RenameCommand(),
                // UX 命令
                new BriefCommand(),
                new VimCommand(),
                new ThemeCommand(),
                new UsageCommand(),
                new TipsCommand(),
                new OutputStyleCommand(),
                new EnvCommand(),
                new PerformanceCommand(),
                new PrivacyCommand(),
                new FeedbackCommand(),
                new ReleaseNotesCommand(),
                new KeybindingsCommand(),
                // 调试命令
                new DebugCommand(),
                new HeapdumpCommand(),
                new TraceCommand(),
                new ContextVizCommand(),
                new ResetLimitsCommand(),
                new SandboxCommand(),
                // Exit
                new ExitCommand()
        );

        pluginManager.registerCommands(registry);
        return registry;
    }
}
