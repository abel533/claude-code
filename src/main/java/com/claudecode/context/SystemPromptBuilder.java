package com.claudecode.context;

import com.claudecode.tool.impl.BashTool;

/**
 * 系统提示词构建器 —— 对应 claude-code/src/prompts.ts。
 * <p>
 * 组装完整的系统提示词，包括核心指令、环境信息、
 * CLAUDE.md、Skills、Git 上下文等模块化内容。
 */
public class SystemPromptBuilder {

    private String workDir;
    private String osName;
    private String userName;
    private String claudeMdContent;
    private String customInstructions;
    private String skillsSummary;
    private String gitSummary;

    public SystemPromptBuilder() {
        this.workDir = System.getProperty("user.dir");
        this.osName = System.getProperty("os.name");
        this.userName = System.getProperty("user.name");
    }

    public SystemPromptBuilder workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public SystemPromptBuilder claudeMd(String content) {
        this.claudeMdContent = content;
        return this;
    }

    public SystemPromptBuilder customInstructions(String instructions) {
        this.customInstructions = instructions;
        return this;
    }

    public SystemPromptBuilder skills(String skillsSummary) {
        this.skillsSummary = skillsSummary;
        return this;
    }

    public SystemPromptBuilder git(String gitSummary) {
        this.gitSummary = gitSummary;
        return this;
    }

    /**
     * 构建完整的系统提示词。
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // 核心角色定义
        sb.append("""
                You are Claude, an AI assistant made by Anthropic, operating as a CLI coding agent.
                You are an interactive CLI tool that helps users with software engineering tasks.
                Use the provided tools to help the user with their request.

                """);

        // 环境信息
        sb.append("# Environment\n");
        sb.append("- Working directory: ").append(workDir).append("\n");
        sb.append("- OS: ").append(osName).append("\n");
        sb.append("- User: ").append(userName).append("\n");
        // 使用 BashTool 检测到的 shell 信息（比 COMSPEC/SHELL 环境变量更准确）
        sb.append(BashTool.getShellHint());
        sb.append("\n");

        // 工具使用指南（对齐官方 Claude Code 的 "Using your tools" 段落）
        sb.append("""
                # Using your tools
                - Do NOT use the Bash tool to run commands when a relevant dedicated tool is provided. \
                Using dedicated tools allows the user to better understand and review your work. \
                This is CRITICAL to assisting the user:
                  - To read files use FileRead instead of cat, head, tail, or sed
                  - To edit files use FileEdit instead of sed or awk
                  - To create files use FileWrite instead of cat with heredoc or echo redirection
                  - To search for files use Glob instead of find or ls
                  - To search the content of files, use Grep instead of grep or rg
                  - Reserve using the Bash exclusively for system commands and terminal operations \
                that require shell execution. If you are unsure and there is a relevant dedicated tool, \
                default to using the dedicated tool and only fallback on using the Bash tool for these \
                if it is absolutely necessary.
                - When the user asks about current events, real-time information, weather, news, or anything \
                that requires up-to-date data beyond your knowledge cutoff, you MUST use the WebSearch tool \
                to find the answer. Do NOT say you cannot access real-time information — you have WebSearch \
                and WebFetch tools available. Use them proactively.
                - Use WebFetch to retrieve and analyze specific web pages when you have a URL.
                - You can call multiple tools in a single response. If you intend to call multiple tools \
                and there are no dependencies between them, make all independent tool calls in parallel. \
                Maximize use of parallel tool calls where possible to increase efficiency. However, if \
                some tool calls depend on previous calls to inform dependent values, do NOT call these \
                tools in parallel and instead call them sequentially.
                - Break down and manage your work with the TodoWrite tool. These tools are helpful for \
                planning your work and helping the user track your progress.

                """);

        // 行为准则
        sb.append("""
                # Guidelines
                - Be concise in responses, but thorough in implementation
                - Always verify changes work before considering a task done
                - Use tools to explore the codebase before making changes
                - When writing code, follow existing patterns and conventions
                - Ask for clarification when requirements are ambiguous
                - When making file edits, always use the Edit tool with exact string matching
                - Prefer editing existing files over creating new ones
                
                """);

        // Git 上下文
        if (gitSummary != null && !gitSummary.isBlank()) {
            sb.append(gitSummary).append("\n");
        }

        // CLAUDE.md 内容
        if (claudeMdContent != null && !claudeMdContent.isBlank()) {
            sb.append("# Project Instructions (CLAUDE.md)\n");
            sb.append(claudeMdContent).append("\n\n");
        }

        // Skills 摘要
        if (skillsSummary != null && !skillsSummary.isBlank()) {
            sb.append(skillsSummary).append("\n");
        }

        // 自定义指令
        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("# Custom Instructions\n");
            sb.append(customInstructions).append("\n\n");
        }

        return sb.toString();
    }
}
