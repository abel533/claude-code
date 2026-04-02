#!/usr/bin/env bash
#
# 构建 Claude Code Java 发行版（jlink 最小 JRE + fat jar）
#
# 用法:
#   ./packaging/build-dist.sh                          # 使用 JAVA_HOME
#   JAVA_HOME=/path/to/jdk25 ./packaging/build-dist.sh # 指定 JDK
#   ./packaging/build-dist.sh --skip-build             # 跳过 Maven 构建
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="${OUTPUT_DIR:-dist}"
SKIP_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --help|-h)
            echo "Usage: $0 [--skip-build]"
            echo "  Environment: JAVA_HOME (required), OUTPUT_DIR (default: dist)"
            exit 0 ;;
    esac
done

# ─── 验证环境 ───
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "Error: JAVA_HOME not set or JDK not found."
    echo "  export JAVA_HOME=/path/to/jdk-25"
    exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"
JAVA_VERSION=$("$JAVA_HOME/bin/java" --version 2>&1 | head -1)

echo ""
echo "  Claude Code Java - Distribution Builder"
echo "  ========================================"
echo "  JDK:    $JAVA_VERSION"
echo "  Output: $OUTPUT_DIR"

# 检测平台
OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS" in
    Darwin)
        PLATFORM="macos-$([ "$ARCH" = "arm64" ] && echo "aarch64" || echo "x64")" ;;
    Linux)
        PLATFORM="linux-$([ "$ARCH" = "aarch64" ] && echo "aarch64" || echo "x64")" ;;
    *)
        PLATFORM="unknown-$OS-$ARCH" ;;
esac
echo "  Platform: $PLATFORM"
echo ""

cd "$PROJECT_ROOT"

# ─── Step 1: Maven 构建 ───
if [ "$SKIP_BUILD" = false ]; then
    echo "[1/4] Building fat jar with Maven..."
    mvn package -q -DskipTests
    echo "  OK Build succeeded"
else
    echo "[1/4] Skipping build (using existing jar)"
fi

JAR_FILE=$(find target -maxdepth 1 -name "*.jar" ! -name "*original*" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "Error: No jar found in target/. Run without --skip-build."
    exit 1
fi
JAR_SIZE=$(du -m "$JAR_FILE" | cut -f1)
echo "  Jar: $(basename "$JAR_FILE") (${JAR_SIZE} MB)"

# ─── Step 2: jdeps 分析 ───
echo "[2/4] Analyzing module dependencies with jdeps..."
DETECTED=$("$JAVA_HOME/bin/jdeps" --print-module-deps --ignore-missing-deps --multi-release 25 "$JAR_FILE" 2>/dev/null || echo "")
if [ -z "$DETECTED" ]; then
    echo "  Warning: jdeps failed, using defaults"
    DETECTED="java.base,java.desktop,java.management,java.net.http"
fi
echo "  Detected: $DETECTED"

# 追加 Spring Boot 运行时模块
EXTRA="java.naming,java.xml,java.sql,java.instrument,java.compiler,java.scripting,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.jfr,jdk.net"
ALL_MODULES=$(echo "$DETECTED,$EXTRA" | tr ',' '\n' | sort -u | tr '\n' ',' | sed 's/,$//')
echo "  Final: $ALL_MODULES"

# ─── Step 3: jlink ───
echo "[3/4] Creating minimal JRE with jlink..."
DIST_DIR="$PROJECT_ROOT/$OUTPUT_DIR"
RUNTIME_DIR="$DIST_DIR/runtime"

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

"$JAVA_HOME/bin/jlink" \
    --add-modules "$ALL_MODULES" \
    --output "$RUNTIME_DIR" \
    --strip-debug \
    --compress zip-6 \
    --no-header-files \
    --no-man-pages

RUNTIME_SIZE=$(du -sm "$RUNTIME_DIR" | cut -f1)
echo "  OK Runtime: ${RUNTIME_SIZE} MB"

# ─── Step 4: 组装 ───
echo "[4/4] Assembling distribution..."

# lib/
mkdir -p "$DIST_DIR/lib"
cp "$JAR_FILE" "$DIST_DIR/lib/claude-code-java.jar"

# bin/
mkdir -p "$DIST_DIR/bin"

# Unix launcher
cat > "$DIST_DIR/bin/claude-code" << 'LAUNCHER'
#!/bin/sh
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
LAUNCHER
chmod +x "$DIST_DIR/bin/claude-code"

# Windows launcher
cat > "$DIST_DIR/bin/claude-code.cmd" << 'LAUNCHER'
@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul 2>&1
set "SCRIPT_DIR=%~dp0"
set "APP_HOME=%SCRIPT_DIR%.."
"%APP_HOME%\runtime\bin\java.exe" ^
    --enable-preview ^
    --enable-native-access=ALL-UNNAMED ^
    --sun-misc-unsafe-memory-access=allow ^
    -Xmx512m ^
    -Djava.net.preferIPv4Stack=true ^
    -Dstdout.encoding=UTF-8 ^
    -Dstderr.encoding=UTF-8 ^
    -Dfile.encoding=UTF-8 ^
    -jar "%APP_HOME%\lib\claude-code-java.jar" ^
    %*
LAUNCHER

# 统计
TOTAL_SIZE=$(du -sm "$DIST_DIR" | cut -f1)
echo ""
echo "  Distribution built successfully!"
echo "  ================================"
echo "  Location : $DIST_DIR"
echo "  Size     : ${TOTAL_SIZE} MB (Runtime ${RUNTIME_SIZE} MB + Jar ${JAR_SIZE} MB)"
echo "  Platform : $PLATFORM"
echo ""
echo "  Quick start:"
echo "    export AI_API_KEY=your-key"
echo "    $DIST_DIR/bin/claude-code"
echo ""
echo "  Or add to PATH:"
echo "    export PATH=\"$DIST_DIR/bin:\$PATH\""
echo ""
