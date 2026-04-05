package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * /heapdump 命令 —— JVM 堆转储（Java 独有优势）。
 */
public class HeapdumpCommand implements SlashCommand {

    @Override
    public String name() { return "heapdump"; }

    @Override
    public String description() { return "Generate JVM heap dump (Java advantage)"; }

    @Override
    public String execute(String args, CommandContext context) {
        String trimmed = CommandUtils.parseArgs(args);

        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("📦", "JVM Heap Dump"));

        if (trimmed.equals("info") || trimmed.isEmpty()) {
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

            sb.append(CommandUtils.subtitle("Heap Memory")).append("\n");
            sb.append("  Used:      ").append(CommandUtils.formatBytes(heap.getUsed())).append("\n");
            sb.append("  Committed: ").append(CommandUtils.formatBytes(heap.getCommitted())).append("\n");
            sb.append("  Max:       ").append(CommandUtils.formatBytes(heap.getMax())).append("\n\n");

            sb.append(CommandUtils.subtitle("Non-Heap Memory")).append("\n");
            sb.append("  Used:      ").append(CommandUtils.formatBytes(nonHeap.getUsed())).append("\n");
            sb.append("  Committed: ").append(CommandUtils.formatBytes(nonHeap.getCommitted())).append("\n\n");

            sb.append(CommandUtils.subtitle("Memory Pools")).append("\n");
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage usage = pool.getUsage();
                if (usage != null && usage.getUsed() > 0) {
                    sb.append("  ").append(String.format("%-25s", pool.getName()))
                            .append(CommandUtils.formatBytes(usage.getUsed())).append("\n");
                }
            }
            sb.append("\n").append(AnsiStyle.dim("  Run /heapdump dump to generate a heap dump file"));

        } else if (trimmed.startsWith("dump")) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String filename = trimmed.length() > 5 ? trimmed.substring(5).trim() : "";
            if (filename.isEmpty()) filename = "heapdump-" + timestamp + ".hprof";
            Path dumpPath = Path.of(System.getProperty("user.dir"), filename);

            try {
                var hotspot = ManagementFactory.getPlatformMXBean(
                        com.sun.management.HotSpotDiagnosticMXBean.class);
                hotspot.dumpHeap(dumpPath.toString(), true);
                long fileSize = dumpPath.toFile().length();
                sb.append(CommandUtils.success("Heap dump saved to:")).append("\n");
                sb.append("  ").append(AnsiStyle.cyan(dumpPath.toString())).append("\n");
                sb.append("  Size: ").append(CommandUtils.formatBytes(fileSize)).append("\n\n");
                sb.append(AnsiStyle.dim("  Analyze with: jhat, MAT, or VisualVM"));
            } catch (Exception e) {
                sb.append(CommandUtils.error("Failed to create heap dump: " + e.getMessage())).append("\n");
                sb.append(AnsiStyle.dim("  Requires HotSpot JVM (OpenJDK or Oracle JDK)"));
            }

        } else if (trimmed.equals("gc")) {
            long beforeUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            System.gc();
            long afterUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long freed = beforeUsed - afterUsed;

            sb.append("  🗑 Garbage collection triggered\n");
            sb.append("  Before: ").append(CommandUtils.formatBytes(beforeUsed)).append("\n");
            sb.append("  After:  ").append(CommandUtils.formatBytes(afterUsed)).append("\n");
            sb.append("  Freed:  ").append(AnsiStyle.green(CommandUtils.formatBytes(Math.max(0, freed)))).append("\n");

        } else {
            sb.append(CommandUtils.subtitle("Subcommands")).append("\n");
            sb.append("  /heapdump         Show memory pool info\n");
            sb.append("  /heapdump dump    Generate .hprof file\n");
            sb.append("  /heapdump gc      Trigger garbage collection\n");
        }

        return sb.toString();
    }
}
