package com.claudecode.core;

import com.claudecode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Coordinator Mode —— 对应 claude-code/src/coordinator/coordinatorMode.ts。
 * <p>
 * 协调模式允许 Agent 作为"协调者"运行，仅使用 Agent、SendMessage、TaskStop 工具
 * 来派发和管理 worker agent。Worker agent 使用标准工具集执行实际任务。
 * <p>
 * 通过环境变量 CLAUDE_CODE_COORDINATOR_MODE=1 启用。
 */
public class CoordinatorMode {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorMode.class);

    /** Coordinator 可用的工具集 */
    public static final Set<String> COORDINATOR_ALLOWED_TOOLS = Set.of(
            "Agent",       // 派发 worker
            "SendMessage", // 向 worker 发送消息
            "TaskStop",    // 停止 worker
            "TaskGet",     // 查看 worker 状态
            "TaskList",    // 列出所有 worker
            "TaskOutput"   // 获取 worker 输出
    );

    /** Worker（异步 agent）可用的工具集 */
    public static final Set<String> WORKER_ALLOWED_TOOLS = Set.of(
            "Read",          // 读取文件
            "Write",         // 写入文件
            "Edit",          // 编辑文件
            "Bash",          // 执行命令
            "Grep",          // 搜索文件内容
            "Glob",          // 文件模式匹配
            "ListFiles",     // 列出目录
            "WebFetch",      // 获取网页
            "WebSearch",     // 搜索网页
            "TodoRead",      // 读取待办
            "TodoWrite",     // 写待办
            "ToolSearch",    // 搜索工具
            "Skill"          // 执行 skill
    );

    /** 检查 coordinator 模式是否通过环境变量启用 */
    public static boolean isCoordinatorMode() {
        String envVal = System.getenv("CLAUDE_CODE_COORDINATOR_MODE");
        return envVal != null && !envVal.isBlank()
                && !envVal.equalsIgnoreCase("false")
                && !envVal.equals("0");
    }

    /**
     * 获取 Coordinator 系统提示词。
     * 对应 TS 版 getCoordinatorSystemPrompt()。
     */
    public static String getCoordinatorSystemPrompt() {
        return """
                You are Claude Code, an AI assistant that orchestrates software engineering tasks \
                across multiple workers. Your role is to:
                1. Understand user requests and decompose them into parallel tasks
                2. Spawn worker agents for each task using the Agent tool
                3. Monitor worker progress and synthesize results
                4. Communicate clear, actionable results to the user
                
                ## Your Tools
                
                - **Agent** — Spawn a worker to execute a specific task. Workers have access to \
                file operations (Read, Write, Edit), shell commands (Bash), search (Grep, Glob), \
                web access, and project skills.
                - **SendMessage** — Send follow-up instructions to a running or completed worker. \
                Use this to continue multi-step workflows or provide corrections.
                - **TaskStop** — Forcefully terminate a worker that is stuck or no longer needed.
                - **TaskGet** — Check the current status and output of a specific worker.
                - **TaskList** — List all active and completed workers.
                - **TaskOutput** — Get the full output of a completed worker.
                
                ## Worker Results
                
                When a worker completes, you'll receive a task-notification with:
                - task-id: The worker's unique identifier
                - status: completed, failed, or cancelled
                - summary: Brief description of what was accomplished
                - result: Full output from the worker
                
                ## Workflow Guidance
                
                ### Task Decomposition
                1. **Research Phase**: Spawn workers to investigate the codebase, understand the problem
                2. **Synthesis**: Analyze worker results, identify patterns, form a plan
                3. **Implementation Phase**: Spawn workers for code changes, each with specific scope
                4. **Verification Phase**: Spawn workers to test, lint, and validate changes
                
                ### Writing Worker Prompts
                - Be **self-contained**: Workers cannot see your conversation history
                - Include **file paths** (absolute), **line numbers**, and **exact context**
                - Specify **expected output format** (what you need from the result)
                - Add a **purpose statement** so the worker understands the bigger picture
                - If building on previous findings, **summarize those findings** in the prompt
                
                ### Concurrency Management
                - Spawn independent tasks **in parallel** for maximum throughput
                - Workers that depend on others' results should be spawned **sequentially**
                - Don't over-decompose — if a task is simple, one worker is enough
                - Group related small changes into a single worker's scope
                
                ### Verification Best Practices
                - Always verify implementation changes with a dedicated verification worker
                - The verification worker should run existing tests and any new tests
                - Ask the verification worker to check for common issues (imports, types, edge cases)
                
                ## Communication
                - Every message you send is directed to the **user** (not workers)
                - Provide concise status updates as workers complete
                - Synthesize worker results into a clear, coherent summary
                - If something goes wrong, explain what happened and propose next steps
                
                ## Important Rules
                - You do NOT have direct access to files, shell, or search — delegate those to workers
                - DO NOT attempt to edit files yourself; spawn a worker for any file operations
                - Keep your conversation focused on orchestration and synthesis
                - If a worker fails, analyze the error and spawn a corrective worker
                """;
    }

    /**
     * 获取 coordinator 的用户上下文消息。
     * 告知 coordinator worker 可用的工具集。
     */
    public static String getCoordinatorUserContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Worker Capabilities\n\n");
        sb.append("Workers have access to the following tools:\n");
        for (String tool : WORKER_ALLOWED_TOOLS.stream().sorted().toList()) {
            sb.append("- ").append(tool).append("\n");
        }
        sb.append("\nPlus any MCP tools from connected servers.\n");
        return sb.toString();
    }

    /**
     * 过滤 ToolRegistry，仅保留 coordinator 可用的工具。
     */
    public static java.util.List<String> filterForCoordinator(ToolRegistry registry) {
        return registry.getToolNames().stream()
                .filter(COORDINATOR_ALLOWED_TOOLS::contains)
                .toList();
    }

    /**
     * 过滤 ToolRegistry，仅保留 worker 可用的工具。
     */
    public static java.util.List<String> filterForWorker(ToolRegistry registry) {
        return registry.getToolNames().stream()
                .filter(WORKER_ALLOWED_TOOLS::contains)
                .toList();
    }
}
