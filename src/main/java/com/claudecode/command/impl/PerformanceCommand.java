package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.telemetry.MetricsCollector;

/**
 * /performance 命令 —— 性能统计。
 */
public class PerformanceCommand implements SlashCommand {

    @Override
    public String name() { return "performance"; }

    @Override
    public String description() { return "Show performance statistics"; }

    @Override
    public java.util.List<String> aliases() {
        return java.util.List.of("perf");
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("⚡", "Performance Statistics"));

        Runtime runtime = Runtime.getRuntime();
        long totalMem = runtime.totalMemory();
        long freeMem = runtime.freeMemory();
        long usedMem = totalMem - freeMem;
        long maxMem = runtime.maxMemory();

        sb.append(CommandUtils.subtitle("Memory")).append("\n");
        sb.append("  Used:      ").append(CommandUtils.formatBytes(usedMem)).append("\n");
        sb.append("  Allocated: ").append(CommandUtils.formatBytes(totalMem)).append("\n");
        sb.append("  Max:       ").append(CommandUtils.formatBytes(maxMem)).append("\n");
        sb.append("  Usage:     ").append(CommandUtils.progressBar((double) usedMem / maxMem, 20)).append("\n\n");

        int threadCount = Thread.activeCount();
        sb.append(CommandUtils.subtitle("Threads")).append("\n");
        sb.append("  Active:    ").append(threadCount).append("\n");
        sb.append("  Available: ").append(runtime.availableProcessors()).append(" CPUs\n\n");

        long gcCount = 0;
        long gcTime = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
            gcTime += gc.getCollectionTime();
        }
        sb.append(CommandUtils.subtitle("GC")).append("\n");
        sb.append("  Collections: ").append(gcCount).append("\n");
        sb.append("  Total time:  ").append(CommandUtils.formatMillis(gcTime)).append("\n\n");

        if (context.agentLoop() != null) {
            Object metricsObj = context.agentLoop().getToolContext().get("METRICS_COLLECTOR");
            if (metricsObj instanceof MetricsCollector metrics) {
                sb.append(CommandUtils.subtitle("Session Metrics")).append("\n");
                sb.append("  Duration:    ").append(CommandUtils.formatDuration(metrics.getSessionDurationSeconds())).append("\n");
                var toolUsage = metrics.getToolUsage();
                if (!toolUsage.isEmpty()) {
                    sb.append("  Tool calls:  ").append(toolUsage.values().stream().mapToLong(Long::longValue).sum()).append("\n");
                    sb.append("  Top tools:   ");
                    toolUsage.entrySet().stream()
                            .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                            .limit(3)
                            .forEach(e -> sb.append(e.getKey()).append("(").append(e.getValue()).append(") "));
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }
}
