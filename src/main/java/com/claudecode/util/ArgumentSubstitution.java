package com.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Argument substitution for skill/command prompts.
 * Aligns with claude-code/src/utils/argumentSubstitution.ts.
 * <p>
 * Supports:
 * <ul>
 *   <li>$ARGUMENTS — replaced with the full arguments string</li>
 *   <li>$ARGUMENTS[0], $ARGUMENTS[1], … — indexed access</li>
 *   <li>$0, $1, … — shorthand for $ARGUMENTS[n]</li>
 *   <li>Named arguments ($foo, $bar) — mapped from frontmatter argument names</li>
 *   <li>${CLAUDE_SKILL_DIR}, ${CLAUDE_SESSION_ID} — special variables</li>
 * </ul>
 */
public final class ArgumentSubstitution {

    private ArgumentSubstitution() {}

    // $ARGUMENTS[n]
    private static final Pattern INDEXED_PATTERN = Pattern.compile("\\$ARGUMENTS\\[(\\d+)]");
    // $n (not followed by word chars, to avoid matching $100foo)
    private static final Pattern SHORTHAND_PATTERN = Pattern.compile("\\$(\\d+)(?!\\w)");

    /**
     * Parse a raw argument string into individual arguments with shell-quote awareness.
     * Handles double-quoted and single-quoted strings.
     * <p>
     * Examples:
     * <pre>
     *   "foo bar baz"           → ["foo", "bar", "baz"]
     *   "foo \"hello world\" z" → ["foo", "hello world", "z"]
     *   "foo 'hello world' z"   → ["foo", "hello world", "z"]
     * </pre>
     */
    public static List<String> parseArguments(String args) {
        if (args == null || args.isBlank()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        boolean escaped = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && !inSingleQuote) {
                escaped = true;
                continue;
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (Character.isWhitespace(c) && !inDoubleQuote && !inSingleQuote) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }

        return result;
    }

    /**
     * Parse argument names from frontmatter 'arguments' field.
     * Rejects numeric-only names (which conflict with $0, $1 shorthand).
     */
    public static List<String> parseArgumentNames(List<String> argumentNames) {
        if (argumentNames == null || argumentNames.isEmpty()) {
            return List.of();
        }
        return argumentNames.stream()
                .filter(name -> name != null && !name.isBlank() && !name.matches("^\\d+$"))
                .toList();
    }

    /**
     * Generate argument hint showing remaining unfilled args.
     *
     * @param argNames  argument names from frontmatter
     * @param typedArgs arguments the user has typed so far
     * @return hint like "[arg2] [arg3]" or null if all filled
     */
    public static String generateProgressiveArgumentHint(List<String> argNames, List<String> typedArgs) {
        if (argNames == null || argNames.size() <= typedArgs.size()) {
            return null;
        }
        return argNames.subList(typedArgs.size(), argNames.size()).stream()
                .map(name -> "[" + name + "]")
                .reduce((a, b) -> a + " " + b)
                .orElse(null);
    }

    /**
     * Substitute argument placeholders in content.
     * <p>
     * Order of substitution (matching TS):
     * <ol>
     *   <li>Named arguments: $foo, $bar → mapped by position from argumentNames</li>
     *   <li>Indexed access: $ARGUMENTS[0], $ARGUMENTS[1], …</li>
     *   <li>Shorthand indexed: $0, $1, …</li>
     *   <li>Full arguments: $ARGUMENTS → raw args string</li>
     *   <li>Auto-append: if no placeholder matched and args non-empty, append "ARGUMENTS: {args}"</li>
     * </ol>
     *
     * @param content              the content containing placeholders
     * @param args                 the raw arguments string (null = no args, return unchanged)
     * @param appendIfNoPlaceholder if true, appends "ARGUMENTS: {args}" when no placeholder found
     * @param argumentNames        named arguments from frontmatter
     */
    public static String substituteArguments(String content, String args,
                                             boolean appendIfNoPlaceholder,
                                             List<String> argumentNames) {
        if (content == null) return "";
        // null means no args provided — return content unchanged
        if (args == null) return content;

        List<String> parsedArgs = parseArguments(args);
        List<String> validNames = parseArgumentNames(argumentNames);
        String original = content;

        // 1. Replace named arguments: $foo, $bar (not followed by [ or word chars)
        for (int i = 0; i < validNames.size(); i++) {
            String name = validNames.get(i);
            String value = i < parsedArgs.size() ? parsedArgs.get(i) : "";
            // Match $name but not $name[…] or $nameXxx
            content = content.replaceAll("\\$" + Pattern.quote(name) + "(?![\\[\\w])",
                    Matcher.quoteReplacement(value));
        }

        // 2. Replace indexed: $ARGUMENTS[0], $ARGUMENTS[1], …
        content = INDEXED_PATTERN.matcher(content).replaceAll(mr -> {
            int idx = Integer.parseInt(mr.group(1));
            return Matcher.quoteReplacement(idx < parsedArgs.size() ? parsedArgs.get(idx) : "");
        });

        // 3. Replace shorthand: $0, $1, …
        content = SHORTHAND_PATTERN.matcher(content).replaceAll(mr -> {
            int idx = Integer.parseInt(mr.group(1));
            return Matcher.quoteReplacement(idx < parsedArgs.size() ? parsedArgs.get(idx) : "");
        });

        // 4. Replace $ARGUMENTS with full raw args string
        content = content.replace("$ARGUMENTS", args);

        // 5. Auto-append if no placeholder found and args non-empty
        if (content.equals(original) && appendIfNoPlaceholder && !args.isBlank()) {
            content = content + "\n\nARGUMENTS: " + args;
        }

        return content;
    }

    /**
     * Overload with default appendIfNoPlaceholder=true, no named args.
     */
    public static String substituteArguments(String content, String args) {
        return substituteArguments(content, args, true, List.of());
    }
}
