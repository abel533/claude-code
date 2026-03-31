/**
 * Build script for Claude Code using Bun's bundler API.
 *
 * Handles:
 * - MACRO.* build-time constant injection
 * - bun:bundle feature flag polyfill (all flags default to false)
 * - src/ path alias resolution
 * - Internal Anthropic package stubs
 *
 * Usage: bun run scripts/build.ts
 */

import { type BunPlugin } from "bun";
import { existsSync, mkdirSync } from "fs";
import { dirname, join, relative } from "path";

const PROJECT_ROOT = join(import.meta.dir, "..");
const OUT_DIR = join(PROJECT_ROOT, "dist");

// Ensure output directory exists
mkdirSync(OUT_DIR, { recursive: true });

// Build-time MACRO values
const VERSION = process.env.CLAUDE_CODE_VERSION || "1.0.0-research";
const BUILD_TIME = new Date().toISOString();
const PACKAGE_URL = "@anthropic-ai/claude-code";
const FEEDBACK_CHANNEL = "https://github.com/anthropics/claude-code/issues";
const ISSUES_EXPLAINER =
  "report issues at https://github.com/anthropics/claude-code/issues";
const VERSION_CHANGELOG = "";

// Feature flags — all disabled by default for external builds
const FEATURE_FLAGS: Record<string, boolean> = {
  PROACTIVE: false,
  KAIROS: false,
  BRIDGE_MODE: false,
  DAEMON: false,
  VOICE_MODE: false,
  AGENT_TRIGGERS: false,
  MONITOR_TOOL: false,
  COORDINATOR_MODE: false,
  ABLATION_BASELINE: false,
  DUMP_SYSTEM_PROMPT: false,
  CHICAGO_MCP: false,
};

/**
 * Plugin to handle `bun:bundle` imports.
 * The `feature()` function returns false for all flags in external builds.
 */
const bunBundlePlugin: BunPlugin = {
  name: "bun-bundle-polyfill",
  setup(build) {
    build.onResolve({ filter: /^bun:bundle$/ }, (args) => {
      return {
        path: "bun:bundle",
        namespace: "bun-bundle-polyfill",
      };
    });
    build.onLoad(
      { filter: /.*/, namespace: "bun-bundle-polyfill" },
      (args) => {
        const featureFnBody = Object.entries(FEATURE_FLAGS)
          .map(([k, v]) => `    if (name === ${JSON.stringify(k)}) return ${v};`)
          .join("\n");

        return {
          contents: `
export function feature(name) {
${featureFnBody}
    return false;
}
`,
          loader: "js",
        };
      }
    );
  },
};

/**
 * Plugin to provide inline stubs for internal Anthropic packages.
 * Each package gets properly named exports to satisfy static analysis.
 */
const internalPackageStubPlugin: BunPlugin = {
  name: "internal-package-stubs",
  setup(build) {
    // Map of package names to their required value exports
    const packageExports: Record<string, string[]> = {
      "@ant/claude-for-chrome-mcp": [
        "BROWSER_TOOLS[]",
        "createClaudeForChromeMcpServer",
      ],
      "@ant/computer-use-input": [],
      "@ant/computer-use-mcp": [
        "API_RESIZE_PARAMS{}",
        "DEFAULT_GRANT_FLAGS{}",
        "bindSessionContext",
        "buildComputerUseTools",
        "createComputerUseMcpServer",
        "targetImageSize",
        "getSentinelCategory",
      ],
      "@ant/computer-use-mcp/sentinelApps": ["getSentinelCategory"],
      "@ant/computer-use-mcp/types": [
        "DEFAULT_GRANT_FLAGS{}",
        "getSentinelCategory",
      ],
      "@ant/computer-use-swift": [],
      "@anthropic-ai/claude-agent-sdk": [],
      "@anthropic-ai/mcpb": [],
      "@anthropic-ai/sandbox-runtime": [
        "SandboxManager",
        "SandboxRuntimeConfigSchema",
        "SandboxViolationStore",
      ],
      "@anthropic-ai/foundry-sdk": [],
      "color-diff-napi": ["ColorDiff", "ColorFile", "getSyntaxTheme"],
      "modifiers-napi": ["isModifierPressed"],
    };

    // Match any package in our map (including subpaths like @ant/computer-use-mcp/types)
    build.onResolve(
      { filter: /^(@ant\/|@anthropic-ai\/(sandbox-runtime|claude-agent-sdk|mcpb|foundry-sdk)|color-diff-napi|modifiers-napi)/ },
      (args) => ({
        path: args.path,
        namespace: "internal-stub",
      })
    );

    build.onLoad(
      { filter: /.*/, namespace: "internal-stub" },
      (args) => {
        // Find the best matching exports for this path
        const exports = packageExports[args.path] || [];

        const exportLines = exports
          .map((name) => {
            // Array exports (name[])
            if (name.endsWith("[]")) {
              const cleanName = name.slice(0, -2);
              return `export const ${cleanName} = [];`;
            }
            // Object exports (name{})
            if (name.endsWith("{}")) {
              const cleanName = name.slice(0, -2);
              return `export const ${cleanName} = {};`;
            }
            // ColorDiff/ColorFile need render() method
            if (name === "ColorDiff" || name === "ColorFile") {
              return `export class ${name} { constructor(...a){} render(...a){ return null; } static create(...a){ return new ${name}(); } }`;
            }
            if (
              name === "SandboxManager" ||
              name === "SandboxViolationStore"
            ) {
              return `export class ${name} {
  constructor(...a){}
  static create(...a){ return new ${name}(); }
  static isSupportedPlatform(){ return false; }
  static checkDependencies(...a){ return { satisfied: false, missing: [] }; }
  static wrapWithSandbox(...a){ return a[0]; }
  static initialize(...a){ return Promise.resolve(); }
  static updateConfig(...a){}
  static reset(){ return Promise.resolve(); }
  static getFsReadConfig(){ return {}; }
  static getFsWriteConfig(){ return {}; }
  static getNetworkRestrictionConfig(){ return {}; }
  static getIgnoreViolations(){ return {}; }
  static getAllowUnixSockets(){ return false; }
  static getAllowLocalBinding(){ return false; }
  static getEnableWeakerNestedSandbox(){ return false; }
  static getProxyPort(){ return 0; }
  static getSocksProxyPort(){ return 0; }
  static getLinuxHttpSocketPath(){ return ''; }
  static getLinuxSocksSocketPath(){ return ''; }
  static waitForNetworkInitialization(){ return Promise.resolve(); }
  static getSandboxViolationStore(){ return new ${name === 'SandboxManager' ? 'SandboxViolationStore' : name}(); }
  static annotateStderrWithSandboxFailures(...a){ return a[0]; }
  static cleanupAfterCommand(){ return Promise.resolve(); }
  dispose(){}
}`;
            }
            if (name === "SandboxRuntimeConfigSchema") {
              return `export const ${name} = { parse: (...a) => ({}), safeParse: (...a) => ({ success: true, data: {} }) };`;
            }
            if (
              name.startsWith("create") ||
              name.startsWith("build") ||
              name.startsWith("bind") ||
              name.startsWith("get") ||
              name === "targetImageSize"
            ) {
              return `export function ${name}(...args) { return {}; }`;
            }
            // Default: export as empty object/array
            if (name.endsWith("[]")) {
              const cleanName = name.slice(0, -2);
              return `export const ${cleanName} = [];`;
            }
            return `export const ${name} = {};`;
          })
          .join("\n");

        return {
          contents: `// Stub: ${args.path}\nexport default {};\n${exportLines}\n`,
          loader: "js",
        };
      }
    );
  },
};

/**
 * Plugin to auto-stub missing source files at build time.
 * When the bundler encounters an import to a file that doesn't exist on disk,
 * this plugin provides a stub implementation instead of failing the build.
 * This eliminates the need for physical stub files in the source tree.
 */
const missingSourceStubPlugin: BunPlugin = {
  name: "missing-source-stubs",
  setup(build) {
    // 需要特定 named exports 的缺失模块（key: 相对路径，不含扩展名）
    const specificStubs: Record<string, string> = {
      "src/types/connectorText": [
        "export default {};",
        "export function isConnectorTextBlock(block) { return block?.type === 'connector_text'; }",
      ].join("\n"),
      "src/utils/protectedNamespace": [
        "export default {};",
        "export const PROTECTED_NAMESPACE_PREFIXES = [];",
        "export function isProtectedNamespace(_name) { return false; }",
      ].join("\n"),
      "src/tools/WorkflowTool/constants": [
        "export default {};",
        "export const WORKFLOW_TOOL_NAME = 'workflow';",
      ].join("\n"),
      "src/utils/filePersistence/types": [
        "export default {};",
        "export const DEFAULT_UPLOAD_CONCURRENCY = 5;",
        "export const FILE_COUNT_LIMIT = 1000;",
        "export const OUTPUTS_SUBDIR = 'outputs';",
      ].join("\n"),
      "src/tools/REPLTool/REPLTool": [
        "export default {};",
        "export const REPLTool = { name: 'repl', description: 'REPL tool (stub)' };",
      ].join("\n"),

      "src/tools/SuggestBackgroundPRTool/SuggestBackgroundPRTool": [
        "export default {};",
        "export const SuggestBackgroundPRTool = { name: 'suggest_background_pr', description: 'stub' };",
      ].join("\n"),
      "src/tools/TungstenTool/TungstenLiveMonitor": [
        "export default {};",
        "export const TungstenLiveMonitor = {};",
      ].join("\n"),
      "src/tools/TungstenTool/TungstenTool": [
        "export default {};",
        "export const TungstenTool = { name: 'tungsten', description: 'Tungsten tool (stub)' };",
      ].join("\n"),
      "src/tools/VerifyPlanExecutionTool/VerifyPlanExecutionTool": [
        "export default {};",
        "export const VerifyPlanExecutionTool = { name: 'verify_plan_execution', description: 'stub' };",
      ].join("\n"),
      "src/assistant/AssistantSessionChooser": [
        "export default {};",
        "export const AssistantSessionChooser = () => null;",
      ].join("\n"),
      "src/components/agents/SnapshotUpdateDialog": [
        "export default {};",
        "export const SnapshotUpdateDialog = () => null;",
      ].join("\n"),
      "src/commands/assistant/assistant": [
        "export default {};",
        "export const NewInstallWizard = () => null;",
        "export function computeDefaultInstallDir() { return ''; }",
      ].join("\n"),
      "src/services/remoteManagedSettings/securityCheck": [
        "export default {};",
        "export function checkManagedSettingsSecurity() { return { ok: true }; }",
        "export function handleSecurityCheckResult() {}",
      ].join("\n"),
    };

    build.onResolve({ filter: /\.(?:ts|tsx|js|jsx|md|txt)$/ }, (args) => {
      if (args.namespace !== "file") return;

      let basePath: string;
      if (args.path.startsWith("src/")) {
        basePath = join(PROJECT_ROOT, args.path);
      } else if (args.path.startsWith(".") || args.path.startsWith("/")) {
        if (!args.importer) return;
        basePath = join(dirname(args.importer), args.path);
      } else {
        return;
      }

      // 对 .js/.jsx 导入尝试 .ts/.tsx 变体
      const candidates = [basePath];
      if (basePath.endsWith(".js")) {
        const base = basePath.slice(0, -3);
        candidates.push(base + ".ts", base + ".tsx", base + ".d.ts");
      } else if (basePath.endsWith(".jsx")) {
        const base = basePath.slice(0, -4);
        candidates.push(base + ".tsx", base + ".ts");
      }

      for (const c of candidates) {
        if (existsSync(c)) return;
      }

      // 文件不存在 → 重定向到 stub 命名空间
      let stubKey = relative(PROJECT_ROOT, basePath).replace(/\\/g, "/");
      if (stubKey.endsWith(".js") || stubKey.endsWith(".jsx")) {
        stubKey = stubKey.replace(/\.jsx?$/, "");
      }

      return { path: stubKey, namespace: "missing-source" };
    });

    build.onLoad({ filter: /.*/, namespace: "missing-source" }, (args) => {
      const key = args.path;

      if (key.endsWith(".md") || key.endsWith(".txt")) {
        return { contents: `export default '';`, loader: "js" };
      }

      if (key.endsWith(".d.ts")) {
        return { contents: `export {};`, loader: "js" };
      }

      const stubContent = specificStubs[key];
      if (stubContent !== undefined) {
        return { contents: stubContent, loader: "js" };
      }

      console.log(`  ⚠️  Auto-stubbing missing module: ${key}`);
      return { contents: `export default {};`, loader: "js" };
    });
  },
};

console.log("🔨 Building Claude Code...");
console.log(`  Version: ${VERSION}`);
console.log(`  Build time: ${BUILD_TIME}`);
console.log(`  Output: ${OUT_DIR}`);
console.log(`  Feature flags: all disabled (external build)`);
console.log("");

const result = await Bun.build({
  entrypoints: [join(PROJECT_ROOT, "src/entrypoints/cli.tsx")],
  outdir: OUT_DIR,
  target: "bun",
  format: "esm",
  splitting: false,
  sourcemap: "external",
  minify: false,
  plugins: [bunBundlePlugin, internalPackageStubPlugin, missingSourceStubPlugin],
  define: {
    "MACRO.VERSION": JSON.stringify(VERSION),
    "MACRO.BUILD_TIME": JSON.stringify(BUILD_TIME),
    "MACRO.PACKAGE_URL": JSON.stringify(PACKAGE_URL),
    "MACRO.NATIVE_PACKAGE_URL": "undefined",
    "MACRO.FEEDBACK_CHANNEL": JSON.stringify(FEEDBACK_CHANNEL),
    "MACRO.ISSUES_EXPLAINER": JSON.stringify(ISSUES_EXPLAINER),
    "MACRO.VERSION_CHANGELOG": JSON.stringify(VERSION_CHANGELOG),
  },
  external: [
    // Node.js built-ins
    "node:*",
    "fs",
    "fs/promises",
    "path",
    "crypto",
    "os",
    "util",
    "url",
    "http",
    "https",
    "net",
    "tls",
    "dns",
    "stream",
    "events",
    "buffer",
    "child_process",
    "process",
    "readline",
    "tty",
    "v8",
    "zlib",
    "async_hooks",
    "perf_hooks",
    // Keep all npm deps external (not bundled)
    "@alcalzone/ansi-tokenize",
    "@anthropic-ai/sdk",
    "@aws-sdk/client-bedrock-runtime",
    "@commander-js/extra-typings",
    "@growthbook/growthbook",
    "@modelcontextprotocol/sdk",
    "@opentelemetry/*",
    "ajv",
    "asciichart",
    "auto-bind",
    "axios",
    "bidi-js",
    "chalk",
    "chokidar",
    "cli-boxes",
    "code-excerpt",
    "diff",
    "emoji-regex",
    "env-paths",
    "execa",
    "figures",
    "fuse.js",
    "get-east-asian-width",
    "google-auth-library",
    "highlight.js",
    "https-proxy-agent",
    "ignore",
    "indent-string",
    "jsonc-parser",
    "lodash-es",
    "lru-cache",
    "marked",
    "p-map",
    "picomatch",
    "proper-lockfile",
    "qrcode",
    "react",
    "react-reconciler",
    "semver",
    "shell-quote",
    "signal-exit",
    "stack-utils",
    "strip-ansi",
    "supports-hyperlinks",
    "tree-kill",
    "type-fest",
    "undici",
    "usehooks-ts",
    "vscode-jsonrpc",
    "vscode-languageserver-protocol",
    "vscode-languageserver-types",
    "wrap-ansi",
    "ws",
    "xss",
    "zod",
    "@anthropic-ai/bedrock-sdk",
    "@anthropic-ai/vertex-sdk",
    "@anthropic-ai/foundry-sdk",
    "@azure/identity",
    "@aws-sdk/client-bedrock",
    "@aws-sdk/client-sts",
    "fflate",
    "yaml",
    "sharp",
    "modifiers-napi",
    "turndown",
  ],
});

if (!result.success) {
  console.error("❌ Build failed!");
  for (const log of result.logs) {
    console.error(`  ${log.message}`);
  }
  process.exit(1);
} else {
  console.log(`✅ Build succeeded!`);
  console.log(`  Output files:`);
  for (const output of result.outputs) {
    const sizeKB = (output.size / 1024).toFixed(1);
    console.log(`    ${output.path} (${sizeKB} KB)`);
  }
}
