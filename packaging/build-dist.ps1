#!/usr/bin/env pwsh
<#
.SYNOPSIS
    构建 Claude Code Java 发行版（jlink 最小 JRE + fat jar）
.DESCRIPTION
    生成可分发包：bin/ 启动脚本 + lib/ fat jar + runtime/ 精简 JRE
    用户只需将 bin/ 目录加入 PATH 即可使用 claude-code 命令
.PARAMETER JavaHome
    JDK 25 安装路径（默认使用 JAVA_HOME 环境变量）
.PARAMETER OutputDir
    输出目录（默认 dist/）
.PARAMETER SkipBuild
    跳过 Maven 构建（使用已存在的 jar）
.EXAMPLE
    .\packaging\build-dist.ps1
    .\packaging\build-dist.ps1 -JavaHome "C:\Dev\jdk-25.0.2"
    .\packaging\build-dist.ps1 -SkipBuild
#>
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutputDir = "dist",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# ─── 验证环境 ───
$javaExe = if ($IsWindows -or $env:OS -eq "Windows_NT") { "java.exe" } else { "java" }
$jlinkExe = if ($IsWindows -or $env:OS -eq "Windows_NT") { "jlink.exe" } else { "jlink" }
$jdepsExe = if ($IsWindows -or $env:OS -eq "Windows_NT") { "jdeps.exe" } else { "jdeps" }

if (-not $JavaHome) {
    Write-Error "JAVA_HOME not set. Use -JavaHome parameter or set JAVA_HOME environment variable."
    exit 1
}
$javaBinPath = Join-Path $JavaHome "bin"
if (-not (Test-Path (Join-Path $javaBinPath $javaExe))) {
    Write-Error "JDK not found at $JavaHome. Ensure JDK 25 is installed."
    exit 1
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$javaBinPath$([IO.Path]::PathSeparator)$env:PATH"

$javaVersion = & (Join-Path $javaBinPath $javaExe) --version 2>&1 | Select-Object -First 1
Write-Host ""
Write-Host "  Claude Code Java - Distribution Builder" -ForegroundColor Cyan
Write-Host "  ========================================" -ForegroundColor Cyan
Write-Host "  JDK:    $javaVersion" -ForegroundColor Gray
Write-Host "  Output: $OutputDir" -ForegroundColor Gray

# ─── 检测平台 ───
$isWin = $IsWindows -or ($env:OS -eq "Windows_NT")
$isMac = $IsMacOS
if ($isWin) { $platform = "windows-x64" }
elseif ($isMac) {
    $arch = & uname -m 2>&1
    $platform = if ($arch -match "arm64") { "macos-aarch64" } else { "macos-x64" }
} else {
    $arch = & uname -m 2>&1
    $platform = if ($arch -match "aarch64") { "linux-aarch64" } else { "linux-x64" }
}
Write-Host "  Platform: $platform" -ForegroundColor Gray
Write-Host ""

# ─── Step 1: Maven 构建 ───
Push-Location $ProjectRoot
try {
    if (-not $SkipBuild) {
        Write-Host "[1/4] Building fat jar with Maven..." -ForegroundColor Yellow
        & mvn package -q -DskipTests 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Maven build failed"
            exit 1
        }
        Write-Host "  OK Build succeeded" -ForegroundColor Green
    } else {
        Write-Host "[1/4] Skipping build (using existing jar)" -ForegroundColor DarkGray
    }

    # 查找 fat jar
    $jarFile = Get-ChildItem "target/*.jar" -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notmatch "original" } |
               Select-Object -First 1
    if (-not $jarFile) {
        Write-Error "No jar found in target/. Run without -SkipBuild."
        exit 1
    }
    $jarSize = [math]::Round($jarFile.Length / 1MB, 1)
    Write-Host "  Jar: $($jarFile.Name) ($jarSize MB)" -ForegroundColor Gray

    # ─── Step 2: jdeps 分析模块 ───
    Write-Host "[2/4] Analyzing module dependencies with jdeps..." -ForegroundColor Yellow
    $detected = & (Join-Path $javaBinPath $jdepsExe) --print-module-deps --ignore-missing-deps --multi-release 25 $jarFile.FullName 2>&1
    if ($LASTEXITCODE -ne 0 -or -not $detected) {
        Write-Host "  Warning: jdeps failed, using default modules" -ForegroundColor DarkYellow
        $detected = "java.base,java.desktop,java.management,java.net.http"
    }
    Write-Host "  Detected: $detected" -ForegroundColor Gray

    # Spring Boot 运行时必要模块
    $required = @(
        "java.naming",       # JNDI (Spring)
        "java.xml",          # XML processing (Spring)
        "java.sql",          # JDBC
        "java.instrument",   # Java agent / instrumentation
        "java.compiler",     # Annotation processing
        "java.scripting",    # Script engines
        "jdk.unsupported",   # sun.misc.Unsafe
        "jdk.crypto.ec",     # HTTPS/TLS ECDH
        "jdk.zipfs",         # ZIP filesystem provider
        "jdk.jfr",           # Flight Recorder
        "jdk.net"            # Extended socket options (RestClient)
    )
    $allSet = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($m in ($detected -split ",")) { [void]$allSet.Add($m.Trim()) }
    foreach ($m in $required) { [void]$allSet.Add($m) }
    $modules = ($allSet | Sort-Object) -join ","
    Write-Host "  Final modules: $modules" -ForegroundColor Gray

    # ─── Step 3: jlink ───
    Write-Host "[3/4] Creating minimal JRE with jlink..." -ForegroundColor Yellow
    $distDir = Join-Path $ProjectRoot $OutputDir
    $runtimeDir = Join-Path $distDir "runtime"

    if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
    New-Item -ItemType Directory -Path $distDir -Force | Out-Null

    & (Join-Path $javaBinPath $jlinkExe) `
        --add-modules $modules `
        --output $runtimeDir `
        --strip-debug `
        --compress zip-6 `
        --no-header-files `
        --no-man-pages 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Error "jlink failed"
        exit 1
    }

    $runtimeSize = [math]::Round(
        (Get-ChildItem -Recurse $runtimeDir | Measure-Object -Property Length -Sum).Sum / 1MB, 1
    )
    Write-Host "  OK Runtime: $runtimeSize MB" -ForegroundColor Green

    # ─── Step 4: 组装 ───
    Write-Host "[4/4] Assembling distribution..." -ForegroundColor Yellow

    # lib/
    $libDir = Join-Path $distDir "lib"
    New-Item -ItemType Directory -Path $libDir -Force | Out-Null
    Copy-Item $jarFile.FullName (Join-Path $libDir "claude-code-java.jar")

    # bin/
    $binDir = Join-Path $distDir "bin"
    New-Item -ItemType Directory -Path $binDir -Force | Out-Null

    # ── Windows launcher (claude-code.cmd) ──
    $cmdContent = @'
@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1

set "SCRIPT_DIR=%~dp0"
set "APP_HOME=%SCRIPT_DIR%.."
set "RUNTIME=%APP_HOME%\runtime"
set "JAR=%APP_HOME%\lib\claude-code-java.jar"

if not exist "%RUNTIME%\bin\java.exe" (
    echo Error: Runtime not found at %RUNTIME%
    exit /b 1
)

"%RUNTIME%\bin\java.exe" ^
    --enable-preview ^
    --enable-native-access=ALL-UNNAMED ^
    --sun-misc-unsafe-memory-access=allow ^
    -Xmx512m ^
    -Djava.net.preferIPv4Stack=true ^
    -Dstdout.encoding=UTF-8 ^
    -Dstderr.encoding=UTF-8 ^
    -Dfile.encoding=UTF-8 ^
    -jar "%JAR%" ^
    %*
'@
    Set-Content -Path (Join-Path $binDir "claude-code.cmd") -Value $cmdContent -Encoding ASCII

    # ── Unix launcher (claude-code) ──
    $shContent = @'
#!/bin/sh
# Claude Code Java launcher
SCRIPT="$0"
while [ -L "$SCRIPT" ]; do
    DIR="$(cd "$(dirname "$SCRIPT")" && pwd)"
    SCRIPT="$(readlink "$SCRIPT")"
    [ "${SCRIPT#/}" = "$SCRIPT" ] && SCRIPT="$DIR/$SCRIPT"
done
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

exec "$APP_HOME/runtime/bin/java" \
    --enable-preview \
    --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow \
    -Xmx512m \
    -Djava.net.preferIPv4Stack=true \
    -Dstdout.encoding=UTF-8 \
    -Dstderr.encoding=UTF-8 \
    -Dfile.encoding=UTF-8 \
    -jar "$APP_HOME/lib/claude-code-java.jar" \
    "$@"
'@
    $shContent = $shContent -replace "`r`n", "`n"
    [IO.File]::WriteAllText(
        (Join-Path $binDir "claude-code"),
        $shContent,
        [Text.UTF8Encoding]::new($false)
    )

    # ── 总计 ──
    $totalSize = [math]::Round(
        (Get-ChildItem -Recurse $distDir | Measure-Object -Property Length -Sum).Sum / 1MB, 1
    )

    Write-Host ""
    Write-Host "  Distribution built successfully!" -ForegroundColor Green
    Write-Host "  ================================" -ForegroundColor Green
    Write-Host "  Location : $distDir" -ForegroundColor White
    Write-Host "  Size     : $totalSize MB (Runtime $runtimeSize MB + Jar $jarSize MB)" -ForegroundColor White
    Write-Host "  Platform : $platform" -ForegroundColor White
    Write-Host ""
    Write-Host "  Quick start:" -ForegroundColor Cyan
    if ($isWin) {
        Write-Host "    set AI_API_KEY=your-key" -ForegroundColor Gray
        Write-Host "    $binDir\claude-code.cmd" -ForegroundColor Gray
    } else {
        Write-Host "    chmod +x $binDir/claude-code" -ForegroundColor Gray
        Write-Host "    export AI_API_KEY=your-key" -ForegroundColor Gray
        Write-Host "    $binDir/claude-code" -ForegroundColor Gray
    }
    Write-Host ""
    Write-Host "  Or add bin/ to PATH for global access:" -ForegroundColor Cyan
    if ($isWin) {
        Write-Host "    setx PATH `"%PATH%;$(Resolve-Path $binDir)`"" -ForegroundColor Gray
    } else {
        Write-Host "    export PATH=`"${binDir}:`$PATH`"" -ForegroundColor Gray
    }
    Write-Host ""

} finally {
    Pop-Location
}
