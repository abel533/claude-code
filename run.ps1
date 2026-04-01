# ============================================
# Claude Code (Java) 启动脚本 - PowerShell 版
# 请在 Windows Terminal / PowerShell 中运行
# ============================================

# === JDK 25 配置 ===
$env:JAVA_HOME = "D:\Dev\jdk-25"
$env:Path = "D:\Dev\jdk-25\bin;$env:Path"

# === 抑制 Maven JVM 的 JDK25 兼容性警告 ===
$env:MAVEN_OPTS = "--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"

# === AI API 配置（按需修改） ===
# 选择 API 提供者：openai（默认）或 anthropic
# $env:CLAUDE_CODE_PROVIDER = "openai"     # 使用 OpenAI 兼容 API（支持代理）
# $env:CLAUDE_CODE_PROVIDER = "anthropic"  # 使用 Anthropic 原生 API

# 统一环境变量（两种 Provider 通用）
# $env:AI_API_KEY  = "your-api-key-here"            # API 密钥（必须）
# $env:AI_BASE_URL = "https://api.openai.com"       # API 基础 URL（按 Provider 不同默认值不同）
# $env:AI_MODEL    = "gpt-4o"                        # 模型名称（按 Provider 不同默认值不同）
#
# OpenAI 默认:  AI_BASE_URL=https://api.openai.com       AI_MODEL=gpt-4o
# Anthropic 默认: AI_BASE_URL=https://api.anthropic.com  AI_MODEL=claude-sonnet-4-20250514

# === 设置控制台 UTF-8 编码（支持 emoji 等字符） ===
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

# === 启动应用 ===
Set-Location $PSScriptRoot
mvn spring-boot:run -q
