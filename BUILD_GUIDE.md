# Claude Code 源码编译指南

> 本文档记录了为 Claude Code 源码快照补充缺失配置文件并成功编译运行的完整过程。

## 概述

Claude Code 的源码快照仅包含 `src/` 目录和 `README.md`，缺少所有构建配置文件。本指南涵盖了从零开始恢复构建环境的全部步骤。

---

## 补充的配置文件

### 1. `package.json`

项目的核心配置文件，定义了：

- **包名**: `@anthropic-ai/claude-code`
- **入口点**: `src/entrypoints/cli.tsx`
- **构建产物**: `dist/cli.js`
- **脚本命令**:
  - `bun run build` — 执行构建脚本
  - `bun run dev` — 直接运行源码（开发模式）
  - `bun run typecheck` — TypeScript 类型检查

**依赖项分为三类：**

| 分类 | 数量 | 示例 |
|------|------|------|
| 公开 npm 包 | ~75 个 | `react`, `chalk`, `zod`, `@anthropic-ai/sdk` |
| Anthropic 内部包（需 stub） | ~10 个 | `@ant/*`, `@anthropic-ai/sandbox-runtime` |
| 开发依赖 | ~13 个 | `typescript`, `@types/react`, `@types/bun` |

### 2. `tsconfig.json`

TypeScript 编译配置，关键设置：

- **模块系统**: ESNext + bundler resolution
- **JSX**: `react-jsx`（React 19 的新 JSX 转换）
- **路径别名**: `src/*` → `./src/*`（项目中大量使用 `import from 'src/...'` 格式的导入）
- **目标**: ESNext（Bun 原生支持）
- **类型**: 同时包含 `bun-types` 和 `node` 类型定义

### 3. `globals.d.ts`

全局类型声明文件，定义了：

- **`MACRO` 常量**：构建时注入的宏定义
  - `MACRO.VERSION` — 版本号
  - `MACRO.BUILD_TIME` — 构建时间戳
  - `MACRO.PACKAGE_URL` — npm 包地址
  - `MACRO.NATIVE_PACKAGE_URL` — 原生包地址
  - `MACRO.FEEDBACK_CHANNEL` — 反馈渠道
  - `MACRO.ISSUES_EXPLAINER` — 问题报告说明
  - `MACRO.VERSION_CHANGELOG` — 版本变更日志

- **`bun:bundle` 模块**：Bun 构建时特性标志（feature flags）模块的类型声明
- **内部包的类型 stub**：为不公开的 Anthropic 内部包提供类型定义

### 4. `scripts/build.ts`

核心构建脚本，使用 Bun 的 `Bun.build()` API，处理以下关键问题：

#### a) `bun:bundle` Feature Flags 处理

原始代码使用 `import { feature } from 'bun:bundle'` 进行编译时死代码消除。构建脚本通过自定义 Bun 插件为 `feature()` 函数提供 polyfill，所有特性标志在外部构建中默认返回 `false`：

```
PROACTIVE, KAIROS, BRIDGE_MODE, DAEMON, VOICE_MODE,
AGENT_TRIGGERS, MONITOR_TOOL, COORDINATOR_MODE,
ABLATION_BASELINE, DUMP_SYSTEM_PROMPT, CHICAGO_MCP
```

#### b) `MACRO.*` 构建时常量注入

通过 `Bun.build()` 的 `define` 选项在编译时替换所有 `MACRO.*` 引用为实际值。

#### c) 内部包 Stub 插件

为不公开的 Anthropic 内部包（`@ant/*`、`@anthropic-ai/sandbox-runtime` 等）提供内联 stub，确保编译时所有命名导出都能正确解析，同时不引入运行时依赖。

#### d) 缺失源文件自动 Stub 插件

源码快照中缺少部分文件（被 `feature()` 特性标志保护或属于内部模块）。构建脚本通过 `missingSourceStubPlugin` 插件**在构建时自动检测并 stub 这些缺失文件**，无需在源码树中手动创建 stub 文件：

- 通过 `onResolve` 钩子拦截所有源文件导入，检测目标文件是否存在于磁盘
- 不存在的文件自动重定向到虚拟 `missing-source` 命名空间
- 对需要特定 named exports 的模块（如 `connectorText`、`protectedNamespace` 等）提供精确 stub
- 其余缺失模块返回通用 `export default {}`
- `.md` 和 `.txt` 文件返回空字符串，`.d.ts` 文件返回 `export {}`
- 构建日志中以 `⚠️ Auto-stubbing missing module:` 标记自动 stub 的模块

#### e) 外部依赖处理

所有公开 npm 包标记为 `external`，不打包进产物，运行时从 `node_modules` 解析。

### 5. 源码修复

`src/main.tsx` 中的 `-d2e` 短标志格式与 Commander.js v13 不兼容（v13 要求短标志为单个字符），已修改为仅使用 `--debug-to-stderr` 长标志。

---

## 编译步骤

### 前提条件

- [Bun](https://bun.sh) ≥ 1.1（推荐 1.3+）
- Windows / macOS / Linux

### 步骤 1：安装依赖

```bash
bun install
```

这将安装约 600+ 个包（含传递依赖），耗时约 1-2 分钟。

### 步骤 2：执行构建

```bash
bun run build
```

构建脚本将：
1. 初始化 `bun:bundle` feature flag polyfill
2. 注入 `MACRO.*` 构建时常量
3. 为内部 Anthropic 包生成内联 stub
4. 自动检测并 stub 源码快照中缺失的源文件
5. 打包 `src/entrypoints/cli.tsx` 入口点
6. 输出到 `dist/cli.js`（约 11.7 MB）和 `dist/cli.js.map`

### 步骤 3：验证运行

```bash
# 检查版本
bun dist/cli.js --version
# 输出: 1.0.0-research (Claude Code)

# 查看帮助
bun dist/cli.js --help

# 启动交互式界面（需要 API Key）
bun dist/cli.js
```

### 自定义版本号

```bash
CLAUDE_CODE_VERSION=2.0.0 bun run build
```

---

## 构建产物

| 文件 | 大小 | 说明 |
|------|------|------|
| `dist/cli.js` | ~11.7 MB | 主程序包（ESM 格式） |
| `dist/cli.js.map` | ~38.6 MB | Source Map |

---

## 技术要点

### 架构概览

```
入口点: src/entrypoints/cli.tsx
    ↓ (动态导入)
主程序: src/main.tsx (Commander.js CLI)
    ↓
终端 UI: src/ink/ (自定义 Ink 实现 + React 19)
    ↓
核心系统:
├── src/tools/      (~40 个工具实现)
├── src/commands/   (~50 个斜杠命令)
├── src/services/   (API、MCP、OAuth、分析)
├── src/hooks/      (权限系统)
└── src/coordinator/ (多智能体协调)
```

### 关键技术栈

| 组件 | 技术 |
|------|------|
| 运行时 | Bun |
| 语言 | TypeScript (strict) |
| 终端 UI | React 19 + 自定义 Ink fork |
| CLI 框架 | Commander.js 13 |
| Schema 验证 | Zod v3 |
| 协议 | MCP SDK, LSP |
| API | Anthropic SDK |
| 遥测 | OpenTelemetry 2.x |
| 布局引擎 | 纯 TypeScript yoga-layout 实现 |

### 注意事项

1. **内部包不可用**：`@ant/*` 系列包和部分 `@anthropic-ai/*` 包不在公共 npm 上发布，相关功能（Chrome 集成、计算机使用、沙箱运行时等）在此构建中不可用。

2. **Feature Flags 全部禁用**：所有 `bun:bundle` 特性标志默认返回 `false`，这意味着实验性功能（语音、守护进程、协调器模式等）的代码路径已被消除。

3. **需要 API Key 才能实际使用**：虽然 CLI 可以编译和启动（完整的终端 UI、主题选择等均正常），但实际使用需要 Anthropic API Key 或 OAuth 登录。

4. **React Reconciler 版本**：项目使用了 `useEffectEvent` Hook，需要 `react-reconciler@0.33.0`（而非 0.31.0），该版本才实现了 `useEffectEvent` 调度器。

5. **内部包 Stub 需完整方法签名**：某些内部包（如 `@anthropic-ai/sandbox-runtime` 的 `SandboxManager`）的 stub 需要包含完整的静态方法（如 `isSupportedPlatform`、`checkDependencies`、`wrapWithSandbox` 等），否则运行时在访问未定义属性时会报错。构建脚本已为所有已知的内部类提供了完整方法签名。

6. **非官方构建**：此构建仅用于教育和安全研究目的，不代表 Anthropic 官方发布。
