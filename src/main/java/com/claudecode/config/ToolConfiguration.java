package com.claudecode.config;

import com.claudecode.mcp.McpManager;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.claudecode.core.TaskManager;
import com.claudecode.permission.PermissionSettings;

/**
 * 工具注册配置 —— 从 AppConfig 拆分出来，专注于 Tool 注册。
 */
@Configuration
public class ToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolConfiguration.class);

    @Bean
    public ToolRegistry toolRegistry(TaskManager taskManager, McpManager mcpManager,
                                     ToolContext toolContext, PermissionSettings permissionSettings) {
        toolContext.set("TASK_MANAGER", taskManager);
        toolContext.set("MCP_MANAGER", mcpManager);
        toolContext.set("PERMISSION_SETTINGS", permissionSettings);

        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(
                // 核心工具
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
                // 任务管理工具
                new TaskCreateTool(),
                new TaskGetTool(),
                new TaskListTool(),
                new TaskUpdateTool(),
                new TaskStopTool(),
                new TaskOutputTool(),
                // 配置/实用工具
                new ConfigTool(),
                new SleepTool(),
                new ToolSearchTool(),
                new EnterPlanModeTool(),
                new ExitPlanModeTool(),
                new SkillTool(),
                new SendMessageTool(),
                new ListMcpResourcesTool(),
                new ReadMcpResourceTool(),
                new EnterWorktreeTool(),
                new ExitWorktreeTool(),
                // 高级工具
                new LSPTool(),
                new BriefTool(),
                new NotificationTool()
        );

        // MCP 工具桥接
        for (var client : mcpManager.getClients().values()) {
            for (var mcpTool : client.getTools()) {
                registry.register(new McpToolBridge(client.getServerName(), mcpTool));
            }
        }

        toolContext.set("TOOL_REGISTRY", registry);
        return registry;
    }
}
