> **📌 仓库分支说明**
>
> 本仓库包含三个独立分支，围绕 Claude Code 展开不同维度的工作：
>
> | 分支 | 说明 |
> |------|------|
> | [`main`](../../tree/main) | **Claude Code Java** — 使用 Java + Spring AI 重写的 Claude Code CLI AI 编码助手（当前分支） |
> | [`claude`](../../tree/claude) | **Claude Code 源码** — Claude Code TypeScript 源码快照，用于安全研究和架构学习 |
> | [`learn`](../../tree/learn) | **Learn Claude Code** — 拆解 Claude Code Agent Harness 架构的教学项目，含 12 节渐进式课程 |

# Claude Code Java ☕

> 使用 Java + Spring AI 重写的 [Claude Code](https://github.com/anthropics/claude-code) CLI AI 编码助手。

## ✨ 功能特性

### 核心功能
- 🤖 **AI 对话** — 流式输出，逐 token 实时显示
- 🔧 **18 个内置工具** — Bash、文件操作、搜索、Web、任务管理、MCP桥接 等
- ⚡ **双 API 提供者** — 同时支持 OpenAI 兼容 API 和 Anthropic 原生 API
- 📝 **28 个斜杠命令** — 从基础操作到代码审查、安全分析、对话管理
- 🖥️ **JLine 终端** — 行编辑、历史记录、Tab 补全、多行输入、Vim 模式
- 📋 **CLAUDE.md** — 自动加载项目级和用户级记忆文件
- 🎯 **Skills** — 可扩展的技能系统
- 🌿 **Git 上下文** — 自动收集分支、状态、最近提交

### P0 核心增强
- 🔒 **多级权限管理** — 5 种模式（DEFAULT/ACCEPT_EDITS/BYPASS/DONT_ASK/PLAN），规则引擎自动评估，30+ 危险命令检测，Y/A/N/D 四选项 UI，拒绝追踪（连续 3 次 / 累计 20 次自动降级）
- 🗜️ **三层上下文压缩** — 微压缩（tool result 截断 + 时间感知）→ Session Memory（AI 摘要保留近期段）→ 全量压缩（PTL gap 解析 + 熔断器），93% 自动触发
- 💭 **Thinking 显示** — 展示 AI 思考过程（Anthropic extended thinking）
- 🔍 **WebSearch** — DuckDuckGo 网络搜索（无需 API Key）
- ❓ **AskUser** — AI 在执行过程中向用户提问

### P1 体验增强
- 📊 **底部状态行** — 持续显示模型、Token、费用、工作目录、token 使用率（4 级颜色：绿→黄→红→⚠闪烁）
- 🪝 **Hook 系统** — PreToolUse/PostToolUse/PrePrompt/PostResponse 4 种钩子
- 🎨 **代码语法高亮** — 支持 Java/JS/TS/Python/Bash/SQL 6 种语言
- ⌨️ **Vim 模式** — JLine vi 编辑模式（`CLAUDE_CODE_VIM=1` 启用）

### P2 扩展功能
- 🔌 **MCP 协议** — Model Context Protocol 客户端（StdIO 传输、工具发现、资源读取）
- 🧩 **插件系统** — JAR 插件加载（ClassLoader 隔离、工具/命令扩展）
- 📋 **任务管理** — 后台任务创建、查询、更新（4 个工具）
- 🔀 **彩色 Diff** — unified diff 渲染（行号、stat 摘要、颜色标注）
- 🌿 **对话分支** — 保存/恢复/标签对话状态
- 🛡️ **安全审查** — AI 驱动的安全漏洞检测

## 📦 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 25 | 运行时 |
| Spring Boot | 4.1.0-M2 | 应用框架 |
| Spring AI | 2.0.0-M4 | AI 模型调用 |
| JLine 3 | 3.28.0 | 终端交互 |
| Picocli | 4.7.6 | CLI 命令解析 |

## 🚀 快速开始

> 📖 完整的构建、安装、跨平台使用说明请参阅 **[BUILD.md](BUILD.md)**

### 前置要求

- **JDK 25**（配置 `JAVA_HOME`）
- **Maven 3.9+**
- **API Key**（OpenAI 或 Anthropic 或兼容服务）

### 配置

设置环境变量：

```bash
# 必须：API 密钥
export AI_API_KEY="your-api-key-here"

# 可选：API 提供者（默认 openai）
export CLAUDE_CODE_PROVIDER="openai"    # 或 "anthropic"

# 可选：自定义 API 地址和模型
export AI_BASE_URL="https://api.openai.com"
export AI_MODEL="gpt-4o"
```

**支持的 API 提供者：**

| 提供者 | 默认 Base URL | 默认模型 |
|--------|--------------|---------|
| `openai` | `https://api.openai.com` | `gpt-4o` |
| `anthropic` | `https://api.anthropic.com` | `claude-sonnet-4-20250514` |

> 💡 OpenAI 提供者兼容所有 OpenAI 格式的 API 代理（如 DeepSeek、Azure OpenAI 等）。

### 运行

**构建发行版后运行：**
```bash
# 1. 构建发行包（仅需一次）
.\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25.0.2"  # Windows
# 或
JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh        # Linux/macOS

# 2. 运行
dist\bin\claude-code.cmd      # Windows
dist/bin/claude-code           # Linux/macOS
```

**开发模式运行：**
```bash
mvn spring-boot:run
```

### 启动效果

```
  ◆ Claude Code (Java)  v0.1.0-SNAPSHOT
  Type /help for commands  •  Ctrl+D to exit

  Provider: OPENAI  Model: deepseek-chat
  API URL:  https://api.deepseek.com
  Work Dir: D:\my-project
  Tools: 18 | Commands: 28
  Terminal: windows-vtp (160×30)
  Tip: Tab to complete commands, ↑↓ to browse history, Ctrl+D to exit

❯ 
```

## 📖 使用说明

### 对话

直接输入文本与 AI 对话，支持流式输出：

```
❯ 帮我分析这个项目的架构
```

### 多行输入

行末加 `\` 续行：

```
❯ 请帮我写一个函数，\
  ... 实现字符串反转
```

### 斜杠命令

#### 基础命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `/help` | | 显示所有可用命令 |
| `/clear` | | 清屏 |
| `/compact` | | AI 摘要压缩对话上下文（委托三层压缩系统） |
| `/cost` | | 显示 Token 使用量和费用 |
| `/model [name]` | | 查看/切换模型 |
| `/status` | | 显示会话状态 |
| `/context` | | 显示已加载的上下文 |
| `/config` | | 查看配置信息 |
| `/init` | | 初始化 CLAUDE.md 配置文件 |
| `/history` | | 列出保存的对话历史 |
| `/exit` | `/quit` | 退出 |

#### P0 命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `/diff` | | 显示 Git 变更（支持 `--staged`, `--stat`） |
| `/version` | `/ver` | 显示版本和环境信息 |
| `/skills` | | 列出已加载的技能 |
| `/memory` | `/mem` | 查看/编辑 CLAUDE.md |
| `/copy` | | 复制最近回复到剪贴板 |

#### P1 命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `/resume` | | 恢复已保存的对话 |
| `/export` | | 导出对话为 Markdown 文件 |
| `/commit` | | AI 生成 commit message 并提交 |

#### P2 命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `/hooks` | | 查看已注册的 Hook |
| `/review` | `/rev` | AI 代码审查（支持 `--staged`、文件路径） |
| `/stats` | | 使用统计（Token、费用、API调用、运行时长） |
| `/branch` | | 对话分支（`save/load/list/delete`） |
| `/rewind [n]` | | 回退对话历史（默认回退1轮） |
| `/tag` | | 对话标签（`<name>/list/goto <name>`） |
| `/security-review` | `/sec` | AI 安全漏洞审查 |
| `/mcp` | | MCP 服务器管理（`connect/disconnect/tools/resources`） |
| `/plugin` | | 插件管理（`load/unload/reload/info`） |

### 内置工具

AI 可以自动调用以下工具：

#### 核心工具（Phase 1-3）

| 工具 | 说明 |
|------|------|
| `bash` | 执行 Shell 命令 |
| `file_read` | 读取文件内容 |
| `file_write` | 写入文件 |
| `file_edit` | 编辑文件（搜索替换） |
| `glob` | 文件模式匹配搜索 |
| `grep` | 文本内容搜索 |
| `list_files` | 列出目录文件 |
| `web_fetch` | 获取网页内容 |
| `todo_write` | 管理待办事项 |
| `agent` | 启动子 Agent 处理复杂任务 |
| `notebook_edit` | 编辑 Jupyter Notebook |

#### P0 工具

| 工具 | 说明 |
|------|------|
| `web_search` | DuckDuckGo 网络搜索（无需 API Key） |
| `ask_user_question` | AI 向用户提问（暂停 agent loop 等待输入） |

#### P2 工具

| 工具 | 说明 |
|------|------|
| `TaskCreate` | 创建后台任务 |
| `TaskGet` | 查询任务详情 |
| `TaskList` | 列出任务（支持状态过滤） |
| `TaskUpdate` | 更新任务状态和结果 |
| `Config` | 读写配置值 |
| `mcp__*` | MCP 远程工具桥接（动态注册） |

### CLAUDE.md 记忆文件

创建 `CLAUDE.md` 文件来给 AI 提供项目上下文：

```markdown
# 项目说明
这是一个 Spring Boot 项目，使用 MyBatis 做数据持久化。

# 编码规范
- 使用中文注释
- 遵循阿里巴巴 Java 开发手册
```

加载顺序（优先级递增）：
1. `~/.claude/CLAUDE.md` — 全局配置
2. 项目根目录 `CLAUDE.md` — 项目级配置
3. 当前目录 `CLAUDE.md` — 目录级配置

### Skills 技能系统

在以下目录放置 `.md` 文件作为技能：

- `~/.claude/skills/` — 全局技能
- `.claude/skills/` — 项目技能
- `.claude/commands/` — 自定义命令技能

技能文件支持 YAML frontmatter：

```markdown
---
name: code-review
description: 代码审查技能
---

请按照以下标准审查代码：
1. 命名规范
2. 错误处理
3. 性能考量
```

### MCP 服务器集成

配置 MCP 服务器，在项目根目录创建 `.mcp.json`：

```json
{
  "servers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"]
    },
    "my-server": {
      "command": "python",
      "args": ["my_mcp_server.py"],
      "env": { "API_KEY": "xxx" }
    }
  }
}
```

或放在全局目录 `~/.claude-code-java/mcp.json`。

使用 `/mcp` 命令管理：
```
❯ /mcp                          # 列出所有 MCP 服务器
❯ /mcp tools                    # 列出所有 MCP 工具
❯ /mcp connect myserver cmd args # 连接新服务器
❯ /mcp disconnect myserver      # 断开服务器
```

### 插件系统

JAR 插件放在以下目录即可自动加载：
- `~/.claude-code-java/plugins/` — 全局插件
- `.claude-code/plugins/` — 项目级插件

JAR 要求：
- `META-INF/MANIFEST.MF` 中包含 `Plugin-Class` 属性
- 实现 `com.claudecode.plugin.Plugin` 接口

使用 `/plugin` 命令管理：
```
❯ /plugin                      # 列出已加载插件
❯ /plugin info output-style    # 查看插件详情
❯ /plugin unload my-plugin     # 卸载插件
```

### Vim 模式

设置环境变量启用 JLine vi 编辑模式：

```bash
export CLAUDE_CODE_VIM=1
```

启用后 Banner 会显示 `[vim]` 标识，输入行支持 vi 按键绑定。

### 权限管理

通过 `/config` 命令管理权限：

```
❯ /config permission-mode default      # 默认模式（逐次确认）
❯ /config permission-mode accept-edits # 自动允许文件编辑
❯ /config permission-mode bypass       # 跳过所有权限检查
❯ /config permission-mode dont-ask     # 自动拒绝所有操作
❯ /config permission-mode plan         # 计划模式（只读操作允许，写操作拒绝）
❯ /config permission-list              # 查看已保存的权限规则
❯ /config permission-reset             # 清除所有权限规则
```

权限规则持久化位置：
- 用户级：`~/.claude-code-java/settings.json`（跨项目生效）
- 项目级：`.claude-code-java/settings.json`（项目特定）

权限确认 UI（工具调用时自动弹出）：

```
⚠ Permission Required
──────────────────────────────────────────
Tool: Bash
Action: npm install express
──────────────────────────────────────────
[Y] Allow once
[A] Always allow Bash(npm:*)
[N] Deny once
[D] Always deny this pattern
Choice [Y/a/n/d]:
```

### Hook 系统

通过代码注册 Hook 来拦截工具调用：

```java
agentLoop.getHookManager().register(HookManager.HookType.PRE_TOOL_USE, new Hook() {
    public String name() { return "my-hook"; }
    public int priority() { return 10; }
    public HookResult execute(HookContext ctx) {
        // 可检查 ctx.getToolName(), ctx.getArguments()
        // 返回 ABORT 可阻止工具执行
        return HookResult.CONTINUE;
    }
});
```

4 种钩子类型：`PRE_TOOL_USE`、`POST_TOOL_USE`、`PRE_PROMPT`、`POST_RESPONSE`

## 🏗️ 架构设计

### 模块结构

```
com.claudecode
├── ClaudeCodeApplication          // Spring Boot 主入口
├── cli/
│   └── ClaudeCodeRunner           // 启动编排（CommandLineRunner）
├── config/
│   └── AppConfig                  // Bean 装配、Provider 切换、组件注册
├── core/
│   ├── AgentLoop                  // Agent 循环（阻塞 + 流式 + Hook + 权限 + 自动压缩）
│   ├── TokenTracker               // Token 使用追踪 + 上下文窗口监控（93%/82%/98% 阈值）
│   ├── ConversationPersistence    // 对话持久化
│   ├── HookManager                // Hook 系统（4种钩子类型）
│   ├── TaskManager                // 后台任务管理
│   └── compact/                   // 三层压缩子系统
│       ├── AutoCompactManager     // 压缩编排（micro→session→full + 熔断器）
│       ├── MicroCompact           // 微压缩（tool result 截断 + 时间感知）
│       ├── SessionMemoryCompact   // Session Memory 压缩（AI 摘要 + 保留段）
│       ├── FullCompact            // 全量压缩（PTL gap 解析 + API Round 分组）
│       └── CompactionResult       // 压缩结果记录
├── tool/
│   ├── Tool                       // 工具协议接口
│   ├── ToolRegistry               // 工具注册中心
│   ├── ToolCallbackAdapter        // Spring AI 适配器
│   └── impl/                      // 18 个工具实现
│       ├── BashTool, FileReadTool, FileWriteTool, FileEditTool
│       ├── GlobTool, GrepTool, ListFilesTool, WebFetchTool
│       ├── TodoWriteTool, AgentTool, NotebookEditTool
│       ├── WebSearchTool, AskUserQuestionTool
│       ├── TaskCreateTool, TaskGetTool, TaskListTool, TaskUpdateTool
│       ├── ConfigTool
│       └── McpToolBridge          // MCP 远程工具桥接
├── command/
│   ├── SlashCommand               // 命令接口
│   ├── CommandRegistry            // 命令注册中心
│   └── impl/                      // 28 个命令实现
├── console/
│   ├── BannerPrinter              // 启动 Banner
│   ├── ToolStatusRenderer         // 工具状态渲染
│   ├── ThinkingRenderer           // Thinking 渲染
│   ├── SpinnerAnimation           // 加载动画
│   ├── MarkdownRenderer           // Markdown 渲染（含语法高亮）
│   ├── DiffRenderer               // 彩色 Diff 渲染
│   ├── StatusLine                 // 底部状态行
│   └── AnsiStyle                  // ANSI 样式工具
├── context/
│   ├── SystemPromptBuilder        // 系统提示词构建
│   ├── ClaudeMdLoader             // CLAUDE.md 加载
│   ├── SkillLoader                // Skills 加载
│   └── GitContext                 // Git 上下文收集
├── mcp/
│   ├── McpClient                  // MCP 客户端（JSON-RPC 2.0）
│   ├── McpTransport               // 传输层接口
│   ├── StdioTransport             // StdIO 传输实现
│   ├── McpManager                 // 多服务器管理
│   └── McpException               // MCP 异常
├── plugin/
│   ├── Plugin                     // 插件接口
│   ├── PluginContext              // 插件上下文
│   ├── PluginManager              // 插件加载/管理
│   └── OutputStylePlugin          // 内置输出样式插件
├── permission/                    // 多级权限子系统
│   ├── PermissionTypes            // 权限类型（行为/模式/规则/决策/选择）
│   ├── PermissionRuleEngine       // 规则引擎（7 步评估链）
│   ├── PermissionSettings         // 持久化（用户/项目/会话三级）
│   ├── DangerousPatterns          // 30+ 危险命令模式检测
│   └── DenialTracker              // 拒绝追踪（连续 3 / 累计 20 阈值）
└── repl/
    ├── ReplSession                // REPL 会话管理
    └── ClaudeCodeCompleter        // Tab 补全
```

### 核心流程

```
用户输入 → 命令检测 → AgentLoop
                        ↓
              chatModel.stream(prompt) → Flux<ChatResponse>
                        ↓
              逐token实时输出到终端
                        ↓
              检测工具调用 → PreToolUse Hook → 权限规则引擎评估 → 执行工具 → PostToolUse Hook → 结果回传
                        ↓                        ↓
              自动压缩检查 ←─────────  权限 UI（Y/A/N/D）
                        ↓
              TokenTracker 阈值监控 → 微压缩 → Session Memory → 全量压缩（兜底）
                        ↓
              继续循环或结束
```

### 权限评估链

```
工具调用 → PermissionRuleEngine.evaluate()
              │
              ├── ① BYPASS 模式 → 直接 ALLOW
              ├── ② PLAN 模式 → 只读 ALLOW，写操作 DENY
              ├── ③ alwaysDeny 规则匹配 → DENY
              ├── ④ alwaysAllow 规则匹配 → ALLOW
              ├── ⑤ readOnly 工具 → ALLOW
              ├── ⑥ ACCEPT_EDITS / DONT_ASK 模式 → 对应行为
              ├── ⑦ DangerousPatterns 检测 → 标记为危险
              └── ⑧ 默认 → ASK（UI 确认）
                       ↓
              用户选择 → applyChoice() → 持久化规则
```

### 三层压缩架构

```
AutoCompactManager.autoCompactIfNeeded()
    │
    ├── TokenTracker.shouldAutoCompact()  (>93%)
    │       ↓
    ├── ① MicroCompact — 本地截断（无 API 调用）
    │       保留最近 6 条 tool result，时间感知（>10min 仅保留 2 条）
    │       ↓
    ├── ② SessionMemoryCompact — AI 摘要（1 次 API 调用）
    │       保留 10K-40K token 近期段，4/3 安全系数，不拆分 tool 对
    │       ↓
    └── ③ FullCompact — 全量压缩（兜底，多次 API 调用）
            API Round 分组 → PTL gap 解析 → 逐步丢弃 → 熔断器（3 次失败停止）
```

### MCP 协议架构

```
McpManager ─── 配置加载 (.mcp.json)
    │
    ├── McpClient("server-a") ── StdioTransport ── 子进程 (npx server-a)
    │       ├── tools/list → 发现工具
    │       ├── tools/call → 调用工具
    │       └── resources/read → 读取资源
    │
    └── McpClient("server-b") ── StdioTransport ── 子进程 (python server-b)
            └── ... (同上)

McpToolBridge → 将 MCP 工具映射为本地 Tool → 注册到 ToolRegistry → AI 可调用
```

### 插件系统架构

```
PluginManager
    ├── 全局插件: ~/.claude-code-java/plugins/*.jar
    ├── 项目插件: .claude-code/plugins/*.jar
    └── 内置插件: OutputStylePlugin
        │
        ├── Plugin.getTools() → 注册到 ToolRegistry
        └── Plugin.getCommands() → 注册到 CommandRegistry
```

### 双 API 提供者架构

```
                    ┌─ openAiChatModel ──── OpenAI / DeepSeek / Azure
CLAUDE_CODE_PROVIDER─┤
                    └─ anthropicChatModel ── Anthropic Claude
```

通过 `claude-code.provider` 配置决定使用哪个 `ChatModel` Bean。

## ⚙️ 配置参考

### application.yml

```yaml
spring:
  ai:
    anthropic:
      api-key: ${AI_API_KEY:}
      base-url: ${AI_BASE_URL:https://api.anthropic.com}
      chat.options.model: ${AI_MODEL:claude-sonnet-4-20250514}
    openai:
      api-key: ${AI_API_KEY:}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat.options.model: ${AI_MODEL:gpt-4o}

claude-code:
  provider: ${CLAUDE_CODE_PROVIDER:openai}
```

### 环境变量

| 变量 | 必须 | 说明 | 默认值 |
|------|------|------|--------|
| `AI_API_KEY` | ✅ | API 密钥 | - |
| `CLAUDE_CODE_PROVIDER` | ❌ | 提供者 (`openai`/`anthropic`) | `openai` |
| `AI_BASE_URL` | ❌ | API 基础 URL | 按提供者不同 |
| `AI_MODEL` | ❌ | 模型名称 | 按提供者不同 |
| `AI_MAX_TOKENS` | ❌ | 最大 Token 数 | `8096` |
| `CLAUDE_CODE_VIM` | ❌ | 启用 Vim 编辑模式 | `0` |
| `CLAUDE_CODE_CONTEXT_WINDOW` | ❌ | 上下文窗口大小 | `200000` |

## 🔧 开发

### 构建

```bash
mvn clean compile
```

### 打包

```bash
mvn clean package -DskipTests
java -jar target/claude-code-java-0.1.0-SNAPSHOT.jar
```

### 发行版构建（jlink 最小 JRE）

创建包含精简 JRE 的独立发行包，无需目标机器安装 JDK：

**Windows (PowerShell)：**
```powershell
.\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25.0.2"
```

**Linux / macOS (Bash)：**
```bash
JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh
```

生成的 `dist/` 目录结构：
```
dist/
├── bin/
│   ├── claude-code          # Unix 启动脚本
│   └── claude-code.cmd      # Windows 启动脚本
├── lib/
│   └── claude-code-java.jar # Spring Boot fat jar (~71 MB)
└── runtime/                 # jlink 精简 JRE (~49 MB)
    └── ...
```

使用方式：
```bash
# 将 bin/ 加入 PATH
export PATH="/path/to/dist/bin:$PATH"    # Linux/macOS
set PATH=C:\path\to\dist\bin;%PATH%      # Windows

# 设置 API Key 后即可使用
export AI_API_KEY=your-key
claude-code
```

> 💡 发行包约 120 MB，比完整 JDK（~350 MB）小得多。每个平台需要独立构建（jlink JRE 是平台相关的）。

### 已知问题

- **Windows 编码**：需要 `chcp 65001` 切换到 UTF-8 编码页（发行版启动脚本已自动处理）
- **IDE 终端**：IntelliJ IDEA 等 IDE 内置终端为 dumb 模式，Tab 补全和行编辑受限
- **JDK 25 警告**：Maven 的 jansi/guava 会触发 native access 警告（启动脚本已通过 JVM 参数抑制）

## 📝 对应关系

| Claude Code (TypeScript) | Java 实现 | 说明 |
|--------------------------|-----------|------|
| `cli.tsx` → `main.tsx` | `ClaudeCodeApplication` + `ClaudeCodeRunner` | 入口 |
| `REPL.tsx` | `ReplSession` + JLine 3 | 交互循环 |
| `query.ts` | `AgentLoop` | Agent 循环 |
| `Tool.ts` + `tools/*` | `Tool` 接口 + `impl/*` (18个) | 工具系统 |
| `commands.ts` | `SlashCommand` + `impl/*` (28个) | 命令系统 |
| `context.ts` + `prompts.ts` | `SystemPromptBuilder` + loaders | 上下文 |
| `CLAUDE.md` + `skills/` | `ClaudeMdLoader` + `SkillLoader` | 记忆/技能 |
| `compact/*` (microCompact/sessionCompact/fullCompact) | `core/compact/*` (5个) | 三层压缩 |
| `permissions/*` (permissionCheck/ruleEngine) | `permission/*` (5个) | 权限管理 |
| Ink Components | `console/*` 渲染器 (8个) | 终端 UI |
| `mcp/*` (22文件) | `mcp/*` (McpClient/Manager/Transport) | MCP 协议 |
| `plugins/*` (38文件) | `plugin/*` (Plugin/Manager/Context) | 插件系统 |
| `hooks/*` | `HookManager` | Hook 系统 |
| `tasks/*` | `TaskManager` + Task工具 | 任务管理 |

## 📄 License

本项目仅用于学习和研究目的。
