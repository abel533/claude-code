@echo off
REM ============================================
REM Claude Code (Java) 启动脚本
REM 请在 Windows Terminal / PowerShell / cmd 中运行
REM ============================================

REM === JDK 25 配置 ===
set JAVA_HOME=D:\Dev\jdk-25
set PATH=%JAVA_HOME%\bin;%PATH%

REM === AI API 配置（按需修改） ===
REM set ANTHROPIC_API_KEY=your-api-key-here
REM set AI_MODEL=claude-sonnet-4-20250514

REM === 启动应用 ===
cd /d %~dp0
mvn spring-boot:run -q
