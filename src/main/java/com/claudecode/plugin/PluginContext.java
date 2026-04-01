package com.claudecode.plugin;

import com.claudecode.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 插件上下文 —— 为插件提供访问应用核心功能的接口。
 * <p>
 * 每个插件在初始化时会收到一个专属的 {@code PluginContext} 实例，
 * 包含：
 * <ul>
 *   <li>{@link ToolContext} —— 工具执行上下文（工作目录、共享状态）</li>
 *   <li>工作目录路径</li>
 *   <li>插件专属日志器 —— 日志前缀为 "plugin.{pluginId}"</li>
 * </ul>
 *
 * <p>此类的引用字段在构造后不可变（shallowly immutable），但持有的
 * {@link ToolContext} 本身是可变的，多个插件共享同一实例。</p>
 */
public class PluginContext {

    private final ToolContext toolContext;
    private final String workDir;
    private final Logger pluginLogger;

    /**
     * 创建插件上下文。
     *
     * @param toolContext 工具执行上下文，不能为 null
     * @param workDir     当前工作目录路径，不能为 null
     * @param pluginId    插件标识，用于创建专属日志器，不能为 null
     * @throws NullPointerException 如果任何参数为 null
     */
    public PluginContext(ToolContext toolContext, String workDir, String pluginId) {
        this.toolContext = Objects.requireNonNull(toolContext, "toolContext cannot be null");
        this.workDir = Objects.requireNonNull(workDir, "workDir cannot be null");
        Objects.requireNonNull(pluginId, "pluginId cannot be null");
        this.pluginLogger = LoggerFactory.getLogger("plugin." + pluginId);
    }

    /**
     * 获取工具执行上下文。
     * <p>
     * 通过 ToolContext 可以访问工作目录、模型信息和共享状态。
     *
     * @return 工具执行上下文
     */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /**
     * 获取当前工作目录路径。
     *
     * @return 工作目录的绝对路径字符串
     */
    public String getWorkDir() {
        return workDir;
    }

    /**
     * 获取插件专属日志器。
     * <p>
     * 日志器名称格式为 "plugin.{pluginId}"，方便在日志中区分不同插件的输出。
     *
     * @return SLF4J Logger 实例
     */
    public Logger getLogger() {
        return pluginLogger;
    }
}
