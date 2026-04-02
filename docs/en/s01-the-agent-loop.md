# s01: The Agent Loop

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"One loop & Bash is all you need"* -- one tool + one loop = an agent.
>
> **Harness layer**: The loop -- the model's first connection to the real world.

## Problem

A language model can reason about code, but it can't *touch* the real world -- can't read files, run tests, or check errors. Without a loop, every tool call requires you to manually copy-paste results back. You become the loop.

## Solution

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> |  Tool   |
| prompt |      |       |      | execute |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (ChatClient.call() auto-loops until no tool calls)
```

A single `call()` invocation controls the entire flow. Spring AI loops automatically until the model stops calling tools.

## How It Works

### 1. Build ChatClient: Inject Model + Register Tools

Inject `ChatModel` via Spring Boot auto-configuration, build the client with `ChatClient.builder()`, set the system prompt and tools.

```java
// TIP: The Python version creates client = Anthropic() and MODEL at module level.
// Spring AI injects ChatModel via auto-configuration, then builds ChatClient with builder.
public S01AgentLoop(ChatModel chatModel) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                    + ". Use bash to solve tasks. Act, don't explain.")
            .defaultTools(new BashTool())   // Tool object with @Tool annotation
            .build();
}
```

### 2. `@Tool` Annotation: Declarative Tool Registration

Spring AI automatically discovers and registers tools via the `@Tool` annotation. At startup, the framework scans objects passed to `defaultTools()`, extracts all `@Tool` method signatures and descriptions, generates the tool schema the LLM needs (name, parameters, description), and automatically includes it in every `call()` request.

```java
// BashTool -- corresponds to the Python version's run_bash() function
public class BashTool {
    @Tool(description = "Run a shell command and return stdout + stderr")
    public String bash(@ToolParam(description = "The shell command to execute")
                       String command) {
        // Dangerous command check + ProcessBuilder execution + timeout control + output truncation
        // ...
    }
}
```

> Comparison with Python's manual registration:
> - Python: `TOOLS = [{"name": "bash", "input_schema": {...}}]` + `TOOL_HANDLERS = {"bash": run_bash}`
> - Java: Just `@Tool` + `@ToolParam` annotations; the framework auto-generates schemas and dispatches methods

### 3. Spring AI Internal Auto-Loop: How `call()` Works Under the Hood

**This is the most critical difference between the Java and Python versions.** The Python version requires a hand-written while loop to drive tool calls:

```python
# Python version -- manual loop
def agent_loop(messages):
    while True:
        response = client.messages.create(model=MODEL, messages=messages, tools=TOOLS)
        # Collect assistant message
        messages.append({"role": "assistant", "content": response.content})
        if response.stop_reason != "tool_use":
            return response           # Model no longer calling tools, exit loop
        # Execute tools and feed back results
        for block in response.content:
            if block.type == "tool_use":
                result = TOOL_HANDLERS[block.name](block.input)
                messages.append({"role": "user", "content": [{"type": "tool_result", ...}]})
```

Spring AI's `ChatClient.call()` **encapsulates fully equivalent logic internally**:

```
call() internal flow:
  ┌─────────────────────────────────────────────────────┐
  │  1. Assemble request: system prompt + user msg + tools │
  │  2. Send to LLM                                     │
  │  3. Parse response                                   │
  │     ├── Has tool_use? ──→ Yes:                      │
  │     │   a. Extract tool name and arguments           │
  │     │   b. Invoke corresponding @Tool method via reflection │
  │     │   c. Append tool_result to message list        │
  │     │   d. Go back to step 2 (auto-loop)            │
  │     └── No ──→ Return final text                    │
  └─────────────────────────────────────────────────────┘
```

Key points:
- **Tool detection**: Spring AI checks if the response contains `tool_use` content blocks (equivalent to Python's `stop_reason == "tool_use"`)
- **Reflection dispatch**: The framework uses Java reflection to find and invoke the `@Tool` method matching the tool name returned by the LLM (equivalent to Python's `TOOL_HANDLERS[block.name]`)
- **Result feedback**: Tool execution results are automatically wrapped as `tool_result` messages and appended to the conversation (equivalent to Python's manual `tool_result` content block construction)
- **Loop termination**: When the model returns pure text (no tool calls), `call()` returns the final result

Thus, Python's ~15-line while loop is condensed into a single `.call()` in Java.

### 4. `AgentRunner.interactive()`: The REPL Interaction Loop

`AgentRunner` is a shared REPL (Read-Eval-Print Loop) utility class used across all lessons, corresponding to the `input()` loop in Python's `if __name__ == "__main__"` block.

```java
public class AgentRunner {
    /**
     * Start an interactive REPL loop.
     * @param prefix  Prompt prefix (e.g., "s01")
     * @param handler Function that processes user input and returns Agent response
     */
    public static void interactive(String prefix, Function<String, String> handler) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Type 'q' or 'exit' to quit");
        while (true) {
            System.out.print("\033[36m" + prefix + " >> \033[0m");  // Colored prompt
            String input;
            try {
                if (!scanner.hasNextLine()) break;
                input = scanner.nextLine().trim();
            } catch (Exception e) {
                break;
            }
            if (input.isEmpty() || "exit".equalsIgnoreCase(input) || "q".equalsIgnoreCase(input)) {
                break;
            }
            try {
                String response = handler.apply(input);  // Call Agent handler
                if (response != null && !response.isBlank()) {
                    System.out.println(response);
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
        System.out.println("Bye!");
    }
}
```

Workflow: `Scanner` reads input → `handler.apply()` sends to Agent → print response → loop. The `handler` is a functional interface; each lesson passes in its own Agent invocation logic.

### 5. Assembled into a Complete Agent Class

```java
@SpringBootApplication(scanBasePackages = "io.mybatis.learn.core")
public class S01AgentLoop implements CommandLineRunner {

    private final ChatClient chatClient;

    public S01AgentLoop(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("You are a coding agent at ...")
                .defaultTools(new BashTool())
                .build();
    }

    @Override
    public void run(String... args) {
        AgentRunner.interactive("s01", userMessage ->
                chatClient.prompt()
                        .user(userMessage)
                        .call()       // ← This single call = Python's entire while loop
                        .content()
        );
    }
}
```

> **TIPS — Key Python → Java Adaptations:**
> - Python's `while True` + `stop_reason` manual loop → Spring AI `ChatClient.call()` built-in auto-loop
> - Python's `TOOLS` array + `TOOL_HANDLERS` dict → `@Tool` annotation + `defaultTools()` auto-registration with reflection dispatch
> - Python's `client = Anthropic()` → Spring Boot auto-configured `ChatModel` injection
> - Python's `input()` interaction → `AgentRunner.interactive()` wrapping Scanner REPL + functional interface

Under 40 lines of core code, and that's the entire agent. The next 11 chapters all layer mechanisms on top of this loop -- the loop itself never changes.

## Source Code Tracing: How the `call()` Internal Loop Is Actually Implemented

The architecture diagram above says "Spring AI auto-loops," but exactly which lines of code make that happen? Below is the complete call chain traced through Spring AI 1.0.3 source code.

### Call Chain Overview

```
Your code: chatClient.prompt().user(msg).call().chatResponse()
    │
    ▼
① DefaultChatClient.DefaultCallResponseSpec.chatResponse()
    │  (DefaultChatClient.java:435-437)
    │  Internally calls doGetObservableChatClientResponse() → advisorChain.nextCall()
    │
    ▼
② Advisor chain (includes your registered ToolCallLoggingAdvisor)
    │  Each advisor executes in turn; the last node is ChatModelCallAdvisor
    │
    ▼
③ ChatModelCallAdvisor.adviseCall()
    │  (ChatModelCallAdvisor.java:49-58)
    │  Core is a single line: this.chatModel.call(prompt)
    │
    ▼
④ AnthropicChatModel.call() → internalCall()
    │  (AnthropicChatModel.java:169-223)
    │  ★ This is where the loop lives! Implemented via recursion, not while ★
```

### Key Source Code: The Recursive Loop in `internalCall`

`AnthropicChatModel.java` lines 176-223 (Spring AI 1.0.3):

```java
// Entry point
public ChatResponse call(Prompt prompt) {
    Prompt requestPrompt = buildRequestPrompt(prompt);
    return this.internalCall(requestPrompt, null);
}

// ★ Loop core: recursive self-invocation
public ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

    // ──── Step 1: Send HTTP request to the Anthropic API ────
    ChatCompletionRequest request = createRequest(prompt, false);

    ChatResponse response = ... // anthropicApi.chatCompletionEntity(request, headers)
    // This is a real HTTP request

    // ──── Step 2: Check if the response contains tool_use ────
    if (this.toolExecutionEligibilityPredicate
            .isToolExecutionRequired(prompt.getOptions(), response)) {

        // ──── Step 3: tool_use found → execute the tool (your BashTool) ────
        var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);

        if (toolExecutionResult.returnDirect()) {
            return ...;  // Tool requests a direct return of results
        } else {
            // ──── Step 4: Recursion! Add tool results to history, call self again ────
            return this.internalCall(
                new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
                response  // Pass previous response (for cumulative token usage)
            );
            // ↑ Recursive call — will send another HTTP request to the AI
        }
    }

    // ──── Step 5: No tool_use → AI has finished answering, return final result ────
    return response;
}
```

### Line-by-Line Mapping to the Python Version

| Python manual loop | Spring AI automatic implementation |
|---|---|
| `while True:` | `internalCall()` recursive self-invocation |
| `response = client.messages.create(...)` | `anthropicApi.chatCompletionEntity(request, ...)` |
| `if response.stop_reason != "tool_use": return` | `isToolExecutionRequired()` returns false → directly `return response` |
| `TOOL_HANDLERS[block.name](block.input)` | `toolCallingManager.executeToolCalls()` → reflection invokes `@Tool` method |
| `messages.append({"role": "user", "content": [tool_result]})` | `buildConversationHistoryAfterToolExecution()` auto-builds |

### Tool Execution Details: `DefaultToolCallingManager`

When `internalCall` detects `tool_use`, it calls `toolCallingManager.executeToolCalls()`. This method (`DefaultToolCallingManager.java:121-148`) does the following:

```java
public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    // 1. Extract tool_call information from the AI response
    AssistantMessage assistantMessage = ...;

    // 2. For each tool_call, find the corresponding @Tool method and execute it
    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
        // Find the ToolCallback by tool name (your BashTool)
        ToolCallback toolCallback = toolCallbacks.stream()
            .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
            .findFirst()...;

        // Invoke the @Tool method via reflection, get result string
        String toolCallResult = toolCallback.call(finalToolInputArguments, toolContext);

        // Wrap as ToolResponseMessage
        toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolCallResult));
    }

    // 3. Build new conversation history = original messages + assistant(tool_use) + tool_result
    List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(
        prompt.getInstructions(), assistantMessage, toolResponseMessage);

    return ToolExecutionResult.builder()
        .conversationHistory(conversationHistory)
        .build();
}
```

### Complete Flow Example

User says `"Create a file hello.txt"`:

```
internalCall() Round 1:
  HTTP → Anthropic: "Create a file hello.txt" + tools=[bash]
  HTTP ← Anthropic: tool_use(bash, "touch hello.txt")
  executeToolCalls() → BashTool.bash("touch hello.txt") → ""
  Recursive call internalCall(history + [assistant(tool_use), tool_result("")])

internalCall() Round 2:
  HTTP → Anthropic: full history + "The tool just returned an empty string"
  HTTP ← Anthropic: tool_use(bash, "echo hello > hello.txt")
  executeToolCalls() → BashTool.bash("echo hello > hello.txt") → ""
  Recursive call internalCall(history + [assistant(tool_use), tool_result("")])

internalCall() Round 3:
  HTTP → Anthropic: full history + "The tool just returned an empty string"
  HTTP ← Anthropic: "I've created hello.txt and written the content for you"
  isToolExecutionRequired() → false (pure text, no tool_use)
  return response  ← Exit recursion, return final result
```

So a single `chatClient.prompt().call()` may trigger **N HTTP requests** (N = number of rounds the AI calls tools + 1 final answer).

> **Note**: The loop is not implemented with `while`, but with **recursion**. `internalCall` calls itself after detecting tool_use, passing the tool results as new conversation history. This approach is better suited for cumulative token usage statistics (passed layer by layer via the `previousChatResponse` parameter).

## What Changed

| Component     | Before     | After                                          |
|---------------|------------|-------------------------------------------------|
| Agent loop    | (none)     | `ChatClient.call()` built-in tool loop          |
| Tools         | (none)     | `BashTool` (single `@Tool` tool)                |
| Messages      | (none)     | Managed internally by Spring AI                 |
| Control flow  | (none)     | Framework auto-detects: returns final text when no tool calls |

```java
// Core code -- build + call
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(new BashTool())
        .build();

AgentRunner.interactive("s01", userMessage ->
        chatClient.prompt().user(userMessage).call().content()
);
```

## Try It

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s01.S01AgentLoop
```

> Set environment variables before running: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`
>
> **The default protocol is OpenAI** (compatible with all OpenAI API-format services, including OpenAI official, Azure OpenAI, and any third-party model services offering an OpenAI-compatible interface).
> To use the Anthropic protocol (Claude native API), expand the section below.

<details>
<summary><strong>Switching AI Protocols (OpenAI ↔ Anthropic)</strong></summary>

This project switches the underlying protocol via **Spring AI Starter dependency + configuration file**. Java business code (`ChatModel`, `ChatClient`) **requires no changes**.

#### Option 1: OpenAI Protocol (Default)

`pom.xml` dependency:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

`application.yml` configuration:

```yaml
spring:
  ai:
    openai:
      api-key: ${AI_API_KEY:sk-xxx}
      base-url: ${AI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:gpt-4o}
```

Environment variable example:

```sh
export AI_API_KEY=sk-proj-xxxxxxxx
export AI_BASE_URL=https://api.openai.com   # Replace with any OpenAI-compatible endpoint
export AI_MODEL=gpt-4o
```

> **TIP**: Many third-party model services (e.g., DeepSeek, Mistral, Qwen) provide OpenAI-compatible APIs. Simply change `AI_BASE_URL` and `AI_MODEL` to connect — no protocol switch needed.

#### Option 2: Anthropic Protocol (Claude Native API)

**Step 1**: Edit `pom.xml` — replace the OpenAI starter with the Anthropic starter:

```xml
<!-- Comment out or remove the OpenAI starter -->
<!-- <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency> -->

<!-- Add the Anthropic starter -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

**Step 2**: Edit `application.yml` — replace `spring.ai.openai` with `spring.ai.anthropic`:

```yaml
spring:
  ai:
    anthropic:
      api-key: ${AI_API_KEY}
      base-url: ${AI_BASE_URL:https://api.anthropic.com}
      chat:
        options:
          model: ${AI_MODEL:claude-sonnet-4-20250514}
```

**Step 3**: Set environment variables:

```sh
export AI_API_KEY=sk-ant-xxxxxxxx
export AI_BASE_URL=https://api.anthropic.com
export AI_MODEL=claude-sonnet-4-20250514
```

#### How Switching Works

Spring AI's `ChatModel` is a unified abstraction interface. Different Starters provide different implementations:

| Starter Dependency | Auto-injected ChatModel | Config Prefix |
|---|---|---|
| `spring-ai-starter-model-openai` | `OpenAiChatModel` | `spring.ai.openai.*` |
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` | `spring.ai.anthropic.*` |

Business code always programs against the `ChatModel` interface. Switching protocols only requires changing the dependency and configuration — no Java code changes needed.

</details>

Try these prompts(English prompts work better with LLMs, but Chinese also works):

1. `Create a file called Hello.java that prints "Hello, World!"`
2. `List all Java files in this directory`
3. `What is the current git branch?`
4. `Create a directory called test_output and write 3 files in it`

## Design Philosophy: Spring AI's 6-Layer Design Patterns

Spring AI has many layers of abstraction -- Builder, Advisor Chain, Observation, Strategy, Recursion, and Callback. Below is a layer-by-layer breakdown of each design pattern, from your code's invocation all the way to the completed HTTP request.

### Panorama: The 6 Layers a `.call()` Passes Through

```
Your code: chatClient.prompt().user(msg).call().chatResponse()
          │
    ┌─────┴─────┐
    │  Layer 1  │  Builder + Fluent API         ← Construction and assembly
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 2  │  Advisor Chain (Chain of Responsibility)  ← Interception and enhancement
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 3  │  Strategy (Strategy Pattern)  ← Multi-model switching
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 4  │  Observation (Observer)       ← Observability
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 5  │  Recursion + Callback         ← Tool loop
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 6  │  HTTP Request                 ← Final execution
    └───────────┘
```

One-sentence summary: **Builder constructs objects, Fluent API guides invocation, Advisor Chain intercepts and enhances, Strategy switches implementations, Observation observes everything, and Recursion drives the loop.**

### Layer 1: Builder Pattern + Fluent API (Fluent Interface)

**Problem solved**: ChatClient has many configuration options (system prompt, tools, advisors, options...). Without Builder, you'd end up with a 10-parameter constructor.

```java
// Builder pattern: step-by-step construction of a complex object
ChatClient chatClient = ChatClient.builder(chatModel)    // ① Create Builder
        .defaultSystem("...")                             // ② Set parameters step by step
        .defaultTools(new BashTool())
        .defaultAdvisors(new ToolCallLoggingAdvisor())
        .build();                                         // ③ Build the final object

// Fluent API (fluent interface): chained calls, each returning this
chatClient.prompt()     // → ChatClientRequestSpec
    .user(msg)          // → ChatClientRequestSpec (still itself)
    .call()             // → CallResponseSpec (different object)
    .chatResponse();    // → ChatResponse
```

**Design rationale**: Builder handles the "construction phase" (immutable configuration), while Fluent API handles the "usage phase" (per-request parameters). The two phases return different types of objects, so the compiler can prevent you from calling `.user()` during the construction phase.

Source code references:
- `DefaultChatClientBuilder.java` -- construction phase
- `DefaultChatClientRequestSpec` (`DefaultChatClient.java:564`) -- usage phase
- `DefaultCallResponseSpec` (`DefaultChatClient.java:341`) -- response phase

### Layer 2: Advisor Chain -- Chain of Responsibility Pattern

**Problem solved**: Insert cross-cutting concerns (logging, caching, authorization, tool call recording) without modifying the core invocation logic.

```
Request → ToolCallLoggingAdvisor → ChatModelCallAdvisor → HTTP
              ↓                        ↓
         Print tool calls          Call chatModel.call()
```

```java
// DefaultChatClient.java:921-931 — Build the chain
private BaseAdvisorChain buildAdvisorChain() {
    // User-registered advisors
    this.advisors.add(new ToolCallLoggingAdvisor());

    // End of chain: the advisor that actually calls the AI (framework auto-added terminator)
    this.advisors.add(ChatModelCallAdvisor.builder()
            .chatModel(this.chatModel).build());

    return DefaultAroundAdvisorChain.builder(observationRegistry)
            .pushAll(this.advisors)
            .build();
}
```

Each Advisor's interface (analogous to Servlet Filter):

```java
// CallAdvisor.java — analogous to the Filter interface
public interface CallAdvisor {
    ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain);
    //                                                request          chain (calls next)
}

// ToolCallLoggingAdvisor — analogous to Filter.doFilter()
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);  // Pass it down the chain first
    // Then extract tool call information from the response and print it
    ... print toolCalls ...
    return response;
}
```

**Analogy**: Completely isomorphic to Servlet Filter, Spring HandlerInterceptor, and Express Middleware. `chain.next()` = `filterChain.doFilter()`.

### Layer 3: Strategy Pattern

**Problem solved**: Your code programs against the `ChatModel` interface only; at runtime it can be Anthropic, OpenAI, or any implementation.

```java
// ChatModel interface — the strategy abstraction
public interface ChatModel {
    ChatResponse call(Prompt prompt);
}

// AnthropicChatModel — strategy implementation A
// OpenAiChatModel   — strategy implementation B
```

Your code never directly references `AnthropicChatModel`:

```java
// S01AgentLoop.java:52 — Injected is the interface, not the implementation
public S01AgentLoop(AiConfig aiConfig) {
    this.chatClient = ChatClient.builder(aiConfig.get())  // aiConfig.get() returns ChatModel
            ...
```

Spring Boot auto-configuration determines which implementation to inject based on the starter on the classpath:

| Starter on classpath | Injected implementation |
|---|---|
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` |
| `spring-ai-starter-model-openai` | `OpenAiChatModel` |

### Layer 4: Observation Pattern (Observer Pattern / Observability)

**Problem solved**: Instrument the entire invocation process without intruding on business logic. Supports Micrometer metrics, distributed tracing, logging, and more.

```java
// DefaultChatClient.java:464-474 — Wraps the entire advisor chain call
var observation = ChatClientObservationDocumentation.AI_CHAT_CLIENT
    .observation(this.observationConvention, ..., this.observationRegistry);

var chatClientResponse = observation.observe(() -> {
    // ← The "action" being observed
    return this.advisorChain.nextCall(chatClientRequest);
});
// observation automatically records: start time, end time, exceptions, tags, etc.
```

The same pattern exists inside `AnthropicChatModel.internalCall()` and also inside `DefaultToolCallingManager.executeToolCall()`. Three nested Observation layers:

```
ChatClient Observation          ← Layer 1: entire call() process
  └─ ChatModel Observation      ← Layer 2: single HTTP request
       └─ ToolCall Observation   ← Layer 3: individual tool execution
```

**Design rationale**: A hybrid of Observer pattern and Decorator. `observation.observe(supplier)` essentially adds `onStart()` / `onStop()` / `onError()` hooks before and after the supplier.

### Layer 5: Recursive + Callback — Recursion + Callback

**Problem solved**: The AI may call tools across multiple consecutive rounds, requiring a loop mechanism.

```java
// AnthropicChatModel.java:206-220 — Recursive loop
if (hasToolUse) {
    var result = toolCallingManager.executeToolCalls(prompt, response);
    return this.internalCall(        // ← Recursion! Calls itself
        new Prompt(result.conversationHistory(), prompt.getOptions()),
        response
    );
}
return response;  // No tool_use → exit
```

Tool execution callback mechanism:

```java
// DefaultToolCallingManager.java:186-243
for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
    // Find your registered ToolCallback by tool name
    ToolCallback toolCallback = toolCallbacks.stream()
        .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
        .findFirst()...;

    // Callback: invoke the @Tool-annotated method via reflection
    String result = toolCallback.call(arguments, toolContext);
}
```

**Design rationale**:
- **Recursion** replaces the while loop, naturally suited for passing accumulated state (token usage)
- **Callback pattern** (`ToolCallback`) decouples "what tool the AI wants to call" from "how the tool executes"

### Summary Comparison Table

| Pattern | Where | Problem Solved |
|---|---|---|
| **Builder** | `ChatClient.builder()` | Step-by-step construction of complex objects |
| **Fluent API** | `.prompt().user().call()` | Readability and type safety of the invocation interface |
| **Chain of Responsibility** | Advisor Chain | Pluggable cross-cutting concerns (logging, monitoring) |
| **Strategy** | `ChatModel` interface | Transparent switching between AI providers |
| **Observer** | Micrometer Observation | Non-intrusive observability instrumentation |
| **Recursive + Callback** | `internalCall()` + `ToolCallback` | Decoupling of tool loop and tool execution |
