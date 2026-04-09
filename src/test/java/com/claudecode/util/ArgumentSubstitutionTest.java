package com.claudecode.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArgumentSubstitutionTest {

    @Test
    void parseArguments_simpleWhitespace() {
        assertEquals(List.of("foo", "bar", "baz"), ArgumentSubstitution.parseArguments("foo bar baz"));
    }

    @Test
    void parseArguments_doubleQuotedString() {
        assertEquals(List.of("foo", "hello world", "baz"),
                ArgumentSubstitution.parseArguments("foo \"hello world\" baz"));
    }

    @Test
    void parseArguments_singleQuotedString() {
        assertEquals(List.of("foo", "hello world", "baz"),
                ArgumentSubstitution.parseArguments("foo 'hello world' baz"));
    }

    @Test
    void parseArguments_escapedSpaces() {
        assertEquals(List.of("hello world"), ArgumentSubstitution.parseArguments("hello\\ world"));
    }

    @Test
    void parseArguments_emptyAndNull() {
        assertEquals(List.of(), ArgumentSubstitution.parseArguments(null));
        assertEquals(List.of(), ArgumentSubstitution.parseArguments(""));
        assertEquals(List.of(), ArgumentSubstitution.parseArguments("   "));
    }

    @Test
    void parseArgumentNames_filtersNumericOnly() {
        assertEquals(List.of("foo", "bar"),
                ArgumentSubstitution.parseArgumentNames(List.of("foo", "123", "bar", "0")));
    }

    @Test
    void substituteArguments_fullArguments() {
        String result = ArgumentSubstitution.substituteArguments(
                "Run $ARGUMENTS now", "test.js", true, List.of());
        assertEquals("Run test.js now", result);
    }

    @Test
    void substituteArguments_indexedAccess() {
        String result = ArgumentSubstitution.substituteArguments(
                "File: $ARGUMENTS[0] Line: $ARGUMENTS[1]", "test.js 42", true, List.of());
        assertEquals("File: test.js Line: 42", result);
    }

    @Test
    void substituteArguments_shorthandIndexed() {
        String result = ArgumentSubstitution.substituteArguments(
                "File: $0 Line: $1", "test.js 42", true, List.of());
        assertEquals("File: test.js Line: 42", result);
    }

    @Test
    void substituteArguments_namedArguments() {
        String result = ArgumentSubstitution.substituteArguments(
                "File: $file Line: $line", "test.js 42", true, List.of("file", "line"));
        assertEquals("File: test.js Line: 42", result);
    }

    @Test
    void substituteArguments_quotedMultiWord() {
        String result = ArgumentSubstitution.substituteArguments(
                "Greeting: $0", "\"hello world\"", true, List.of());
        assertEquals("Greeting: hello world", result);
    }

    @Test
    void substituteArguments_autoAppendWhenNoPlaceholder() {
        String result = ArgumentSubstitution.substituteArguments(
                "No placeholders here.", "some args", true, List.of());
        assertEquals("No placeholders here.\n\nARGUMENTS: some args", result);
    }

    @Test
    void substituteArguments_noAutoAppendWhenDisabled() {
        String result = ArgumentSubstitution.substituteArguments(
                "No placeholders here.", "some args", false, List.of());
        assertEquals("No placeholders here.", result);
    }

    @Test
    void substituteArguments_nullArgsUnchanged() {
        String content = "Content with $ARGUMENTS placeholder";
        assertSame(content, ArgumentSubstitution.substituteArguments(content, null, true, List.of()));
    }

    @Test
    void substituteArguments_emptyArgsReplaces() {
        String result = ArgumentSubstitution.substituteArguments(
                "Run $ARGUMENTS now", "", true, List.of());
        assertEquals("Run  now", result);
    }

    @Test
    void generateProgressiveArgumentHint_basic() {
        assertEquals("[arg2] [arg3]",
                ArgumentSubstitution.generateProgressiveArgumentHint(
                        List.of("arg1", "arg2", "arg3"), List.of("val1")));
    }

    @Test
    void generateProgressiveArgumentHint_allFilled() {
        assertNull(ArgumentSubstitution.generateProgressiveArgumentHint(
                List.of("arg1"), List.of("val1")));
    }
}
