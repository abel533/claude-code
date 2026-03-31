/**
 * Global type declarations for Claude Code build-time constants.
 *
 * MACRO is defined at bundle time via Bun's --define flag.
 * bun:bundle provides compile-time feature flags for dead code elimination.
 */

// Build-time macro constants injected via --define
declare const MACRO: {
  /** Application version string, e.g. "1.0.0" */
  VERSION: string;
  /** ISO 8601 build timestamp, e.g. "2026-03-31T00:00:00Z" */
  BUILD_TIME: string;
  /** npm package URL, e.g. "@anthropic-ai/claude-code" */
  PACKAGE_URL: string;
  /** Native package URL for platform-specific binaries */
  NATIVE_PACKAGE_URL: string | undefined;
  /** Feedback channel URL or description */
  FEEDBACK_CHANNEL: string;
  /** Instructions for reporting issues */
  ISSUES_EXPLAINER: string;
  /** Version changelog content */
  VERSION_CHANGELOG: string;
};

// Bun's bundle-time feature flag module
declare module "bun:bundle" {
  /**
   * Returns true if the named feature flag is enabled at bundle time.
   * Used for dead code elimination — disabled branches are stripped entirely.
   */
  export function feature(name: string): boolean;
}

// Stub declarations for internal Anthropic packages that are not publicly available
declare module "@ant/claude-for-chrome-mcp" {
  const mod: any;
  export default mod;
  export const runClaudeInChromeMcpServer: () => Promise<void>;
}

declare module "@ant/computer-use-input" {
  const mod: any;
  export default mod;
}

declare module "@ant/computer-use-mcp" {
  const mod: any;
  export default mod;
  export const runComputerUseMcpServer: () => Promise<void>;
}

declare module "@ant/computer-use-swift" {
  const mod: any;
  export default mod;
}

declare module "@anthropic-ai/claude-agent-sdk" {
  const mod: any;
  export default mod;
}

declare module "@anthropic-ai/mcpb" {
  const mod: any;
  export default mod;
}

declare module "@anthropic-ai/sandbox-runtime" {
  const mod: any;
  export default mod;
}

declare module "color-diff-napi" {
  export function diff(a: string, b: string): any;
  const mod: any;
  export default mod;
}
