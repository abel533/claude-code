package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /review 命令 —— 通过 AI 进行代码审查。
 * <p>
 * 支持三种模式：
 * <ul>
 *   <li>无参数：审查当前未暂存的变更（{@code git diff}）</li>
 *   <li>{@code --staged}：审查已暂存的变更（{@code git diff --staged}）</li>
 *   <li>指定文件路径：审查特定文件的变更</li>
 * </ul>
 * <p>
 * 获取 diff 内容后，发送给 AI 模型进行代码审查。
 */
public class ReviewCommand implements SlashCommand {

    @Override
    public String name() {
        return "review";
    }

    @Override
    public String description() {
        return "Review code changes using AI";
    }

    @Override
    public List<String> aliases() {
        return List.of("rev");
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop 不可用，无法执行代码审查。");
        }

        String trimmedArgs = args != null ? args.trim() : "";

        try {
            // 构建 git diff 命令
            List<String> command = buildGitDiffCommand(trimmedArgs);
            String diffOutput = executeGitCommand(command);

            // 检查 diff 是否为空
            if (diffOutput.isBlank()) {
                return AnsiStyle.yellow("  ⚠ 没有检测到代码变更。") + "\n"
                        + AnsiStyle.dim("  提示: 使用 --staged 审查已暂存的变更，或指定文件路径。");
            }

            // 构建审查提示
            String reviewPrompt = buildReviewPrompt(trimmedArgs, diffOutput);

            // 输出审查进行中的提示
            context.out().println(AnsiStyle.cyan("  🔍 正在审查代码变更..."));
            context.out().println(AnsiStyle.dim("  diff 大小: " + diffOutput.lines().count() + " 行"));
            context.out().println();

            // 发送给 AI 进行审查
            String result = context.agentLoop().run(reviewPrompt);
            return result;

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ 代码审查失败: " + e.getMessage()) + "\n"
                    + AnsiStyle.dim("  请确保当前目录是一个 Git 仓库。");
        }
    }

    /**
     * 根据参数构建 git diff 命令。
     *
     * @param args 用户输入的参数
     * @return git diff 命令列表
     */
    private List<String> buildGitDiffCommand(String args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");

        if (args.contains("--staged")) {
            // 审查已暂存的变更
            command.add("--staged");
        } else if (!args.isEmpty() && !args.startsWith("-")) {
            // 审查指定文件
            command.add("--");
            command.add(args);
        }
        // 默认：审查未暂存的变更（无额外参数）

        return command;
    }

    /**
     * 执行 git 命令并返回输出。
     *
     * @param command 命令列表
     * @return 命令的标准输出
     * @throws Exception 执行失败时抛出异常
     */
    private String executeGitCommand(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        // 读取错误输出
        String errorOutput;
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            errorOutput = errorReader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git diff 执行失败 (exit=" + exitCode + "): " + errorOutput);
        }

        return output;
    }

    /**
     * 构建代码审查提示词。
     *
     * @param args       用户输入的参数
     * @param diffOutput git diff 的输出内容
     * @return 完整的审查提示词
     */
    private String buildReviewPrompt(String args, String diffOutput) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Please review these code changes:\n\n");

        // 描述审查范围
        if (args.contains("--staged")) {
            prompt.append("(Staged changes)\n\n");
        } else if (!args.isEmpty() && !args.startsWith("-")) {
            prompt.append("(Changes in file: ").append(args).append(")\n\n");
        } else {
            prompt.append("(Unstaged changes)\n\n");
        }

        prompt.append("```diff\n");
        prompt.append(diffOutput);
        prompt.append("\n```\n\n");
        prompt.append("Please provide a thorough code review covering:\n");
        prompt.append("1. **Correctness** — Are there any bugs or logic errors?\n");
        prompt.append("2. **Code Quality** — Is the code clean, readable, and well-structured?\n");
        prompt.append("3. **Performance** — Are there any performance concerns?\n");
        prompt.append("4. **Security** — Are there any security vulnerabilities?\n");
        prompt.append("5. **Best Practices** — Does the code follow established patterns and conventions?\n");
        prompt.append("6. **Suggestions** — What improvements would you recommend?\n");

        return prompt.toString();
    }
}
