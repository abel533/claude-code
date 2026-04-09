package com.claudecode.util;

import com.claudecode.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes embedded shell commands in skill/command markdown content.
 * Aligns with claude-code/src/utils/promptShellExecution.ts.
 * <p>
 * Supported syntaxes:
 * <ul>
 *   <li>Code blocks: ```! command ```</li>
 *   <li>Inline: !`command`</li>
 * </ul>
 * <p>
 * Commands are executed via the system shell (bash or powershell as specified
 * in frontmatter). Output replaces the command placeholder in the content.
 */
public final class PromptShellExecution {

    private static final Logger log = LoggerFactory.getLogger(PromptShellExecution.class);

    private PromptShellExecution() {}

    /** Pattern for code blocks: ```! command ``` */
    private static final Pattern BLOCK_PATTERN = Pattern.compile("```!\\s*\\n?([\\s\\S]*?)\\n?```");

    /** Pattern for inline: !`command` (preceded by whitespace or start-of-line) */
    private static final Pattern INLINE_PATTERN = Pattern.compile("(?<=^|\\s)!`([^`]+)`", Pattern.MULTILINE);

    /** Default command timeout in seconds */
    private static final int DEFAULT_TIMEOUT = 30;

    /**
     * Parse and execute embedded shell commands in the given text.
     * Replaces each command placeholder with its output.
     *
     * @param text    the skill/command markdown content
     * @param shell   "bash" or "powershell" (null defaults to bash)
     * @param workDir working directory for command execution
     * @return content with command outputs substituted
     */
    public static String executeShellCommandsInPrompt(String text, String shell, Path workDir) {
        if (text == null || text.isEmpty()) return text;

        // Collect all matches (block + inline)
        List<CommandMatch> matches = new ArrayList<>();

        Matcher blockMatcher = BLOCK_PATTERN.matcher(text);
        while (blockMatcher.find()) {
            String command = blockMatcher.group(1);
            if (command != null && !command.isBlank()) {
                matches.add(new CommandMatch(blockMatcher.start(), blockMatcher.end(),
                        blockMatcher.group(0), command.strip()));
            }
        }

        // Only scan for inline pattern if text contains !` (optimization from TS)
        if (text.contains("!`")) {
            Matcher inlineMatcher = INLINE_PATTERN.matcher(text);
            while (inlineMatcher.find()) {
                String command = inlineMatcher.group(1);
                if (command != null && !command.isBlank()) {
                    matches.add(new CommandMatch(inlineMatcher.start(), inlineMatcher.end(),
                            inlineMatcher.group(0), command.strip()));
                }
            }
        }

        if (matches.isEmpty()) return text;

        // Execute commands and replace (reverse order to preserve offsets)
        matches.sort((a, b) -> Integer.compare(b.start, a.start));

        String result = text;
        for (CommandMatch match : matches) {
            try {
                String output = executeShellCommand(match.command, shell, workDir);
                result = result.replace(match.fullMatch, output);
                log.debug("Shell command executed in skill: {} → {} chars output", 
                        match.command.substring(0, Math.min(50, match.command.length())), output.length());
            } catch (Exception e) {
                log.warn("Shell command failed in skill content: {}: {}", match.command, e.getMessage());
                result = result.replace(match.fullMatch,
                        "[Error executing command: " + e.getMessage() + "]");
            }
        }

        return result;
    }

    /**
     * Execute a single shell command and return its stdout.
     */
    private static String executeShellCommand(String command, String shell, Path workDir) throws IOException {
        List<String> cmd;
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if ("powershell".equalsIgnoreCase(shell)) {
            if (isWindows) {
                cmd = List.of("powershell", "-NoProfile", "-Command", command);
            } else {
                cmd = List.of("pwsh", "-NoProfile", "-Command", command);
            }
        } else {
            // Default: bash
            if (isWindows) {
                // Try bash (Git Bash / WSL), fallback to cmd
                cmd = List.of("bash", "-c", command);
            } else {
                cmd = List.of("bash", "-c", command);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(DEFAULT_TIMEOUT, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out after " + DEFAULT_TIMEOUT + "s: " + command);
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.debug("Shell command exited with code {}: {}", exitCode, command);
            }
            return output.strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted: " + command, e);
        }
    }

    private record CommandMatch(int start, int end, String fullMatch, String command) {}
}
