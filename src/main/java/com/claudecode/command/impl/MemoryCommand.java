package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * /memory 命令 —— 查看和编辑 CLAUDE.md 记忆文件。
 * <p>
 * 对应 claude-code 的 /memory 命令，支持：
 * <ul>
 *   <li>/memory —— 显示当前 CLAUDE.md 内容</li>
 *   <li>/memory add [内容] —— 追加内容到项目级 CLAUDE.md</li>
 *   <li>/memory edit —— 用系统编辑器打开 CLAUDE.md</li>
 *   <li>/memory user —— 查看用户级 CLAUDE.md</li>
 * </ul>
 */
public class MemoryCommand implements SlashCommand {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "View/edit CLAUDE.md memory files";
    }

    @Override
    public List<String> aliases() {
        return List.of("mem");
    }

    @Override
    public String execute(String args, CommandContext context) {
        args = args == null ? "" : args.strip();

        if (args.startsWith("add ")) {
            return handleAdd(args.substring(4).strip());
        } else if (args.equals("edit")) {
            return handleEdit();
        } else if (args.equals("user")) {
            return showUserMemory();
        } else {
            return showProjectMemory();
        }
    }

    /** 显示项目级 CLAUDE.md */
    private String showProjectMemory() {
        Path projectClaudeMd = Path.of(System.getProperty("user.dir"), "CLAUDE.md");
        return showMemoryFile(projectClaudeMd, "Project");
    }

    /** 显示用户级 CLAUDE.md */
    private String showUserMemory() {
        Path userClaudeMd = Path.of(System.getProperty("user.home"), ".claude", "CLAUDE.md");
        return showMemoryFile(userClaudeMd, "User");
    }

    private String showMemoryFile(Path path, String level) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📝 CLAUDE.md (" + level + ")\n"));
        sb.append("  ").append("─".repeat(50)).append("\n");
        sb.append("  ").append(AnsiStyle.dim("Path: " + path)).append("\n\n");

        if (Files.exists(path)) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.isBlank()) {
                    sb.append(AnsiStyle.dim("  (File is empty)\n"));
                } else {
                    content.lines().forEach(line -> sb.append("  ").append(line).append("\n"));
                }
            } catch (IOException e) {
                sb.append(AnsiStyle.red("  ✗ Read failed: " + e.getMessage() + "\n"));
            }
        } else {
            sb.append(AnsiStyle.dim("  (File does not exist)\n\n"));
            sb.append(AnsiStyle.dim("  Use /memory add <content> to create and add content\n"));
            sb.append(AnsiStyle.dim("  Or use /init command to initialize\n"));
        }

        return sb.toString();
    }

    /** 追加内容到项目级 CLAUDE.md */
    private String handleAdd(String content) {
        if (content.isEmpty()) {
            return AnsiStyle.yellow("  ⚠ Please provide content: /memory add <content>");
        }

        Path projectClaudeMd = Path.of(System.getProperty("user.dir"), "CLAUDE.md");
        try {
            // 确保文件存在
            if (!Files.exists(projectClaudeMd)) {
                Files.writeString(projectClaudeMd,
                        "# CLAUDE.md\n\n" + content + "\n",
                        StandardCharsets.UTF_8);
                return AnsiStyle.green("  ✓ Created CLAUDE.md and added content");
            }

            // 追加内容
            String existing = Files.readString(projectClaudeMd, StandardCharsets.UTF_8);
            String newContent = existing.endsWith("\n") ? existing + "\n" + content + "\n" : existing + "\n\n" + content + "\n";
            Files.writeString(projectClaudeMd, newContent, StandardCharsets.UTF_8);

            return AnsiStyle.green("  ✓ Content appended to CLAUDE.md");
        } catch (IOException e) {
            return AnsiStyle.red("  ✗ Write failed: " + e.getMessage());
        }
    }

    /** 用系统编辑器打开 CLAUDE.md */
    private String handleEdit() {
        Path projectClaudeMd = Path.of(System.getProperty("user.dir"), "CLAUDE.md");
        try {
            if (!Files.exists(projectClaudeMd)) {
                Files.writeString(projectClaudeMd, "# CLAUDE.md\n\n", StandardCharsets.UTF_8);
            }

            // 尝试用系统编辑器打开
            String editor = System.getenv("EDITOR");
            if (editor == null || editor.isBlank()) {
                editor = System.getenv("VISUAL");
            }

            if (editor != null && !editor.isBlank()) {
                ProcessBuilder pb = new ProcessBuilder(editor, projectClaudeMd.toString());
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
                return AnsiStyle.green("  ✓ Editor closed");
            }

            // Windows: 尝试 notepad
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                ProcessBuilder pb = new ProcessBuilder("notepad", projectClaudeMd.toString());
                pb.start(); // 不等待
                return AnsiStyle.green("  ✓ Opened CLAUDE.md with Notepad");
            }

            return AnsiStyle.yellow("  ⚠ No editor found. Set EDITOR environment variable, or manually edit:\n  " + projectClaudeMd);

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Failed to open editor: " + e.getMessage());
        }
    }
}
