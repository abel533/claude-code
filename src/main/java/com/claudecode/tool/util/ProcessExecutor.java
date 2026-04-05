package com.claudecode.tool.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 进程执行器 —— 消除 BashTool、GrepTool、NotificationTool 中的重复 ProcessBuilder 模式。
 * <p>
 * 封装进程创建、超时管理、输出捕获和资源清理。
 */
public final class ProcessExecutor {

    private ProcessExecutor() {}

    /**
     * 进程执行结果。
     */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }

        public String output() {
            return stdout != null ? stdout : "";
        }

        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) sb.append(stdout);
            if (stderr != null && !stderr.isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(stderr);
            }
            return sb.toString();
        }
    }

    /**
     * 在指定工作目录执行命令，带超时和资源清理。
     */
    public static Result execute(List<String> command, Path workDir, long timeoutMs) {
        return execute(command, workDir, timeoutMs, null);
    }

    /**
     * 在指定工作目录执行命令，带超时、环境变量和资源清理。
     */
    public static Result execute(List<String> command, Path workDir, long timeoutMs, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }

        Process process = null;
        try {
            process = pb.start();

            String stdout;
            String stderr;
            try (var stdoutStream = process.getInputStream();
                 var stderrStream = process.getErrorStream()) {
                stdout = new String(stdoutStream.readAllBytes());
                stderr = new String(stderrStream.readAllBytes());
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return new Result(-1, stdout, stderr, true);
            }

            return new Result(process.exitValue(), stdout, stderr, false);
        } catch (IOException e) {
            return new Result(-1, "", "IOException: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "Interrupted", false);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 快速检查命令是否可用（which/where）。
     */
    public static boolean isCommandAvailable(String command) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String checker = isWindows ? "where" : "which";
        Result result = execute(List.of(checker, command), null, 5000);
        return result.isSuccess();
    }

    /**
     * 执行 shell 命令（通过 sh -c 或 cmd /c）。
     */
    public static Result executeShell(String shellCommand, Path workDir, long timeoutMs) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> command;
        if (isWindows) {
            command = List.of("cmd.exe", "/c", shellCommand);
        } else {
            command = List.of("/bin/sh", "-c", shellCommand);
        }
        return execute(command, workDir, timeoutMs);
    }
}
