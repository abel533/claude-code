@echo off
REM ============================================
REM Claude Code (Java) 启动脚本
REM 请在 Windows Terminal / PowerShell / cmd 中运行
REM ============================================

REM === JDK 25 配置 ===
set JAVA_HOME=D:\Dev\jdk-25
set PATH=%JAVA_HOME%\bin;%PATH%

REM === 抑制 Maven JVM 的 JDK25 兼容性警告 ===
set MAVEN_OPTS=--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow

REM === AI API 配置（按需修改） ===
REM 选择 API 提供者：openai（默认）或 anthropic
REM set CLAUDE_CODE_PROVIDER=openai
REM set CLAUDE_CODE_PROVIDER=anthropic

REM OpenAI 兼容 API 配置（默认）
REM set AI_API_KEY=your-api-key-here
REM set AI_BASE_URL=https://api.openai.com
REM set AI_OPENAI_MODEL=gpt-4o

REM Anthropic 原生 API 配置
REM set ANTHROPIC_API_KEY=your-api-key-here
REM set ANTHROPIC_BASE_URL=https://api.anthropic.com
REM set AI_MODEL=claude-sonnet-4-20250514

REM === 设置控制台 UTF-8 编码（支持 emoji 等字符） ===
chcp 65001 >nul 2>&1

REM === 启动应用 ===
cd /d %~dp0
mvn spring-boot:run -q
