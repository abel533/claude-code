# Claude Code Java — 构建与安装指南

## 前置要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| **JDK** | 25+ | 必须，推荐 [Oracle JDK 25](https://www.oracle.com/java/technologies/downloads/) 或 [OpenJDK 25](https://jdk.java.net/25/) |
| **Maven** | 3.9+ | 必须，[下载地址](https://maven.apache.org/download.cgi) |
| **API Key** | - | OpenAI / Anthropic / 兼容 API 的密钥 |

> ⚠️ JDK 25 是必须的，项目使用了 preview 特性（如 pattern matching、string templates）。

---

## 快速开始（开发模式）

适合开发和调试，通过 Maven 直接运行：

```bash
# 1. 设置 JDK
export JAVA_HOME=/path/to/jdk-25    # Linux/macOS
set JAVA_HOME=C:\Dev\jdk-25.0.2     # Windows

# 2. 设置 API Key
export AI_API_KEY=your-api-key       # Linux/macOS
set AI_API_KEY=your-api-key          # Windows

# 3. 运行
mvn spring-boot:run
```

> 📌 开发模式下，工作目录（AI 操作的目录）为执行 `mvn` 命令的目录。

---

## 构建发行版（推荐用于日常使用）

发行版使用 **jlink** 创建精简 JRE，打包为独立可执行程序，无需目标机器安装 JDK。

### Windows

```powershell
# 构建
.\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25.0.2"

# 或跳过 Maven 构建（已有 jar 的情况）
.\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25.0.2" -SkipBuild
```

### Linux / macOS

```bash
# 构建
JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh

# 或跳过 Maven 构建
JAVA_HOME=/path/to/jdk-25 ./packaging/build-dist.sh --skip-build
```

### 构建输出

```
dist/
├── bin/
│   ├── claude-code          # Unix 启动脚本 (Linux/macOS)
│   └── claude-code.cmd      # Windows 启动脚本
├── lib/
│   └── claude-code-java.jar # Spring Boot fat jar (~71 MB)
└── runtime/                 # jlink 精简 JRE (~49 MB)
    ├── bin/
    ├── conf/
    ├── lib/
    └── release
```

总大小约 **120 MB**（对比完整 JDK ~350 MB）。

---

## 安装到系统 PATH

将 `dist/bin` 加入系统 PATH 后，可在任意目录直接使用 `claude-code` 命令。

### Windows

**方法 1：CMD 临时生效（当前终端）**
```cmd
set PATH=C:\path\to\claude-code-java\dist\bin;%PATH%
```

**方法 2：PowerShell 临时生效（当前终端）**
```powershell
$env:PATH = "C:\path\to\claude-code-java\dist\bin;$env:PATH"
```

**方法 3：CMD 永久生效（用户级）**
```cmd
setx PATH "%PATH%;C:\path\to\claude-code-java\dist\bin"
```
> 需要重开终端窗口生效。

**方法 4：PowerShell 永久生效**
```powershell
$binPath = "C:\path\to\claude-code-java\dist\bin"
$currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($currentPath -notmatch [regex]::Escape($binPath)) {
    [Environment]::SetEnvironmentVariable("PATH", "$currentPath;$binPath", "User")
    Write-Host "Added to PATH. Restart terminal to take effect."
}
```

### Linux

```bash
# 临时生效
export PATH="/path/to/claude-code-java/dist/bin:$PATH"

# 永久生效 — 添加到 ~/.bashrc 或 ~/.zshrc
echo 'export PATH="/path/to/claude-code-java/dist/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

### macOS

```bash
# 临时生效
export PATH="/path/to/claude-code-java/dist/bin:$PATH"

# 永久生效 — 添加到 ~/.zshrc (macOS 默认 shell 是 zsh)
echo 'export PATH="/path/to/claude-code-java/dist/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### 验证安装

```bash
# 在任意目录执行
claude-code           # Linux/macOS
claude-code.cmd       # Windows CMD
claude-code           # Windows PowerShell (自动找 .cmd)
```

---

## 在其他目录使用

安装到 PATH 后，`claude-code` 可以在任何目录启动。**AI 的工作目录就是你启动命令时的当前目录**。

```bash
# 示例：在项目目录启动，AI 会自动读取该目录的上下文
cd /path/to/my-project
claude-code

# AI 将：
# - 自动加载 ./CLAUDE.md（如果存在）
# - 读取 .git 信息获取项目上下文
# - 所有文件操作基于当前目录
```

```bash
# 示例：在不同项目之间切换
cd ~/projects/web-app && claude-code      # 操作 web-app 项目
cd ~/projects/api-server && claude-code   # 操作 api-server 项目
```

### 工作目录说明

| 场景 | 工作目录 | 说明 |
|------|----------|------|
| `cd /my-project && claude-code` | `/my-project` | AI 在此目录下操作文件 |
| 启动后使用工具 | 当前目录 | `bash`, `file_read` 等都基于启动目录 |
| `CLAUDE.md` 加载 | 当前目录 + `~/.claude/` | 项目级 + 全局级自动合并 |
| Git 上下文 | 当前目录的 `.git` | 自动检测分支、状态、最近提交 |

---

## 环境变量

| 变量 | 必须 | 说明 | 默认值 |
|------|------|------|--------|
| `AI_API_KEY` | ✅ | API 密钥 | - |
| `CLAUDE_CODE_PROVIDER` | ❌ | 提供者 (`openai` / `anthropic`) | `openai` |
| `AI_BASE_URL` | ❌ | API 基础 URL | 按提供者不同 |
| `AI_MODEL` | ❌ | 模型名称 | 按提供者不同 |
| `AI_MAX_TOKENS` | ❌ | 最大生成 Token 数 | `8096` |
| `CLAUDE_CODE_VIM` | ❌ | 启用 Vim 编辑模式 | `0` |
| `CLAUDE_CODE_CONTEXT_WINDOW` | ❌ | 上下文窗口大小 | `200000` |

建议将常用变量配置到 shell profile 中：

```bash
# ~/.bashrc 或 ~/.zshrc
export AI_API_KEY="sk-your-key-here"
export CLAUDE_CODE_PROVIDER="openai"
export AI_BASE_URL="https://api.deepseek.com"
export AI_MODEL="deepseek-chat"
```

Windows 可用 `setx` 持久化：
```cmd
setx AI_API_KEY "sk-your-key-here"
setx CLAUDE_CODE_PROVIDER "openai"
```

---

## 跨平台构建注意事项

jlink 创建的 JRE 是 **平台相关** 的。在 Windows 上构建的 `dist/` 只能在 Windows 上运行。

如果需要为多个平台构建：

| 构建平台 | 产出 | 可运行平台 |
|----------|------|------------|
| Windows x64 | `dist/runtime/` (Windows JRE) | Windows x64 |
| Linux x64 | `dist/runtime/` (Linux JRE) | Linux x64 |
| macOS ARM | `dist/runtime/` (macOS JRE) | macOS ARM (M1/M2/M3) |
| macOS x64 | `dist/runtime/` (macOS JRE) | macOS Intel |

> 💡 `lib/claude-code-java.jar` 是跨平台的，只有 `runtime/` 需要针对每个平台构建。
>
> 如果在 CI/CD 中构建，可以在 GitHub Actions 中使用 matrix strategy 分别在 `ubuntu-latest`, `windows-latest`, `macos-latest` 上构建。

---

## 常见问题

### Q: 启动时报 "OpenAI API key must be set"

设置 `AI_API_KEY` 环境变量：
```bash
export AI_API_KEY="your-key"
```

### Q: Windows 终端中文乱码

发行版启动脚本已自动执行 `chcp 65001`。如果仍有问题，手动运行：
```cmd
chcp 65001
```

### Q: 如何使用 Anthropic API 而不是 OpenAI?

```bash
export CLAUDE_CODE_PROVIDER="anthropic"
export AI_API_KEY="sk-ant-your-key"
export AI_MODEL="claude-sonnet-4-20250514"
```

### Q: 如何使用 DeepSeek / Azure OpenAI 等兼容 API?

```bash
export CLAUDE_CODE_PROVIDER="openai"
export AI_BASE_URL="https://api.deepseek.com"
export AI_MODEL="deepseek-chat"
export AI_API_KEY="your-deepseek-key"
```

### Q: dist/ 目录可以复制到其他机器吗?

可以，只要目标机器的操作系统和 CPU 架构与构建机器一致。`dist/` 是完全自包含的，不需要安装 JDK。

### Q: 如何更新?

重新执行构建脚本覆盖 `dist/` 目录即可：
```bash
git pull
./packaging/build-dist.sh        # 或 .ps1
```
