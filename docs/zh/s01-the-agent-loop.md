# s01: The Agent Loop (智能体循环)

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"One loop & Bash is all you need"* -- 一个工具 + 一个循环 = 一个智能体。
>
> **Harness 层**: 循环 -- 模型与真实世界的第一道连接。

## 问题

语言模型能推理代码, 但碰不到真实世界 -- 不能读文件、跑测试、看报错。没有循环, 每次工具调用你都得手动把结果粘回去。你自己就是那个循环。

## 解决方案

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> |  Tool   |
| prompt |      |       |      | execute |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (ChatClient.call() 自动循环直到无工具调用)
```

一个 `call()` 调用控制整个流程。Spring AI 自动循环, 直到模型不再调用工具。

## 工作原理

### 1. 构建 ChatClient：注入模型 + 注册工具

通过 Spring Boot 自动配置注入 `ChatModel`，用 `ChatClient.builder()` 构建客户端，设置系统提示和工具。

```java
// TIP: Python 版在模块级创建 client = Anthropic() 和 MODEL。
// Spring AI 通过自动配置注入 ChatModel，再用 builder 构建 ChatClient。
public S01AgentLoop(ChatModel chatModel) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                    + ". Use bash to solve tasks. Act, don't explain.")
            .defaultTools(new BashTool())   // @Tool 注解的工具对象
            .build();
}
```

### 2. `@Tool` 注解：声明式工具注册

Spring AI 通过 `@Tool` 注解自动发现和注册工具。框架在启动时扫描 `defaultTools()` 传入的对象，提取所有 `@Tool` 方法的签名和描述，生成 LLM 需要的 tool schema（名称、参数、描述），然后在每次 `call()` 请求中自动携带。

```java
// BashTool —— 对应 Python 版的 run_bash() 函数
public class BashTool {
    @Tool(description = "Run a shell command and return stdout + stderr")
    public String bash(@ToolParam(description = "The shell command to execute")
                       String command) {
        // 危险命令检查 + ProcessBuilder 执行 + 超时控制 + 输出截断
        // ...
    }
}
```

> 对比 Python 版的手动注册方式：
> - Python: `TOOLS = [{"name": "bash", "input_schema": {...}}]` + `TOOL_HANDLERS = {"bash": run_bash}`
> - Java: 只需 `@Tool` + `@ToolParam` 注解，框架自动完成 schema 生成和方法分派

### 3. Spring AI 内部自动循环：`call()` 的底层实现

**这是理解 Java 版与 Python 版最关键的区别。** Python 版本需要手写 while 循环来驱动工具调用：

```python
# Python 版 —— 手动循环
def agent_loop(messages):
    while True:
        response = client.messages.create(model=MODEL, messages=messages, tools=TOOLS)
        # 收集 assistant 消息
        messages.append({"role": "assistant", "content": response.content})
        if response.stop_reason != "tool_use":
            return response           # 模型不再调用工具，退出循环
        # 执行工具并回传结果
        for block in response.content:
            if block.type == "tool_use":
                result = TOOL_HANDLERS[block.name](block.input)
                messages.append({"role": "user", "content": [{"type": "tool_result", ...}]})
```

Spring AI 的 `ChatClient.call()` **内部封装了完全等价的逻辑**：

```
call() 内部流程:
  ┌─────────────────────────────────────────────────────┐
  │  1. 组装请求: system prompt + user message + tools  │
  │  2. 发送给 LLM                                     │
  │  3. 解析响应                                        │
  │     ├── 有 tool_use? ──→ 是:                       │
  │     │   a. 提取工具名和参数                         │
  │     │   b. 通过反射调用对应的 @Tool 方法            │
  │     │   c. 将 tool_result 追加到消息列表            │
  │     │   d. 回到步骤 2（自动循环）                   │
  │     └── 否 ──→ 返回最终文本                        │
  └─────────────────────────────────────────────────────┘
```

关键点：
- **工具检测**: Spring AI 检查响应中是否有 `tool_use` 类型的 content block（对应 Python 的 `stop_reason == "tool_use"`）
- **反射分派**: 框架通过 Java 反射机制，根据 LLM 返回的工具名称找到对应的 `@Tool` 方法并调用（对应 Python 的 `TOOL_HANDLERS[block.name]`）
- **结果回传**: 工具执行结果自动包装为 `tool_result` 消息追加到对话（对应 Python 手动构造 `tool_result` content block）
- **循环终止**: 当模型返回纯文本（无工具调用）时，`call()` 返回最终结果

因此，Python 版约 15 行的 while 循环，在 Java 版中浓缩为一行 `.call()`。

### 4. `AgentRunner.interactive()`：REPL 交互循环

`AgentRunner` 是所有课程共用的交互式 REPL（Read-Eval-Print Loop）工具类，对应 Python 版 `if __name__ == "__main__"` 中的 `input()` 循环。

```java
public class AgentRunner {
    /**
     * 启动交互式 REPL 循环。
     * @param prefix  提示符前缀（如 "s01"）
     * @param handler 处理用户输入并返回 Agent 响应的函数
     */
    public static void interactive(String prefix, Function<String, String> handler) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("输入 'q' 或 'exit' 退出");
        while (true) {
            System.out.print("\033[36m" + prefix + " >> \033[0m");  // 彩色提示符
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
                String response = handler.apply(input);  // 调用 Agent 处理
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

工作流程：`Scanner` 读取输入 → `handler.apply()` 发给 Agent → 打印响应 → 循环。`handler` 是一个函数式接口，每个课程传入自己的 Agent 调用逻辑。

### 5. 组装为完整的 Agent 类

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
                        .call()       // ← 这一个调用 = Python 的整个 while 循环
                        .content()
        );
    }
}
```

> **TIPS — Python → Java 关键适配点:**
> - Python 的 `while True` + `stop_reason` 手动循环 → Spring AI `ChatClient.call()` 内置自动循环
> - Python 的 `TOOLS` 数组 + `TOOL_HANDLERS` 字典 → `@Tool` 注解 + `defaultTools()` 自动注册与反射分派
> - Python 的 `client = Anthropic()` → Spring Boot 自动配置注入 `ChatModel`
> - Python 的 `input()` 交互 → `AgentRunner.interactive()` 封装 Scanner REPL + 函数式接口

不到 40 行核心代码, 这就是整个智能体。后面 11 个章节都在这个循环上叠加机制 -- 循环本身始终不变。

## 源码追踪：`call()` 内部循环到底怎么实现的

上面的架构图说"Spring AI 自动循环"，但具体在哪行代码？以下是 Spring AI 1.0.3 源码的完整调用链追踪。

### 调用链总览

```
你的代码: chatClient.prompt().user(msg).call().chatResponse()
    │
    ▼
① DefaultChatClient.DefaultCallResponseSpec.chatResponse()
    │  (DefaultChatClient.java:435-437)
    │  内部调用 doGetObservableChatClientResponse() → advisorChain.nextCall()
    │
    ▼
② Advisor 链（含你注册的 ToolCallLoggingAdvisor）
    │  逐个 advisor 执行，最后一个节点是 ChatModelCallAdvisor
    │
    ▼
③ ChatModelCallAdvisor.adviseCall()
    │  (ChatModelCallAdvisor.java:49-58)
    │  核心就一行: this.chatModel.call(prompt)
    │
    ▼
④ AnthropicChatModel.call() → internalCall()
    │  (AnthropicChatModel.java:169-223)
    │  ★ 这里就是循环！用递归实现，不是 while ★
```

### 关键源码：递归循环在 `internalCall`

`AnthropicChatModel.java` 第 176-223 行（Spring AI 1.0.3）：

```java
// 入口
public ChatResponse call(Prompt prompt) {
    Prompt requestPrompt = buildRequestPrompt(prompt);
    return this.internalCall(requestPrompt, null);
}

// ★ 循环核心：递归调用自身
public ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

    // ──── 第 1 步：发 HTTP 请求给 Anthropic API ────
    ChatCompletionRequest request = createRequest(prompt, false);

    ChatResponse response = ... // anthropicApi.chatCompletionEntity(request, headers)
    // 这里是一次真实的 HTTP 请求

    // ──── 第 2 步：检查响应里有没有 tool_use ────
    if (this.toolExecutionEligibilityPredicate
            .isToolExecutionRequired(prompt.getOptions(), response)) {

        // ──── 第 3 步：有 tool_use → 执行工具（你的 BashTool）────
        var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);

        if (toolExecutionResult.returnDirect()) {
            return ...;  // 工具要求直接返回结果
        } else {
            // ──── 第 4 步：递归！把工具结果加入历史，再调自己 ────
            return this.internalCall(
                new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
                response  // 传递上一次响应（用于累计 token 用量）
            );
            // ↑ 这里是递归调用，会再次发 HTTP 请求给 AI
        }
    }

    // ──── 第 5 步：没有 tool_use → AI 回答完毕，返回最终结果 ────
    return response;
}
```

### 对应 Python 版本的逐行映射

| Python 手动循环 | Spring AI 自动实现 |
|---|---|
| `while True:` | `internalCall()` 递归调用自身 |
| `response = client.messages.create(...)` | `anthropicApi.chatCompletionEntity(request, ...)` |
| `if response.stop_reason != "tool_use": return` | `isToolExecutionRequired()` 返回 false → 直接 `return response` |
| `TOOL_HANDLERS[block.name](block.input)` | `toolCallingManager.executeToolCalls()` → 反射调用 `@Tool` 方法 |
| `messages.append({"role": "user", "content": [tool_result]})` | `buildConversationHistoryAfterToolExecution()` 自动构建 |

### 工具执行细节：`DefaultToolCallingManager`

当 `internalCall` 检测到 `tool_use`，调用 `toolCallingManager.executeToolCalls()`。这个方法（`DefaultToolCallingManager.java:121-148`）做了这些事：

```java
public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    // 1. 从 AI 响应中提取 tool_call 信息
    AssistantMessage assistantMessage = ...;
    
    // 2. 对每个 tool_call，找到对应的 @Tool 方法并执行
    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
        // 按 tool 名称找到 ToolCallback（你的 BashTool）
        ToolCallback toolCallback = toolCallbacks.stream()
            .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
            .findFirst()...;
        
        // 通过反射调用 @Tool 方法，拿到结果字符串
        String toolCallResult = toolCallback.call(finalToolInputArguments, toolContext);
        
        // 包装为 ToolResponseMessage
        toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolCallResult));
    }
    
    // 3. 构建新的对话历史 = 原始消息 + assistant(tool_use) + tool_result
    List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(
        prompt.getInstructions(), assistantMessage, toolResponseMessage);
    
    return ToolExecutionResult.builder()
        .conversationHistory(conversationHistory)
        .build();
}
```

### 完整流程示例

用户说 `"创建一个文件 hello.txt"`：

```
internalCall() 第 1 轮:
  HTTP → Anthropic: "创建一个文件 hello.txt" + tools=[bash]
  HTTP ← Anthropic: tool_use(bash, "touch hello.txt")
  executeToolCalls() → BashTool.bash("touch hello.txt") → ""
  递归调用 internalCall(history + [assistant(tool_use), tool_result("")])

internalCall() 第 2 轮:
  HTTP → Anthropic: 完整历史 + "刚才工具返回了空字符串"
  HTTP ← Anthropic: tool_use(bash, "echo hello > hello.txt")
  executeToolCalls() → BashTool.bash("echo hello > hello.txt") → ""
  递归调用 internalCall(history + [assistant(tool_use), tool_result("")])

internalCall() 第 3 轮:
  HTTP → Anthropic: 完整历史 + "刚才工具返回了空字符串"
  HTTP ← Anthropic: "已经为您创建了 hello.txt 并写入了内容"
  isToolExecutionRequired() → false（纯文本，无 tool_use）
  return response  ← 退出递归，返回最终结果
```

所以一次 `chatClient.prompt().call()` 可能触发 **N 次 HTTP 请求**（N = AI 调用工具的轮数 + 1 次最终回答）。

> **注意**: 循环不是用 `while` 实现的，而是用**递归**。`internalCall` 在检测到 tool_use 后调用自身，把工具结果作为新的对话历史传进去。这种方式比 while 更适合累积 token 用量统计（通过 `previousChatResponse` 参数层层传递）。

## 变更内容

| 组件          | 之前       | 之后                                             |
|---------------|------------|--------------------------------------------------|
| Agent loop    | (无)       | `ChatClient.call()` 内置工具循环                 |
| Tools         | (无)       | `BashTool` (单一 `@Tool` 工具)                   |
| Messages      | (无)       | Spring AI 内部管理消息列表                       |
| Control flow  | (无)       | 框架自动判断: 无工具调用时返回最终文本           |

```java
// 核心代码 —— 构建 + 调用
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(new BashTool())
        .build();

AgentRunner.interactive("s01", userMessage ->
        chatClient.prompt().user(userMessage).call().content()
);
```

## 试一试

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s01.S01AgentLoop
```

> 运行前需设置环境变量: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`
>
> **当前默认使用 OpenAI 协议**（兼容所有 OpenAI API 格式的服务，包括 OpenAI 官方、Azure OpenAI、各类第三方大模型服务的 OpenAI 兼容接口等）。
> 如需使用 Anthropic 协议（Claude 系列模型原生接口），请展开下方「切换 AI 协议」。

<details>
<summary><strong>切换 AI 协议（OpenAI ↔ Anthropic）</strong></summary>

本项目通过 Spring AI 的 **Starter 依赖 + 配置文件** 来切换底层协议，Java 业务代码（`ChatModel`、`ChatClient`）**无需任何修改**。

#### 方式一：OpenAI 协议（默认）

`pom.xml` 依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

`application.yml` 配置：

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

环境变量示例（以 OpenAI 官方为例）：

```sh
export AI_API_KEY=sk-proj-xxxxxxxx
export AI_BASE_URL=https://api.openai.com   # 可替换为任何 OpenAI 兼容接口
export AI_MODEL=gpt-4o
```

> **TIP**: 许多第三方大模型服务（如 DeepSeek、Mistral、通义千问等）提供了 OpenAI 兼容接口，只需修改 `AI_BASE_URL` 和 `AI_MODEL` 即可接入，无需切换协议。

#### 方式二：Anthropic 协议（Claude 原生接口）

**第 1 步**：修改 `pom.xml`，将 OpenAI starter 替换为 Anthropic starter：

```xml
<!-- 注释或删除 OpenAI starter -->
<!-- <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency> -->

<!-- 添加 Anthropic starter -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

**第 2 步**：修改 `application.yml`，将 `spring.ai.openai` 替换为 `spring.ai.anthropic`：

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

**第 3 步**：设置环境变量：

```sh
export AI_API_KEY=sk-ant-xxxxxxxx
export AI_BASE_URL=https://api.anthropic.com
export AI_MODEL=claude-sonnet-4-20250514
```

#### 切换原理

Spring AI 的设计使得 `ChatModel` 是一个统一的抽象接口。不同的 Starter 提供不同的实现：

| Starter 依赖 | 自动注入的 ChatModel 实现 | 配置前缀 |
|---|---|---|
| `spring-ai-starter-model-openai` | `OpenAiChatModel` | `spring.ai.openai.*` |
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` | `spring.ai.anthropic.*` |

业务代码始终面向 `ChatModel` 接口编程，切换协议只需替换依赖和配置，无需改动任何 Java 代码。

</details>

试试这些 prompt(英文 prompt 对 LLM 效果更好, 也可以用中文):

1. `Create a file called Hello.java that prints "Hello, World!"`
2. `List all Java files in this directory`
3. `What is the current git branch?`
4. `Create a directory called test_output and write 3 files in it`

## 设计理念：Spring AI 的 6 层设计模式

Spring AI 的封装层次很多——Builder、Advisor Chain、Observation、Strategy、递归、Callback。以下从你的代码发起调用到 HTTP 请求完成，逐层拆解每个设计模式。

### 全景图：一个 `.call()` 经过的 6 层

```
你的代码: chatClient.prompt().user(msg).call().chatResponse()
          │
    ┌─────┴─────┐
    │  Layer 1  │  Builder + Fluent API     ← 构建和组装
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 2  │  Advisor Chain (责任链)   ← 拦截和增强
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 3  │  Strategy (策略模式)      ← 多模型切换
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 4  │  Observation (观察者)     ← 可观测性
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 5  │  Recursion + Callback     ← 工具循环
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 6  │  HTTP Request             ← 最终落地
    └───────────┘
```

一句话总结：**Builder 构建对象，Fluent API 引导调用，Advisor Chain 拦截增强，Strategy 切换实现，Observation 观察一切，Recursion 驱动循环。**

### Layer 1: Builder 模式 + Fluent API（流畅接口）

**解决问题**：ChatClient 配置项很多（system prompt、tools、advisors、options...），不用 Builder 会变成一个 10 参数的构造函数。

```java
// Builder 模式：分步构建复杂对象
ChatClient chatClient = ChatClient.builder(chatModel)    // ① 创建 Builder
        .defaultSystem("...")                             // ② 逐步设参
        .defaultTools(new BashTool())
        .defaultAdvisors(new ToolCallLoggingAdvisor())
        .build();                                         // ③ 构建最终对象

// Fluent API（流畅接口）：链式调用，每次返回 this
chatClient.prompt()     // → ChatClientRequestSpec
    .user(msg)          // → ChatClientRequestSpec（还是自己）
    .call()             // → CallResponseSpec（换了一个对象）
    .chatResponse();    // → ChatResponse
```

**设计思路**：Builder 负责"构建阶段"（不可变配置），Fluent API 负责"使用阶段"（每次请求的参数）。两个阶段返回不同类型的对象，编译器就能防止你在构建阶段调用 `.user()`。

源码体现：
- `DefaultChatClientBuilder.java` — 构建阶段
- `DefaultChatClientRequestSpec`（`DefaultChatClient.java:564`）— 使用阶段
- `DefaultCallResponseSpec`（`DefaultChatClient.java:341`）— 响应阶段

### Layer 2: Advisor Chain — 责任链模式（Chain of Responsibility）

**解决问题**：在不修改核心调用逻辑的前提下，插入横切关注点（日志、缓存、权限、工具调用记录）。

```
请求 → ToolCallLoggingAdvisor → ChatModelCallAdvisor → HTTP
              ↓                        ↓
          打印工具调用              调 chatModel.call()
```

```java
// DefaultChatClient.java:921-931 — 构建链
private BaseAdvisorChain buildAdvisorChain() {
    // 用户注册的 advisor
    this.advisors.add(new ToolCallLoggingAdvisor());

    // 链的末尾：真正调 AI 的 advisor（框架自动加的终结者）
    this.advisors.add(ChatModelCallAdvisor.builder()
            .chatModel(this.chatModel).build());

    return DefaultAroundAdvisorChain.builder(observationRegistry)
            .pushAll(this.advisors)
            .build();
}
```

每个 Advisor 的接口（类比 Servlet Filter）：

```java
// CallAdvisor.java — 类比 Filter 接口
public interface CallAdvisor {
    ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain);
    //                                                请求              链（调下一个）
}

// ToolCallLoggingAdvisor — 类比 Filter.doFilter()
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);  // 先往后传
    // 然后从 response 中提取工具调用信息打印
    ... print toolCalls ...
    return response;
}
```

**类比**：和 Servlet Filter、Spring HandlerInterceptor、Express Middleware 完全同构。`chain.next()` = `filterChain.doFilter()`。

### Layer 3: Strategy 模式（策略模式）

**解决问题**：你的代码只面向 `ChatModel` 接口编程，运行时可以是 Anthropic、OpenAI、任何实现。

```java
// ChatModel 接口 — 策略的抽象
public interface ChatModel {
    ChatResponse call(Prompt prompt);
}

// AnthropicChatModel — 策略实现 A
// OpenAiChatModel   — 策略实现 B
```

你的代码从来不直接出现 `AnthropicChatModel`：

```java
// S01AgentLoop.java:52 — 注入的是接口，不是实现
public S01AgentLoop(AiConfig aiConfig) {
    this.chatClient = ChatClient.builder(aiConfig.get())  // aiConfig.get() 返回 ChatModel
            ...
```

Spring Boot 自动配置根据 classpath 上的 starter 决定注入哪个实现：

| classpath 上有 | 注入的实现 |
|---|---|
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` |
| `spring-ai-starter-model-openai` | `OpenAiChatModel` |

### Layer 4: Observation 模式（观察者模式 / 可观测性）

**解决问题**：给整个调用过程埋点，不侵入业务逻辑。支持 Micrometer 指标、分布式追踪、日志等。

```java
// DefaultChatClient.java:464-474 — 包裹整个 advisor chain 调用
var observation = ChatClientObservationDocumentation.AI_CHAT_CLIENT
    .observation(this.observationConvention, ..., this.observationRegistry);

var chatClientResponse = observation.observe(() -> {
    // ← 被观察的"动作"
    return this.advisorChain.nextCall(chatClientRequest);
});
// observation 自动记录: 开始时间、结束时间、异常、标签等
```

同样的模式在 `AnthropicChatModel.internalCall()` 里也有一层，在 `DefaultToolCallingManager.executeToolCall()` 里还有一层。三层 Observation 嵌套：

```
ChatClient Observation          ← 第一层：整个 call() 过程
  └─ ChatModel Observation      ← 第二层：单次 HTTP 请求
       └─ ToolCall Observation   ← 第三层：单个工具执行
```

**设计思路**：观察者模式 + 装饰器的混合体。`observation.observe(supplier)` 本质上是在 supplier 前后加了 `onStart()` / `onStop()` / `onError()` 的钩子。

### Layer 5: Recursive + Callback — 递归 + 回调

**解决问题**：AI 可能连续多轮调用工具，需要一个循环机制。

```java
// AnthropicChatModel.java:206-220 — 递归循环
if (hasToolUse) {
    var result = toolCallingManager.executeToolCalls(prompt, response);
    return this.internalCall(        // ← 递归！自己调自己
        new Prompt(result.conversationHistory(), prompt.getOptions()),
        response
    );
}
return response;  // 无 tool_use → 退出
```

工具执行的回调机制：

```java
// DefaultToolCallingManager.java:186-243
for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
    // 按 tool 名称找到你注册的 ToolCallback
    ToolCallback toolCallback = toolCallbacks.stream()
        .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
        .findFirst()...;

    // 回调：通过反射调用 @Tool 注解的方法
    String result = toolCallback.call(arguments, toolContext);
}
```

**设计思路**：
- **递归**替代 while 循环，天然适合传递累积状态（token 用量）
- **Callback 模式**（`ToolCallback`）解耦了"AI 要调什么工具"和"工具怎么执行"

### 总结对照表

| 模式 | 在哪 | 解决什么问题 |
|---|---|---|
| **Builder** | `ChatClient.builder()` | 复杂对象的分步构建 |
| **Fluent API** | `.prompt().user().call()` | 调用接口的可读性和类型安全 |
| **Chain of Responsibility** | Advisor Chain | 横切关注点的可插拔（日志、监控） |
| **Strategy** | `ChatModel` 接口 | 多 AI 供应商的透明切换 |
| **Observer** | Micrometer Observation | 无侵入的可观测性埋点 |
| **Recursive + Callback** | `internalCall()` + `ToolCallback` | 工具循环和工具执行的解耦 |
