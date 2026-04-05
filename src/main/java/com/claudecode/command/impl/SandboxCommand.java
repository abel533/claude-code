package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;

/**
 * /sandbox 命令 —— 沙箱模式切换。
 * 控制工具执行的安全隔离级别。
 */
public class SandboxCommand implements SlashCommand {

    @Override
    public String name() { return "sandbox"; }

    @Override
    public String description() { return "Toggle sandbox mode for tool execution"; }

    @Override
    public String execute(String args, CommandContext context) {
        String trimmed = CommandUtils.parseArgs(args);

        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("🏖", "Sandbox Mode"));

        if (context.agentLoop() == null) {
            return sb.append("  No active agent loop\n").toString();
        }

        var toolCtx = context.agentLoop().getToolContext();

        if (trimmed.equals("on") || trimmed.equals("enable") || trimmed.equals("strict")) {
            toolCtx.set("SANDBOX_MODE", "strict");
            sb.append("  Sandbox: ").append(AnsiStyle.green("STRICT")).append("\n\n");
            sb.append("  Restrictions:\n");
            sb.append("  • File writes limited to work directory\n");
            sb.append("  • Network access: disabled\n");
            sb.append("  • Shell commands: require approval\n");
            sb.append("  • System calls: blocked\n");

        } else if (trimmed.equals("off") || trimmed.equals("disable") || trimmed.equals("none")) {
            toolCtx.set("SANDBOX_MODE", "none");
            sb.append("  Sandbox: ").append(AnsiStyle.red("DISABLED")).append("\n");
            sb.append("  ⚠️  All tool operations are unrestricted.\n");

        } else if (trimmed.equals("permissive")) {
            toolCtx.set("SANDBOX_MODE", "permissive");
            sb.append("  Sandbox: ").append(AnsiStyle.yellow("PERMISSIVE")).append("\n\n");
            sb.append("  Restrictions:\n");
            sb.append("  • File writes: allowed with logging\n");
            sb.append("  • Network access: allowed\n");
            sb.append("  • Shell commands: allowed with logging\n");
            sb.append("  • System calls: require approval\n");

        } else {
            // Show current status
            Object mode = toolCtx.get("SANDBOX_MODE");
            String current = (mode instanceof String m) ? m : "permissive";

            sb.append("  Current mode: ");
            sb.append(switch (current) {
                case "strict" -> AnsiStyle.green("STRICT");
                case "none" -> AnsiStyle.red("NONE");
                default -> AnsiStyle.yellow("PERMISSIVE");
            }).append("\n\n");

            sb.append(AnsiStyle.bold("  Available Modes\n"));
            sb.append("  ┌─────────────┬────────────┬─────────┬──────────┐\n");
            sb.append("  │ Mode        │ File Write │ Network │ Shell    │\n");
            sb.append("  ├─────────────┼────────────┼─────────┼──────────┤\n");
            sb.append("  │ strict      │ work dir   │ blocked │ approval │\n");
            sb.append("  │ permissive  │ logged     │ allowed │ logged   │\n");
            sb.append("  │ none        │ unlimited  │ allowed │ allowed  │\n");
            sb.append("  └─────────────┴────────────┴─────────┴──────────┘\n\n");

            sb.append(AnsiStyle.bold("  Usage\n"));
            sb.append("  /sandbox strict       Enable strict sandbox\n");
            sb.append("  /sandbox permissive   Enable permissive sandbox\n");
            sb.append("  /sandbox off          Disable sandbox\n");
        }

        return sb.toString();
    }
}
