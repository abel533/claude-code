package com.claudecode.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 插件自动更新器 —— 每日检查已安装插件的更新。
 * <p>
 * 对应 claude-code 中插件自动更新逻辑。
 * <p>
 * 特性：
 * <ul>
 *   <li>可配置检查间隔（默认24小时）</li>
 *   <li>只通知，不自动安装（除非配置 autoInstall=true）</li>
 *   <li>并行检查所有已安装插件</li>
 *   <li>记录上次检查时间，避免频繁检查</li>
 * </ul>
 */
public class PluginAutoUpdate {

    private static final Logger log = LoggerFactory.getLogger(PluginAutoUpdate.class);

    private final PluginInstaller installer;
    private final PluginManager pluginManager;
    private final String marketplaceUrl;

    /** 是否自动安装更新 */
    private boolean autoInstall = false;

    /** 检查间隔 */
    private Duration checkInterval = Duration.ofHours(24);

    /** 上次检查时间 */
    private Instant lastCheckTime = Instant.EPOCH;

    /** 可用更新缓存 */
    private final ConcurrentHashMap<String, PluginInstaller.UpdateCheckResult> pendingUpdates =
            new ConcurrentHashMap<>();

    /** 更新通知回调 */
    private UpdateNotificationCallback notificationCallback;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "plugin-auto-update");
                t.setDaemon(true);
                return t;
            });

    public PluginAutoUpdate(PluginInstaller installer, PluginManager pluginManager) {
        this(installer, pluginManager, "https://marketplace.claude-code.dev/api/v1");
    }

    public PluginAutoUpdate(PluginInstaller installer, PluginManager pluginManager,
                            String marketplaceUrl) {
        this.installer = installer;
        this.pluginManager = pluginManager;
        this.marketplaceUrl = marketplaceUrl;
    }

    /**
     * 启动定期检查。
     */
    public void start() {
        long intervalHours = checkInterval.toHours();
        scheduler.scheduleWithFixedDelay(this::checkForUpdates,
                1, intervalHours, TimeUnit.HOURS);
        log.info("Plugin auto-update started (interval: {}h)", intervalHours);
    }

    /**
     * 停止定期检查。
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 立即检查所有已安装插件的更新。
     */
    public Map<String, PluginInstaller.UpdateCheckResult> checkForUpdates() {
        Map<String, PluginInstaller.InstalledPluginInfo> installed = installer.listInstalled();
        if (installed.isEmpty()) {
            log.debug("No installed plugins to check for updates");
            return Map.of();
        }

        log.info("Checking updates for {} plugins...", installed.size());

        // 并行检查所有插件
        List<CompletableFuture<PluginInstaller.UpdateCheckResult>> futures = new ArrayList<>();
        for (var entry : installed.values()) {
            futures.add(installer.checkUpdate(
                    entry.id(), entry.version(), marketplaceUrl));
        }

        // 等待所有结果
        Map<String, PluginInstaller.UpdateCheckResult> results = new HashMap<>();
        for (var future : futures) {
            try {
                var result = future.get(30, TimeUnit.SECONDS);
                if (result.hasUpdate()) {
                    results.put(result.pluginId(), result);
                    pendingUpdates.put(result.pluginId(), result);
                    log.info("Update available: {} {} → {}",
                            result.pluginId(), result.currentVersion(), result.latestVersion());

                    // 自动安装
                    if (autoInstall && result.downloadUrl() != null) {
                        var installed2 = installer.listInstalled().get(result.pluginId());
                        if (installed2 != null) {
                            var installResult = installer.install(
                                    result.downloadUrl(), installed2.scope());
                            log.info("Auto-updated {}: {}", result.pluginId(), installResult.message());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Update check failed: {}", e.getMessage());
            }
        }

        lastCheckTime = Instant.now();

        // 通知回调
        if (!results.isEmpty() && notificationCallback != null) {
            notificationCallback.onUpdatesAvailable(results);
        }

        log.info("Update check complete: {} updates available", results.size());
        return results;
    }

    /**
     * 获取待处理的更新列表。
     */
    public Map<String, PluginInstaller.UpdateCheckResult> getPendingUpdates() {
        return Map.copyOf(pendingUpdates);
    }

    /**
     * 清除指定插件的待更新标记。
     */
    public void clearPendingUpdate(String pluginId) {
        pendingUpdates.remove(pluginId);
    }

    /**
     * 是否需要检查（距上次检查超过 interval）。
     */
    public boolean shouldCheck() {
        return Duration.between(lastCheckTime, Instant.now()).compareTo(checkInterval) > 0;
    }

    // ==================== 配置 ====================

    public void setAutoInstall(boolean autoInstall) {
        this.autoInstall = autoInstall;
    }

    public void setCheckInterval(Duration interval) {
        this.checkInterval = interval;
    }

    public void setNotificationCallback(UpdateNotificationCallback callback) {
        this.notificationCallback = callback;
    }

    public Instant getLastCheckTime() {
        return lastCheckTime;
    }

    // ==================== 回调接口 ====================

    @FunctionalInterface
    public interface UpdateNotificationCallback {
        void onUpdatesAvailable(Map<String, PluginInstaller.UpdateCheckResult> updates);
    }
}
