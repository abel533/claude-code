package com.claudecode.command;

import com.claudecode.core.AgentLoop;
import com.claudecode.tool.ToolRegistry;

import java.io.PrintStream;

/**
 * 命令执行上下文。
 */
public record CommandContext(
        AgentLoop agentLoop,
        ToolRegistry toolRegistry,
        PrintStream out,
        Runnable exitCallback
) {
}
