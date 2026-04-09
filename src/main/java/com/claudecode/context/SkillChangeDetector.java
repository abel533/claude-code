package com.claudecode.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Skill 文件变更检测器 —— 对应 claude-code/src/utils/skills/skillChangeDetector.ts。
 * <p>
 * 使用 Java WatchService 监控 skill 目录文件变更，
 * 自动触发 SkillLoader 缓存清除和重新加载。
 * <p>
 * 特性：
 * <ul>
 *   <li>300ms debounce（合并短时间内的多个文件事件）</li>
 *   <li>监控 ~/.claude/skills, .claude/skills, .claude/commands</li>
 *   <li>监控 .md 文件的创建、修改、删除</li>
 *   <li>自动调用 SkillLoader.clearCache() 触发重新加载</li>
 * </ul>
 */
public class SkillChangeDetector implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SkillChangeDetector.class);

    /** Debounce delay in milliseconds (matches TS 300ms) */
    private static final long DEBOUNCE_MS = 300;

    /** File stability check delay — wait for file writes to finish (matches TS 1s) */
    private static final long STABILITY_MS = 1000;

    private final SkillLoader skillLoader;
    private final Path projectDir;
    private final List<Consumer<Void>> changeListeners = new CopyOnWriteArrayList<>();

    private WatchService watchService;
    private final Map<WatchKey, Path> watchKeyPathMap = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pendingDebounce;
    private volatile boolean running = false;

    public SkillChangeDetector(SkillLoader skillLoader, Path projectDir) {
        this.skillLoader = skillLoader;
        this.projectDir = projectDir;
    }

    /**
     * Start watching skill directories for changes.
     * Non-blocking — starts a background thread.
     */
    public void start() {
        if (running) return;

        try {
            watchService = FileSystems.getDefault().newWatchService();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "skill-change-detector");
                t.setDaemon(true);
                return t;
            });

            // Register directories to watch
            List<Path> watchDirs = getWatchDirectories();
            for (Path dir : watchDirs) {
                registerDirectory(dir);
            }

            if (watchKeyPathMap.isEmpty()) {
                log.debug("No skill directories found to watch");
                close();
                return;
            }

            running = true;

            // Start polling thread
            Thread poller = new Thread(this::pollLoop, "skill-watcher-poll");
            poller.setDaemon(true);
            poller.start();

            log.debug("SkillChangeDetector started, watching {} directories", watchKeyPathMap.size());
        } catch (IOException e) {
            log.debug("Failed to start skill file watcher: {}", e.getMessage());
        }
    }

    /**
     * Get all directories that should be watched.
     */
    private List<Path> getWatchDirectories() {
        List<Path> dirs = new ArrayList<>();

        // User skills directory
        Path userSkills = Path.of(System.getProperty("user.home"), ".claude", "skills");
        if (Files.isDirectory(userSkills)) dirs.add(userSkills);

        // Project skills directory
        Path projectSkills = projectDir.resolve(".claude").resolve("skills");
        if (Files.isDirectory(projectSkills)) dirs.add(projectSkills);

        // Project commands directory
        Path projectCommands = projectDir.resolve(".claude").resolve("commands");
        if (Files.isDirectory(projectCommands)) dirs.add(projectCommands);

        // User agents directory
        Path userAgents = Path.of(System.getProperty("user.home"), ".claude", "agents");
        if (Files.isDirectory(userAgents)) dirs.add(userAgents);

        // Project agents directory
        Path projectAgents = projectDir.resolve(".claude").resolve("agents");
        if (Files.isDirectory(projectAgents)) dirs.add(projectAgents);

        return dirs;
    }

    /**
     * Register a directory (and its subdirectories) with the WatchService.
     */
    private void registerDirectory(Path dir) {
        try {
            WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            watchKeyPathMap.put(key, dir);
            log.debug("Watching directory: {}", dir);

            // Also register subdirectories (for skill-name/SKILL.md structure)
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).forEach(subDir -> {
                    try {
                        WatchKey subKey = subDir.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                        watchKeyPathMap.put(subKey, subDir);
                    } catch (IOException e) {
                        log.debug("Failed to watch subdirectory: {}", subDir);
                    }
                });
            }
        } catch (IOException e) {
            log.debug("Failed to register directory for watching: {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Main polling loop — runs on a daemon thread.
     */
    private void pollLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(2, TimeUnit.SECONDS);
                if (key == null) continue;

                Path dir = watchKeyPathMap.get(key);
                boolean relevant = false;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changed = dir != null ? dir.resolve(pathEvent.context()) : pathEvent.context();

                    // Only care about .md files and directories (new skill dirs)
                    String name = changed.getFileName().toString();
                    if (name.endsWith(".md") || Files.isDirectory(changed) || kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        relevant = true;
                        log.debug("Skill file change detected: {} {}", kind.name(), changed);

                        // If a new directory was created, register it for watching
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                            registerDirectory(changed);
                        }
                    }
                }

                key.reset();

                if (relevant) {
                    scheduleDebounce();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }

    /**
     * Schedule a debounced cache clear + reload.
     * Multiple rapid file changes are coalesced into a single reload.
     */
    private void scheduleDebounce() {
        // Cancel any pending debounce
        ScheduledFuture<?> pending = this.pendingDebounce;
        if (pending != null) {
            pending.cancel(false);
        }

        // Schedule new debounce (DEBOUNCE_MS + STABILITY_MS for file stability)
        this.pendingDebounce = scheduler.schedule(() -> {
            log.info("Skill files changed, reloading...");
            try {
                skillLoader.clearCache();
                skillLoader.loadAll();
                notifyListeners();
                log.info("Skills reloaded successfully ({} skills)", skillLoader.getSkills().size());
            } catch (Exception e) {
                log.warn("Failed to reload skills after file change: {}", e.getMessage());
            }
        }, DEBOUNCE_MS + STABILITY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Register a listener for skill change events.
     */
    public void onSkillsChanged(Consumer<Void> listener) {
        changeListeners.add(listener);
    }

    private void notifyListeners() {
        for (Consumer<Void> listener : changeListeners) {
            try {
                listener.accept(null);
            } catch (Exception e) {
                log.debug("Skill change listener error: {}", e.getMessage());
            }
        }
    }

    /**
     * Check if the detector is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        if (pendingDebounce != null) {
            pendingDebounce.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("Error closing WatchService: {}", e.getMessage());
            }
        }
        watchKeyPathMap.clear();
        log.debug("SkillChangeDetector stopped");
    }
}
