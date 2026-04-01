package com.claudecode.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 任务管理器 —— 对应 claude-code 中的 TaskCreate / TaskGet / TaskList / TaskUpdate 功能。
 * <p>
 * 管理后台任务的创建、执行、查询和更新。
 * 所有公共方法均线程安全，内部使用 {@link ConcurrentHashMap} 存储任务、
 * 使用守护线程池异步执行带有 {@link Callable} 工作体的任务。
 * </p>
 *
 * <h3>两种创建模式</h3>
 * <ul>
 *   <li>{@link #createTask(String, Callable)} —— 自动执行模式：提交后立即在后台线程池中运行</li>
 *   <li>{@link #createManualTask(String)} —— 手动管理模式：仅创建 PENDING 状态的记录，
 *       由外部通过 {@link #updateTask} 驱动状态流转</li>
 * </ul>
 */
public class TaskManager {

    /* ------------------------------------------------------------------ */
    /*  任务状态枚举                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 任务生命周期状态。
     */
    public enum TaskStatus {
        /** 已创建，等待执行 */
        PENDING,
        /** 正在执行 */
        RUNNING,
        /** 执行成功完成 */
        COMPLETED,
        /** 执行失败 */
        FAILED,
        /** 已被取消 */
        CANCELLED
    }

    /* ------------------------------------------------------------------ */
    /*  任务信息记录                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 不可变的任务快照。每次状态变更都会创建新的 {@code TaskInfo} 实例写入映射表，
     * 从而保证读取端永远拿到一致的快照。
     *
     * @param id          任务唯一 ID（UUID 前 8 位）
     * @param description 任务描述
     * @param status      当前状态
     * @param result      执行结果（可为 {@code null}）
     * @param createdAt   创建时间
     * @param updatedAt   最后更新时间
     * @param metadata    附加元数据（不可变视图）
     */
    public record TaskInfo(
            String id,
            String description,
            TaskStatus status,
            String result,
            Instant createdAt,
            Instant updatedAt,
            Map<String, String> metadata
    ) {
        /**
         * 创建一个更新了状态、结果和时间戳的新快照。
         */
        public TaskInfo withStatusAndResult(TaskStatus newStatus, String newResult) {
            return new TaskInfo(
                    id, description, newStatus, newResult,
                    createdAt, Instant.now(),
                    metadata
            );
        }

        /**
         * 创建一个仅更新了状态和时间戳的新快照。
         */
        public TaskInfo withStatus(TaskStatus newStatus) {
            return withStatusAndResult(newStatus, result);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  内部状态                                                           */
    /* ------------------------------------------------------------------ */

    /** 任务存储：taskId → TaskInfo（不可变快照） */
    private final ConcurrentHashMap<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    /** 自动执行模式下对应的 Future，用于取消 */
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();

    /** 守护线程池，用于执行自动任务 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-worker-" + Thread.currentThread().threadId());
        t.setDaemon(true);
        return t;
    });

    /* ------------------------------------------------------------------ */
    /*  创建任务                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 创建并自动执行一个后台任务。
     * <p>
     * 任务会立即被提交到线程池执行；执行完成后状态自动变为 COMPLETED 或 FAILED。
     * </p>
     *
     * @param description 任务描述
     * @param work        要执行的工作体
     * @return 生成的任务 ID（UUID 前 8 位）
     * @throws NullPointerException 若 description 或 work 为 null
     */
    public String createTask(String description, Callable<String> work) {
        Objects.requireNonNull(description, "任务描述不能为 null");
        Objects.requireNonNull(work, "任务工作体不能为 null");

        String taskId = generateId();
        Instant now = Instant.now();

        TaskInfo info = new TaskInfo(
                taskId, description, TaskStatus.PENDING, null,
                now, now, Collections.emptyMap()
        );
        tasks.put(taskId, info);

        // 提交到线程池异步执行
        Future<?> future = executor.submit(() -> {
            // 标记为 RUNNING
            tasks.computeIfPresent(taskId, (id, old) -> old.withStatus(TaskStatus.RUNNING));
            try {
                String result = work.call();
                // 标记为 COMPLETED
                tasks.computeIfPresent(taskId, (id, old) ->
                        old.withStatusAndResult(TaskStatus.COMPLETED, result));
            } catch (Exception e) {
                // 标记为 FAILED，记录异常信息
                String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                tasks.computeIfPresent(taskId, (id, old) ->
                        old.withStatusAndResult(TaskStatus.FAILED, errorMsg));
            }
        });
        futures.put(taskId, future);

        return taskId;
    }

    /**
     * 创建并自动执行一个带元数据的后台任务。
     *
     * @param description 任务描述
     * @param work        要执行的工作体
     * @param metadata    附加元数据
     * @return 生成的任务 ID
     */
    public String createTask(String description, Callable<String> work,
                             Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "元数据不能为 null");
        String taskId = createTask(description, work);
        // 把元数据补充进去（创建时尚处于 PENDING/RUNNING 初期阶段）
        tasks.computeIfPresent(taskId, (id, old) -> new TaskInfo(
                old.id(), old.description(), old.status(), old.result(),
                old.createdAt(), old.updatedAt(),
                Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
        ));
        return taskId;
    }

    /**
     * 创建一个手动管理的任务（不自动执行）。
     * <p>
     * 初始状态为 PENDING，需要外部通过 {@link #updateTask} 手动驱动状态变化。
     * </p>
     *
     * @param description 任务描述
     * @return 生成的任务 ID
     */
    public String createManualTask(String description) {
        return createManualTask(description, Collections.emptyMap());
    }

    /**
     * 创建一个带元数据的手动管理任务。
     *
     * @param description 任务描述
     * @param metadata    附加元数据
     * @return 生成的任务 ID
     */
    public String createManualTask(String description, Map<String, String> metadata) {
        Objects.requireNonNull(description, "任务描述不能为 null");

        String taskId = generateId();
        Instant now = Instant.now();

        Map<String, String> metaCopy = (metadata == null || metadata.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));

        TaskInfo info = new TaskInfo(
                taskId, description, TaskStatus.PENDING, null,
                now, now, metaCopy
        );
        tasks.put(taskId, info);
        return taskId;
    }

    /* ------------------------------------------------------------------ */
    /*  查询任务                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 获取指定任务的信息快照。
     *
     * @param taskId 任务 ID
     * @return 包含任务信息的 Optional，不存在时返回 empty
     */
    public Optional<TaskInfo> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * 列出所有任务。
     *
     * @return 任务列表（按创建时间升序）
     */
    public List<TaskInfo> listTasks() {
        return listTasks(null);
    }

    /**
     * 列出任务，可按状态过滤。
     *
     * @param statusFilter 状态过滤器，{@code null} 表示不过滤
     * @return 符合条件的任务列表（按创建时间升序）
     */
    public List<TaskInfo> listTasks(TaskStatus statusFilter) {
        return tasks.values().stream()
                .filter(t -> statusFilter == null || t.status() == statusFilter)
                .sorted(Comparator.comparing(TaskInfo::createdAt))
                .collect(Collectors.toUnmodifiableList());
    }

    /* ------------------------------------------------------------------ */
    /*  更新任务                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 更新任务的状态和结果。
     *
     * @param taskId    任务 ID
     * @param newStatus 新状态
     * @param result    执行结果（可为 null）
     * @return 如果任务存在且更新成功返回 {@code true}；否则 {@code false}
     */
    public boolean updateTask(String taskId, TaskStatus newStatus, String result) {
        if (taskId == null || newStatus == null) {
            return false;
        }

        TaskInfo existing = tasks.get(taskId);
        if (existing == null) {
            return false;
        }

        // 不允许对已终态（COMPLETED / FAILED / CANCELLED）的任务再次更新
        if (isTerminal(existing.status())) {
            return false;
        }

        tasks.computeIfPresent(taskId, (id, old) ->
                old.withStatusAndResult(newStatus, result));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  取消任务                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 取消指定任务。
     * <p>
     * 如果任务由线程池执行且尚未完成，会尝试中断执行线程。
     * </p>
     *
     * @param taskId 任务 ID
     * @return 如果任务存在且成功取消返回 {@code true}
     */
    public boolean cancelTask(String taskId) {
        if (taskId == null) {
            return false;
        }

        TaskInfo existing = tasks.get(taskId);
        if (existing == null) {
            return false;
        }

        // 已处于终态则无需取消
        if (isTerminal(existing.status())) {
            return false;
        }

        // 尝试中断线程池中正在运行的 Future
        Future<?> future = futures.remove(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }

        tasks.computeIfPresent(taskId, (id, old) ->
                old.withStatus(TaskStatus.CANCELLED));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  汇总信息                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 返回当前所有任务的汇总字符串，适合用于 UI 展示。
     *
     * @return 格式化的汇总信息
     */
    public String getSummary() {
        if (tasks.isEmpty()) {
            return "当前没有任何任务。";
        }

        Map<TaskStatus, Long> counts = tasks.values().stream()
                .collect(Collectors.groupingBy(TaskInfo::status, Collectors.counting()));

        StringBuilder sb = new StringBuilder();
        sb.append("任务汇总 (共 ").append(tasks.size()).append(" 个):\n");
        for (TaskStatus status : TaskStatus.values()) {
            long count = counts.getOrDefault(status, 0L);
            if (count > 0) {
                sb.append("  ").append(status.name()).append(": ").append(count).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /* ------------------------------------------------------------------ */
    /*  生命周期管理                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 关闭执行器。等待最多 5 秒让正在运行的任务完成，超时后强制关闭。
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  内部辅助方法                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 生成任务 ID：取 UUID 前 8 位。
     */
    private String generateId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 判断状态是否为终态。
     */
    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }
}
