package com.claudecode.plugin;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.SlashCommand;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 插件管理器 —— 负责插件的加载、注册和生命周期管理。
 * <p>
 * 对应 claude-code 中的插件加载机制，支持从外部 JAR 文件动态加载插件。
 *
 * <h3>插件加载方式</h3>
 * <ol>
 *   <li><b>全局插件</b>：从 {@code ~/.claude-code-java/plugins/} 目录加载 JAR 文件</li>
 *   <li><b>项目插件</b>：从项目 {@code .claude-code/plugins/} 目录加载 JAR 文件</li>
 * </ol>
 *
 * <h3>JAR 插件要求</h3>
 * <ul>
 *   <li>{@code META-INF/MANIFEST.MF} 中必须包含 {@code Plugin-Class} 属性</li>
 *   <li>指定的类必须实现 {@link Plugin} 接口</li>
 *   <li>类必须有无参公共构造器</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>
 * 插件列表使用 {@link CopyOnWriteArrayList} 存储，支持并发读取。
 * 加载和卸载操作本身不是原子的，建议在应用启动阶段或由单一线程执行。
 * </p>
 *
 * @see Plugin
 * @see PluginContext
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    /** 已加载的插件信息列表，使用 COW 列表保证并发读安全 */
    private final List<PluginInfo> plugins = new CopyOnWriteArrayList<>();

    /** 工具执行上下文，传递给每个插件 */
    private final ToolContext toolContext;

    /** 全局插件目录：~/.claude-code-java/plugins/ */
    private final Path globalPluginDir;

    /** 项目插件目录：{project}/.claude-code/plugins/ */
    private final Path projectPluginDir;

    /**
     * 创建插件管理器。
     *
     * @param toolContext 工具执行上下文，不能为 null
     * @throws NullPointerException 如果 toolContext 为 null
     */
    public PluginManager(ToolContext toolContext) {
        this.toolContext = Objects.requireNonNull(toolContext, "toolContext cannot be null");
        this.globalPluginDir = Path.of(
                System.getProperty("user.home"), ".claude-code-java", "plugins");
        this.projectPluginDir = toolContext.getWorkDir().resolve(".claude-code").resolve("plugins");
    }

    /**
     * 扫描并加载所有插件目录中的插件。
     * <p>
     * 依次扫描全局插件目录和项目插件目录。
     * 加载失败的单个插件不会影响其他插件的加载。
     */
    public void loadAll() {
        loadFromDirectory(globalPluginDir, "global");
        loadFromDirectory(projectPluginDir, "project");
        log.info("Loaded {} plugins in total", plugins.size());
    }

    /**
     * 从指定目录扫描并加载所有 JAR 插件。
     *
     * @param dir   插件目录路径
     * @param scope 插件作用域标识（"global" 或 "project"）
     */
    private void loadFromDirectory(Path dir, String scope) {
        if (!Files.isDirectory(dir)) {
            log.debug("Plugin directory does not exist, skipping: {}", dir);
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(jar -> loadJarPlugin(jar, scope));
        } catch (IOException e) {
            log.warn("Failed to scan plugin directory: {}", dir, e);
        }
    }

    /**
     * 加载单个 JAR 插件。
     * <p>
     * 流程：
     * <ol>
     *   <li>读取 JAR 的 MANIFEST.MF，获取 Plugin-Class 属性</li>
     *   <li>使用 URLClassLoader 加载插件类</li>
     *   <li>验证插件类实现了 {@link Plugin} 接口</li>
     *   <li>实例化并初始化插件</li>
     *   <li>将插件信息添加到已加载列表</li>
     * </ol>
     *
     * @param jarPath JAR 文件路径
     * @param scope   插件作用域
     */
    private void loadJarPlugin(Path jarPath, String scope) {
        // 第一步：从 JAR 清单中读取 Plugin-Class 属性（使用 try-with-resources 确保关闭）
        String pluginClassName;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            pluginClassName = (manifest != null)
                    ? manifest.getMainAttributes().getValue("Plugin-Class")
                    : null;
        } catch (IOException e) {
            log.error("Failed to read JAR manifest: {}", jarPath.getFileName(), e);
            return;
        }

        if (pluginClassName == null) {
            log.warn("JAR {} missing Plugin-Class attribute, skipping", jarPath.getFileName());
            return;
        }

        // 第二步：加载并实例化插件（确保失败时关闭 ClassLoader）
        URLClassLoader loader = null;
        boolean success = false;
        try {
            // 创建隔离的类加载器，以当前类加载器为父加载器
            loader = new URLClassLoader(
                    new URL[]{jarPath.toUri().toURL()},
                    getClass().getClassLoader()
            );

            Class<?> clazz = loader.loadClass(pluginClassName);
            if (!Plugin.class.isAssignableFrom(clazz)) {
                log.warn("{} does not implement Plugin interface, skipping", pluginClassName);
                return;
            }

            Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();

            // 检查插件 ID 是否重复
            if (findPlugin(plugin.id()) != null) {
                log.warn("Plugin ID '{}' already exists, skipping duplicate load: {}", plugin.id(), jarPath.getFileName());
                return;
            }

            // 创建插件上下文并初始化
            PluginContext ctx = new PluginContext(
                    toolContext, toolContext.getWorkDir().toString(), plugin.id());
            plugin.initialize(ctx);

            plugins.add(new PluginInfo(plugin, scope, jarPath, loader));
            log.info("Loaded plugin: {} v{} [{}] ({})", plugin.name(), plugin.version(), plugin.id(), scope);
            success = true;

        } catch (Exception e) {
            log.error("Failed to load plugin: {}", jarPath.getFileName(), e);
        } finally {
            // 仅在加载失败时关闭类加载器；成功时由 PluginInfo 持有
            if (!success) {
                safeClose(loader);
            }
        }
    }

    /**
     * 从指定 JAR 路径加载单个插件（用于运行时动态加载）。
     *
     * @param jarPath JAR 文件路径
     * @return 加载成功返回 true，否则返回 false
     */
    public boolean loadPlugin(Path jarPath) {
        if (!Files.isRegularFile(jarPath) || !jarPath.toString().endsWith(".jar")) {
            log.warn("Invalid plugin path: {}", jarPath);
            return false;
        }
        loadJarPlugin(jarPath, "dynamic");
        // 通过检查是否有任何插件关联此 JAR 路径来判断是否加载成功
        return plugins.stream().anyMatch(info -> jarPath.equals(info.jarPath()));
    }

    /**
     * 将所有已加载插件的工具注册到 ToolRegistry。
     *
     * @param toolRegistry 工具注册中心
     */
    public void registerTools(ToolRegistry toolRegistry) {
        for (PluginInfo info : plugins) {
            for (Tool tool : info.plugin().getTools()) {
                toolRegistry.register(tool);
                log.debug("Registered plugin tool: {} (from {})", tool.name(), info.plugin().name());
            }
        }
    }

    /**
     * 将所有已加载插件的命令注册到 CommandRegistry。
     *
     * @param commandRegistry 命令注册中心
     */
    public void registerCommands(CommandRegistry commandRegistry) {
        for (PluginInfo info : plugins) {
            for (SlashCommand cmd : info.plugin().getCommands()) {
                commandRegistry.register(cmd);
                log.debug("Registered plugin command: /{} (from {})", cmd.name(), info.plugin().name());
            }
        }
    }

    /**
     * 卸载指定 ID 的插件。
     * <p>
     * 调用插件的 {@link Plugin#destroy()} 方法，并关闭其类加载器。
     * 注意：已注册到 ToolRegistry / CommandRegistry 的工具和命令不会自动移除。
     * <p>
     * 使用 {@link CopyOnWriteArrayList#remove(Object)} 而非迭代器删除，
     * 因为 CopyOnWriteArrayList 的迭代器不支持 remove 操作。
     *
     * @param pluginId 插件唯一标识
     * @return 卸载成功返回 true，未找到返回 false
     */
    public boolean unload(String pluginId) {
        for (PluginInfo info : plugins) {
            if (info.plugin().id().equals(pluginId)) {
                try {
                    info.plugin().destroy();
                } catch (Exception e) {
                    log.warn("Plugin {} exception during destroy", pluginId, e);
                }
                safeClose(info.classLoader());
                plugins.remove(info); // CopyOnWriteArrayList.remove(Object) 是安全的
                log.info("Unloaded plugin: {} ({})", info.plugin().name(), pluginId);
                return true;
            }
        }
        log.warn("Plugin not found: {}", pluginId);
        return false;
    }

    /**
     * 获取所有已加载的插件信息（只读快照）。
     *
     * @return 不可变的插件信息列表
     */
    public List<PluginInfo> getPlugins() {
        return List.copyOf(plugins);
    }

    /**
     * 根据 ID 查找已加载的插件信息。
     *
     * @param pluginId 插件唯一标识
     * @return 插件信息，未找到返回 null
     */
    public PluginInfo findPlugin(String pluginId) {
        for (PluginInfo info : plugins) {
            if (info.plugin().id().equals(pluginId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * 获取已加载插件的摘要信息。
     *
     * @return 格式化的插件列表字符串
     */
    public String getSummary() {
        if (plugins.isEmpty()) {
            return "No plugins loaded";
        }
        StringBuilder sb = new StringBuilder();
        for (PluginInfo info : plugins) {
            Plugin p = info.plugin();
            sb.append(String.format("  %s v%s [%s] - %s (tools: %d, commands: %d)%n",
                    p.name(), p.version(), info.scope(), p.description(),
                    p.getTools().size(), p.getCommands().size()));
        }
        return sb.toString();
    }

    /**
     * 关闭所有插件并清理资源。
     * <p>
     * 依次调用每个插件的 {@link Plugin#destroy()} 方法，
     * 然后关闭对应的类加载器。此方法应在应用关闭时调用。
     */
    public void shutdown() {
        log.info("Shutting down {} plugins...", plugins.size());
        for (PluginInfo info : plugins) {
            try {
                info.plugin().destroy();
            } catch (Exception e) {
                log.warn("Plugin {} exception during destroy", info.plugin().id(), e);
            }
            safeClose(info.classLoader());
        }
        plugins.clear();
        log.info("All plugins shut down");
    }

    /**
     * 安全关闭 AutoCloseable 资源，异常仅记录 DEBUG 日志。
     *
     * @param closeable 要关闭的资源，可以为 null
     */
    private void safeClose(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Exception closing resource ({}): {}",
                        closeable.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * 插件信息记录 —— 封装已加载插件的元数据。
     *
     * @param plugin      插件实例
     * @param scope       插件作用域（"global"、"project" 或 "dynamic"）
     * @param jarPath     JAR 文件路径
     * @param classLoader 插件的类加载器，内建插件为 null
     */
    public record PluginInfo(
            Plugin plugin,
            String scope,
            Path jarPath,
            URLClassLoader classLoader
    ) {
    }
}
