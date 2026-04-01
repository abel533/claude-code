package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 管理器 —— 管理多个 MCP 服务器连接的统一入口。
 * <p>
 * 职责：
 * <ul>
 *   <li>从配置文件加载 MCP 服务器定义</li>
 *   <li>管理服务器连接的生命周期（连接、断开、重连）</li>
 *   <li>聚合所有服务器的工具和资源供上层使用</li>
 *   <li>路由工具调用到正确的服务器</li>
 * </ul>
 * <p>
 * 配置文件格式（{@code mcp.json}）：
 * <pre>{@code
 * {
 *   "servers": {
 *     "server-name": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem"],
 *       "env": { "KEY": "VALUE" }
 *     }
 *   }
 * }
 * }</pre>
 *
 * @see McpClient
 * @see StdioTransport
 */
public class McpManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 全局配置文件路径：~/.claude-code-java/mcp.json */
    private static final String GLOBAL_CONFIG = ".claude-code-java/mcp.json";

    /** 项目级配置文件名 */
    private static final String PROJECT_CONFIG = ".mcp.json";

    /** 已连接的 MCP 客户端：serverName -> McpClient */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    /** 工具名称到服务器名称的映射：toolName -> serverName（用于路由调用） */
    private final Map<String, String> toolToServer = new ConcurrentHashMap<>();

    /**
     * 从配置文件加载并连接所有 MCP 服务器。
     * <p>
     * 优先级：
     * <ol>
     *   <li>项目级配置文件：当前工作目录下的 {@code .mcp.json}</li>
     *   <li>全局配置文件：{@code ~/.claude-code-java/mcp.json}</li>
     * </ol>
     * 两个配置文件中的服务器会合并加载。
     */
    public void loadFromConfig() {
        // 项目级配置
        Path projectConfig = Path.of(System.getProperty("user.dir"), PROJECT_CONFIG);
        if (Files.exists(projectConfig)) {
            loadConfigFile(projectConfig, "project");
        }

        // 全局配置
        Path globalConfig = Path.of(System.getProperty("user.home"), GLOBAL_CONFIG);
        if (Files.exists(globalConfig)) {
            loadConfigFile(globalConfig, "global");
        }

        if (clients.isEmpty()) {
            log.debug("No MCP config file found or no server definitions");
        }
    }

    /**
     * 加载单个配置文件中的 MCP 服务器定义。
     */
    private void loadConfigFile(Path configPath, String label) {
        log.info("Loading {} MCP config: {}", label, configPath);

        try {
            String content = Files.readString(configPath);
            JsonNode root = MAPPER.readTree(content);

            JsonNode serversNode = root.get("servers");
            if (serversNode == null || !serversNode.isObject()) {
                log.warn("{} config file missing 'servers' field: {}", label, configPath);
                return;
            }

            Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode serverDef = entry.getValue();

                // 跳过已存在的服务器（项目级优先于全局）
                if (clients.containsKey(name)) {
                    log.debug("MCP server '{}' already connected, skipping duplicate definition in {} config", name, label);
                    continue;
                }

                try {
                    String command = serverDef.get("command").asText();

                    List<String> args = new ArrayList<>();
                    if (serverDef.has("args") && serverDef.get("args").isArray()) {
                        for (JsonNode arg : serverDef.get("args")) {
                            args.add(arg.asText());
                        }
                    }

                    Map<String, String> env = new HashMap<>();
                    if (serverDef.has("env") && serverDef.get("env").isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> envFields = serverDef.get("env").fields();
                        while (envFields.hasNext()) {
                            Map.Entry<String, JsonNode> envEntry = envFields.next();
                            env.put(envEntry.getKey(), envEntry.getValue().asText());
                        }
                    }

                    connect(name, command, args, env);
                } catch (Exception e) {
                    log.error("Failed to connect MCP server '{}' from config: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to read MCP config file: {}", configPath, e);
        }
    }

    /**
     * 连接单个 MCP 服务器。
     *
     * @param name    服务器名称标识
     * @param command 服务器可执行命令
     * @param args    命令参数列表
     * @param env     环境变量（可为 {@code null}）
     * @return 已初始化的 MCP 客户端
     * @throws McpException 连接或初始化失败
     */
    public McpClient connect(String name, String command, List<String> args, Map<String, String> env)
            throws McpException {
        // 如果已存在，先断开
        if (clients.containsKey(name)) {
            log.info("MCP server '{}' already exists, disconnecting old connection", name);
            try {
                disconnect(name);
            } catch (Exception e) {
                log.warn("Exception disconnecting old MCP connection '{}': {}", name, e.getMessage());
            }
        }

        log.info("Connecting MCP server '{}': {} {}", name, command, String.join(" ", args));

        // 创建传输层并启动（确保初始化失败时清理资源）
        StdioTransport transport = new StdioTransport(command, args, env);
        McpClient client;
        try {
            transport.start();
            client = new McpClient(name, transport);
            client.initialize();
        } catch (Exception e) {
            // 初始化失败时必须关闭传输层，防止子进程泄漏
            try {
                transport.close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw (e instanceof McpException mcp) ? mcp
                    : new McpException("Failed to connect MCP server '" + name + "': " + e.getMessage(), e);
        }

        // 注册客户端
        clients.put(name, client);

        // 建立工具 -> 服务器的映射
        for (McpClient.McpTool tool : client.getTools()) {
            String existingServer = toolToServer.get(tool.name());
            if (existingServer != null) {
                log.warn("MCP tool name conflict: '{}' exists in both server '{}' and '{}', using latter",
                        tool.name(), existingServer, name);
            }
            toolToServer.put(tool.name(), name);
        }

        log.info("MCP server '{}' connected successfully", name);
        return client;
    }

    /**
     * 断开 MCP 服务器连接。
     *
     * @param name 服务器名称
     * @throws McpException 断开失败
     */
    public void disconnect(String name) throws McpException {
        McpClient client = clients.remove(name);
        if (client == null) {
            throw new McpException("MCP server '" + name + "' does not exist");
        }

        // 清理工具映射
        toolToServer.entrySet().removeIf(entry -> entry.getValue().equals(name));

        try {
            client.close();
            log.info("MCP server '{}' disconnected", name);
        } catch (Exception e) {
            throw new McpException("Exception disconnecting MCP server '" + name + "': " + e.getMessage(), e);
        }
    }

    /**
     * 获取所有已连接的客户端（不可变视图）。
     */
    public Map<String, McpClient> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    /**
     * 获取指定服务器的客户端。
     *
     * @param name 服务器名称
     * @return 客户端实例，若不存在则返回 {@link Optional#empty()}
     */
    public Optional<McpClient> getClient(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    /**
     * 获取所有 MCP 工具（合并所有服务器的工具）。
     *
     * @return 所有已发现的工具列表
     */
    public List<McpClient.McpTool> getAllTools() {
        return clients.values().stream()
                .filter(McpClient::isInitialized)
                .flatMap(client -> client.getTools().stream())
                .toList();
    }

    /**
     * 获取指定服务器的工具。
     *
     * @param serverName 服务器名称
     * @return 工具列表，若服务器不存在则返回空列表
     */
    public List<McpClient.McpTool> getServerTools(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null || !client.isInitialized()) {
            return List.of();
        }
        return List.copyOf(client.getTools());
    }

    /**
     * 获取所有 MCP 资源（合并所有服务器的资源）。
     *
     * @return 所有已发现的资源列表
     */
    public List<McpClient.McpResource> getAllResources() {
        return clients.values().stream()
                .filter(McpClient::isInitialized)
                .flatMap(client -> client.getResources().stream())
                .toList();
    }

    /**
     * 获取指定服务器的资源。
     *
     * @param serverName 服务器名称
     * @return 资源列表，若服务器不存在则返回空列表
     */
    public List<McpClient.McpResource> getServerResources(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null || !client.isInitialized()) {
            return List.of();
        }
        return List.copyOf(client.getResources());
    }

    /**
     * 调用 MCP 工具 —— 自动路由到拥有该工具的服务器。
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @return 工具执行结果
     * @throws McpException 工具不存在或调用失败
     */
    public String callTool(String toolName, Map<String, Object> args) throws McpException {
        String serverName = toolToServer.get(toolName);
        if (serverName == null) {
            throw new McpException("MCP tool not found: " + toolName);
        }
        return callTool(serverName, toolName, args);
    }

    /**
     * 调用指定服务器的 MCP 工具。
     *
     * @param serverName 服务器名称
     * @param toolName   工具名称
     * @param args       工具参数
     * @return 工具执行结果
     * @throws McpException 服务器不存在或调用失败
     */
    public String callTool(String serverName, String toolName, Map<String, Object> args)
            throws McpException {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new McpException("MCP server '" + serverName + "' does not exist");
        }
        if (!client.isInitialized()) {
            throw new McpException("MCP server '" + serverName + "' not yet initialized");
        }
        return client.callTool(toolName, args);
    }

    /**
     * 查找工具所属的服务器名称。
     *
     * @param toolName 工具名称
     * @return 服务器名称，若不存在则返回 {@link Optional#empty()}
     */
    public Optional<String> findServerForTool(String toolName) {
        return Optional.ofNullable(toolToServer.get(toolName));
    }

    /**
     * 重新加载配置文件并重连所有服务器。
     * <p>
     * 先断开所有已有连接，再重新加载配置。
     */
    public void reload() {
        log.info("Reloading MCP config...");

        // 断开所有现有连接
        List<String> serverNames = new ArrayList<>(clients.keySet());
        for (String name : serverNames) {
            try {
                disconnect(name);
            } catch (Exception e) {
                log.warn("Failed to disconnect MCP server '{}' during reload: {}", name, e.getMessage());
            }
        }

        // 重新加载
        loadFromConfig();
        log.info("MCP config reload complete: {} servers connected", clients.size());
    }

    /**
     * 获取状态摘要（用于 /mcp 命令或状态显示）。
     *
     * @return 格式化的状态摘要文本
     */
    public String getSummary() {
        if (clients.isEmpty()) {
            return "  No connected MCP servers";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            String name = entry.getKey();
            McpClient client = entry.getValue();

            String status;
            if (client.isConnected() && client.isInitialized()) {
                status = "✅ Connected";
            } else if (client.isConnected()) {
                status = "🔄 Connecting";
            } else {
                status = "❌ Disconnected";
            }

            sb.append(String.format("  %-20s %s (%d tools, %d resources)%n",
                    name, status, client.getTools().size(), client.getResources().size()));
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public void close() throws Exception {
        log.info("Closing all MCP connections...");
        List<Exception> errors = new ArrayList<>();

        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                errors.add(e);
                log.error("Exception closing MCP server '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        clients.clear();
        toolToServer.clear();

        if (!errors.isEmpty()) {
            McpException ex = new McpException("Errors closing MCP manager: " + errors.size() + " errors");
            errors.forEach(ex::addSuppressed);
            throw ex;
        }

        log.info("All MCP connections closed");
    }
}
