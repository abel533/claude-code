package com.claudecode.plugin;

import com.claudecode.tool.Tool;
import com.claudecode.command.SlashCommand;

import java.util.List;

/**
 * 插件接口 —— 对应 claude-code 中的 plugins/ 模块。
 * <p>
 * 插件可以提供额外的工具和命令，扩展核心功能。
 * 每个插件有独立的生命周期：初始化 → 运行 → 销毁。
 *
 * <h3>实现指南</h3>
 * <ul>
 *   <li>每个插件必须有唯一的 {@link #id()}，推荐使用 kebab-case 格式（如 "my-plugin"）</li>
 *   <li>{@link #initialize(PluginContext)} 在加载时调用一次，用于初始化资源</li>
 *   <li>{@link #getTools()} 和 {@link #getCommands()} 返回插件提供的扩展</li>
 *   <li>{@link #destroy()} 在卸载时调用，释放资源</li>
 * </ul>
 *
 * <h3>JAR 插件打包</h3>
 * <p>
 * 打包为 JAR 时需要在 {@code META-INF/MANIFEST.MF} 中指定：
 * <pre>
 * Plugin-Class: com.example.MyPlugin
 * </pre>
 */
public interface Plugin {

    /**
     * 插件唯一标识。
     * <p>
     * 推荐使用 kebab-case 格式，如 "output-style"、"git-helper"。
     * 标识在整个应用生命周期内必须唯一。
     *
     * @return 非空的插件标识字符串
     */
    String id();

    /**
     * 插件显示名称。
     *
     * @return 人类可读的插件名称
     */
    String name();

    /**
     * 插件版本号。
     * <p>
     * 推荐使用语义化版本号（SemVer），如 "1.0.0"。
     *
     * @return 版本号字符串
     */
    String version();

    /**
     * 插件功能描述。
     *
     * @return 简短的功能描述
     */
    String description();

    /**
     * 初始化插件。
     * <p>
     * 在插件加载后立即调用，用于：
     * <ul>
     *   <li>初始化内部状态和资源</li>
     *   <li>读取配置</li>
     *   <li>建立外部连接</li>
     * </ul>
     * 如果初始化失败应抛出异常，插件将不会被注册。
     *
     * @param context 插件上下文，提供访问应用核心功能的接口
     * @throws RuntimeException 初始化失败时抛出
     */
    void initialize(PluginContext context);

    /**
     * 获取插件提供的工具列表。
     * <p>
     * 返回的工具将被注册到 {@link com.claudecode.tool.ToolRegistry}，
     * 可供 LLM 调用。
     *
     * @return 工具列表，默认为空列表
     */
    default List<Tool> getTools() {
        return List.of();
    }

    /**
     * 获取插件提供的斜杠命令列表。
     * <p>
     * 返回的命令将被注册到 {@link com.claudecode.command.CommandRegistry}，
     * 用户可通过 /{@code name} 调用。
     *
     * @return 命令列表，默认为空列表
     */
    default List<SlashCommand> getCommands() {
        return List.of();
    }

    /**
     * 销毁插件，释放资源。
     * <p>
     * 在插件卸载或应用关闭时调用。实现应：
     * <ul>
     *   <li>关闭打开的连接和流</li>
     *   <li>停止后台线程</li>
     *   <li>释放外部资源</li>
     * </ul>
     * 此方法不应抛出异常。
     */
    default void destroy() {
    }
}
