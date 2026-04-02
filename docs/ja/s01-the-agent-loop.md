# s01: The Agent Loop (エージェントループ)

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"One loop & Bash is all you need"* -- 1つのツール + 1つのループ = エージェント。
>
> **Harness 層**: ループ -- モデルと現実世界を繋ぐ最初の接点。

## 問題

言語モデルはコードについて推論できるが、現実世界に触れられない -- ファイルを読めず、テストを実行できず、エラーを確認できない。ループがなければ、ツール呼び出しのたびに手動で結果を貼り戻す必要がある。あなた自身がそのループになる。

## 解決策

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> |  Tool   |
| prompt |      |       |      | execute |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (ChatClient.call() がツール呼び出しがなくなるまで自動ループ)
```

1つの `call()` 呼び出しがフロー全体を制御する。Spring AI が自動的にループし、モデルがツール呼び出しを止めるまで続ける。

## 仕組み

### 1. ChatClient の構築：モデル注入 + ツール登録

Spring Boot の自動設定で `ChatModel` を注入し、`ChatClient.builder()` でクライアントを構築、システムプロンプトとツールを設定する。

```java
// TIP: Python 版ではモジュールレベルで client = Anthropic() と MODEL を作成。
// Spring AI は自動設定で ChatModel を注入し、builder で ChatClient を構築する。
public S01AgentLoop(ChatModel chatModel) {
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem("You are a coding agent at " + System.getProperty("user.dir")
                    + ". Use bash to solve tasks. Act, don't explain.")
            .defaultTools(new BashTool())   // @Tool アノテーション付きツールオブジェクト
            .build();
}
```

### 2. `@Tool` アノテーション：宣言的ツール登録

Spring AI は `@Tool` アノテーションでツールを自動的に検出・登録する。起動時にフレームワークが `defaultTools()` に渡されたオブジェクトをスキャンし、すべての `@Tool` メソッドのシグネチャと説明を抽出し、LLM が必要とするツールスキーマ（名前、パラメータ、説明）を生成して、毎回の `call()` リクエストに自動的に含める。

```java
// BashTool -- Python 版の run_bash() 関数に相当
public class BashTool {
    @Tool(description = "Run a shell command and return stdout + stderr")
    public String bash(@ToolParam(description = "The shell command to execute")
                       String command) {
        // 危険コマンドチェック + ProcessBuilder 実行 + タイムアウト制御 + 出力切り詰め
        // ...
    }
}
```

> Python の手動登録方式との比較：
> - Python: `TOOLS = [{"name": "bash", "input_schema": {...}}]` + `TOOL_HANDLERS = {"bash": run_bash}`
> - Java: `@Tool` + `@ToolParam` アノテーションだけで、フレームワークがスキーマ生成とメソッドディスパッチを自動化

### 3. Spring AI 内部自動ループ：`call()` の内部実装

**これが Java 版と Python 版の最も重要な違いだ。** Python 版ではツール呼び出しを駆動するために手書きの while ループが必要：

```python
# Python 版 -- 手動ループ
def agent_loop(messages):
    while True:
        response = client.messages.create(model=MODEL, messages=messages, tools=TOOLS)
        # assistant メッセージを収集
        messages.append({"role": "assistant", "content": response.content})
        if response.stop_reason != "tool_use":
            return response           # モデルがツールを呼ばなくなった、ループ終了
        # ツールを実行して結果を返送
        for block in response.content:
            if block.type == "tool_use":
                result = TOOL_HANDLERS[block.name](block.input)
                messages.append({"role": "user", "content": [{"type": "tool_result", ...}]})
```

Spring AI の `ChatClient.call()` は**完全に等価なロジックを内部にカプセル化**している：

```
call() 内部フロー:
  ┌─────────────────────────────────────────────────────┐
  │  1. リクエスト組み立て: system prompt + user msg + tools │
  │  2. LLM に送信                                      │
  │  3. レスポンス解析                                   │
  │     ├── tool_use あり? ──→ はい:                    │
  │     │   a. ツール名と引数を抽出                      │
  │     │   b. リフレクションで対応する @Tool メソッドを呼出 │
  │     │   c. tool_result をメッセージリストに追加       │
  │     │   d. ステップ 2 に戻る（自動ループ）           │
  │     └── いいえ ──→ 最終テキストを返す               │
  └─────────────────────────────────────────────────────┘
```

キーポイント：
- **ツール検出**: Spring AI はレスポンスに `tool_use` タイプのコンテンツブロックがあるかチェック（Python の `stop_reason == "tool_use"` に相当）
- **リフレクションディスパッチ**: フレームワークが Java リフレクションで、LLM が返したツール名に対応する `@Tool` メソッドを見つけて呼び出す（Python の `TOOL_HANDLERS[block.name]` に相当）
- **結果返送**: ツール実行結果は自動的に `tool_result` メッセージとして会話に追加（Python が手動で `tool_result` コンテンツブロックを構築するのに相当）
- **ループ終了**: モデルが純粋なテキスト（ツール呼び出しなし）を返すと、`call()` が最終結果を返す

従って、Python 版の約15行の while ループは、Java 版では1行の `.call()` に凝縮される。

### 4. `AgentRunner.interactive()`：REPL インタラクションループ

`AgentRunner` は全レッスン共通の REPL（Read-Eval-Print Loop）ユーティリティクラスで、Python の `if __name__ == "__main__"` 内の `input()` ループに相当する。

```java
public class AgentRunner {
    /**
     * インタラクティブ REPL ループを開始。
     * @param prefix  プロンプトプレフィックス（例: "s01"）
     * @param handler ユーザー入力を処理し Agent レスポンスを返す関数
     */
    public static void interactive(String prefix, Function<String, String> handler) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("'q' または 'exit' で終了");
        while (true) {
            System.out.print("\033[36m" + prefix + " >> \033[0m");  // カラープロンプト
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
                String response = handler.apply(input);  // Agent ハンドラーを呼び出し
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

ワークフロー：`Scanner` で入力読み取り → `handler.apply()` で Agent に送信 → レスポンス出力 → ループ。`handler` は関数型インターフェースで、各レッスンが自分の Agent 呼び出しロジックを渡す。

### 5. 完全な Agent クラスとして組み立て

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
                        .call()       // ← この1つの呼び出し = Python の while ループ全体
                        .content()
        );
    }
}
```

> **TIPS — Python → Java 主要な適応ポイント:**
> - Python の `while True` + `stop_reason` 手動ループ → Spring AI `ChatClient.call()` 内蔵自動ループ
> - Python の `TOOLS` 配列 + `TOOL_HANDLERS` 辞書 → `@Tool` アノテーション + `defaultTools()` 自動登録とリフレクションディスパッチ
> - Python の `client = Anthropic()` → Spring Boot 自動設定で `ChatModel` を注入
> - Python の `input()` インタラクション → `AgentRunner.interactive()` が Scanner REPL + 関数型インターフェースをカプセル化

コアコード40行未満、これがエージェント全体だ。残り11章はすべてこのループの上にメカニズムを積み重ねる -- ループ自体は決して変わらない。

## ソースコード追跡：`call()` 内部ループの具体的な実装

上のアーキテクチャ図では「Spring AI が自動ループ」と説明したが、具体的にどの行のコードで実装されているのか？以下は Spring AI 1.0.3 ソースコードの完全な呼び出しチェーンの追跡である。

### 呼び出しチェーン全体像

```
あなたのコード: chatClient.prompt().user(msg).call().chatResponse()
    │
    ▼
① DefaultChatClient.DefaultCallResponseSpec.chatResponse()
    │  (DefaultChatClient.java:435-437)
    │  内部で doGetObservableChatClientResponse() → advisorChain.nextCall() を呼び出し
    │
    ▼
② Advisor チェーン（登録した ToolCallLoggingAdvisor を含む）
    │  各 advisor を順次実行、最後のノードは ChatModelCallAdvisor
    │
    ▼
③ ChatModelCallAdvisor.adviseCall()
    │  (ChatModelCallAdvisor.java:49-58)
    │  実質1行: this.chatModel.call(prompt)
    │
    ▼
④ AnthropicChatModel.call() → internalCall()
    │  (AnthropicChatModel.java:169-223)
    │  ★ ここがループ！while ではなく再帰で実装されている ★
```

### 重要ソースコード：`internalCall` の再帰ループ

`AnthropicChatModel.java` 第 176-223 行（Spring AI 1.0.3）：

```java
// エントリポイント
public ChatResponse call(Prompt prompt) {
    Prompt requestPrompt = buildRequestPrompt(prompt);
    return this.internalCall(requestPrompt, null);
}

// ★ ループの核心：自身を再帰呼び出し
public ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

    // ──── ステップ 1：Anthropic API に HTTP リクエストを送信 ────
    ChatCompletionRequest request = createRequest(prompt, false);

    ChatResponse response = ... // anthropicApi.chatCompletionEntity(request, headers)
    // ここで実際の HTTP リクエストが発生

    // ──── ステップ 2：レスポンスに tool_use があるかチェック ────
    if (this.toolExecutionEligibilityPredicate
            .isToolExecutionRequired(prompt.getOptions(), response)) {

        // ──── ステップ 3：tool_use あり → ツールを実行（あなたの BashTool）────
        var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);

        if (toolExecutionResult.returnDirect()) {
            return ...;  // ツールが直接結果を返すことを要求
        } else {
            // ──── ステップ 4：再帰！ツール結果を履歴に追加し、再度自分自身を呼び出し ────
            return this.internalCall(
                new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
                response  // 前回のレスポンスを渡す（累積 token 使用量の集計用）
            );
            // ↑ ここが再帰呼び出し。再度 AI に HTTP リクエストを送信する
        }
    }

    // ──── ステップ 5：tool_use なし → AI の回答完了、最終結果を返す ────
    return response;
}
```

### Python 版との行単位の対応表

| Python 手動ループ | Spring AI 自動実装 |
|---|---|
| `while True:` | `internalCall()` が自身を再帰呼び出し |
| `response = client.messages.create(...)` | `anthropicApi.chatCompletionEntity(request, ...)` |
| `if response.stop_reason != "tool_use": return` | `isToolExecutionRequired()` が false を返す → そのまま `return response` |
| `TOOL_HANDLERS[block.name](block.input)` | `toolCallingManager.executeToolCalls()` → リフレクションで `@Tool` メソッドを呼び出し |
| `messages.append({"role": "user", "content": [tool_result]})` | `buildConversationHistoryAfterToolExecution()` が自動構築 |

### ツール実行の詳細：`DefaultToolCallingManager`

`internalCall` が `tool_use` を検出すると、`toolCallingManager.executeToolCalls()` を呼び出す。このメソッド（`DefaultToolCallingManager.java:121-148`）が行う処理：

```java
public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
    // 1. AI レスポンスから tool_call 情報を抽出
    AssistantMessage assistantMessage = ...;

    // 2. 各 tool_call について、対応する @Tool メソッドを見つけて実行
    for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
        // ツール名で ToolCallback（あなたの BashTool）を検索
        ToolCallback toolCallback = toolCallbacks.stream()
            .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
            .findFirst()...;

        // リフレクションで @Tool メソッドを呼び出し、結果文字列を取得
        String toolCallResult = toolCallback.call(finalToolInputArguments, toolContext);

        // ToolResponseMessage にラップ
        toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolCallResult));
    }

    // 3. 新しい会話履歴を構築 = 元のメッセージ + assistant(tool_use) + tool_result
    List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(
        prompt.getInstructions(), assistantMessage, toolResponseMessage);

    return ToolExecutionResult.builder()
        .conversationHistory(conversationHistory)
        .build();
}
```

### 完全なフロー例

ユーザーが `"hello.txt というファイルを作成して"` と入力した場合：

```
internalCall() 第 1 ラウンド:
  HTTP → Anthropic: "hello.txt というファイルを作成して" + tools=[bash]
  HTTP ← Anthropic: tool_use(bash, "touch hello.txt")
  executeToolCalls() → BashTool.bash("touch hello.txt") → ""
  再帰呼び出し internalCall(history + [assistant(tool_use), tool_result("")])

internalCall() 第 2 ラウンド:
  HTTP → Anthropic: 完全な履歴 + "先ほどツールは空文字列を返しました"
  HTTP ← Anthropic: tool_use(bash, "echo hello > hello.txt")
  executeToolCalls() → BashTool.bash("echo hello > hello.txt") → ""
  再帰呼び出し internalCall(history + [assistant(tool_use), tool_result("")])

internalCall() 第 3 ラウンド:
  HTTP → Anthropic: 完全な履歴 + "先ほどツールは空文字列を返しました"
  HTTP ← Anthropic: "hello.txt を作成し、内容を書き込みました"
  isToolExecutionRequired() → false（純テキスト、tool_use なし）
  return response  ← 再帰から抜け、最終結果を返す
```

つまり、1回の `chatClient.prompt().call()` で **N 回の HTTP リクエスト**が発生する可能性がある（N = AI がツールを呼び出すラウンド数 + 最終回答の 1 回）。

> **注意**: ループは `while` ではなく**再帰**で実装されている。`internalCall` は `tool_use` を検出すると自身を呼び出し、ツール結果を新しい会話履歴として渡す。この手法は while よりも累積 token 使用量の統計に適している（`previousChatResponse` パラメータで階層的に渡される）。

## 変更点

| コンポーネント  | 変更前     | 変更後                                           |
|---------------|------------|--------------------------------------------------|
| Agent loop    | (なし)     | `ChatClient.call()` 内蔵ツールループ             |
| Tools         | (なし)     | `BashTool` (単一の `@Tool` ツール)               |
| Messages      | (なし)     | Spring AI が内部でメッセージリストを管理          |
| Control flow  | (なし)     | フレームワークが自動判定: ツール呼び出しなしで最終テキストを返す |

```java
// コアコード -- 構築 + 呼び出し
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("You are a coding agent ...")
        .defaultTools(new BashTool())
        .build();

AgentRunner.interactive("s01", userMessage ->
        chatClient.prompt().user(userMessage).call().content()
);
```

## 試してみる

```sh
cd learn-claude-code
mvn exec:java -Dexec.mainClass=io.mybatis.learn.s01.S01AgentLoop
```

> 実行前に環境変数の設定が必要: `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`
>
> **デフォルトプロトコルは OpenAI**（OpenAI 公式、Azure OpenAI、OpenAI 互換インターフェースを提供するサードパーティモデルサービスなど、すべての OpenAI API 形式のサービスに対応）。
> Anthropic プロトコル（Claude ネイティブ API）を使用する場合は、以下のセクションを展開してください。

<details>
<summary><strong>AI プロトコルの切り替え（OpenAI ↔ Anthropic）</strong></summary>

このプロジェクトは **Spring AI の Starter 依存 + 設定ファイル** で基盤プロトコルを切り替える。Java ビジネスコード（`ChatModel`、`ChatClient`）は**変更不要**。

#### 方式 1：OpenAI プロトコル（デフォルト）

`pom.xml` の依存：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

`application.yml` の設定：

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

環境変数の例：

```sh
export AI_API_KEY=sk-proj-xxxxxxxx
export AI_BASE_URL=https://api.openai.com   # 任意の OpenAI 互換エンドポイントに変更可
export AI_MODEL=gpt-4o
```

> **TIP**: 多くのサードパーティモデルサービス（DeepSeek、Mistral、Qwen など）が OpenAI 互換 API を提供している。`AI_BASE_URL` と `AI_MODEL` を変更するだけで接続でき、プロトコル切り替えは不要。

#### 方式 2：Anthropic プロトコル（Claude ネイティブ API）

**ステップ 1**：`pom.xml` を編集 — OpenAI starter を Anthropic starter に置き換え：

```xml
<!-- OpenAI starter をコメントアウトまたは削除 -->
<!-- <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency> -->

<!-- Anthropic starter を追加 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

**ステップ 2**：`application.yml` を編集 — `spring.ai.openai` を `spring.ai.anthropic` に置き換え：

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

**ステップ 3**：環境変数を設定：

```sh
export AI_API_KEY=sk-ant-xxxxxxxx
export AI_BASE_URL=https://api.anthropic.com
export AI_MODEL=claude-sonnet-4-20250514
```

#### 切り替えの仕組み

Spring AI の `ChatModel` は統一された抽象インターフェース。異なる Starter が異なる実装を提供する：

| Starter 依存 | 自動注入される ChatModel 実装 | 設定プレフィックス |
|---|---|---|
| `spring-ai-starter-model-openai` | `OpenAiChatModel` | `spring.ai.openai.*` |
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` | `spring.ai.anthropic.*` |

ビジネスコードは常に `ChatModel` インターフェースに対してプログラムする。プロトコル切り替えには依存と設定の変更だけが必要で、Java コードの変更は不要。

</details>

以下のプロンプトを試してみよう(英語プロンプトの方が LLM に効果的だが、日本語でも可):

1. `Create a file called Hello.java that prints "Hello, World!"`
2. `List all Java files in this directory`
3. `What is the current git branch?`
4. `Create a directory called test_output and write 3 files in it`

## 設計思想：Spring AI の 6 層デザインパターン

Spring AI には多くのカプセル化レイヤーがある -- Builder、Advisor Chain、Observation、Strategy、再帰、Callback。以下では、あなたのコードから呼び出しが発行されてから HTTP リクエストが完了するまで、各デザインパターンを層ごとに分解して解説する。

### 全景図：1つの `.call()` が通過する 6 層

```
あなたのコード: chatClient.prompt().user(msg).call().chatResponse()
          │
    ┌─────┴─────┐
    │  Layer 1  │  Builder + Fluent API     ← 構築と組み立て
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 2  │  Advisor Chain (責任チェーン)   ← 拦截と拡張
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 3  │  Strategy (ストラテジーパターン)      ← マルチモデル切替
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 4  │  Observation (オブザーバー)     ← オブザーバビリティ
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 5  │  Recursion + Callback     ← ツールループ
    └─────┬─────┘
    ┌─────┴─────┐
    │  Layer 6  │  HTTP Request             ← 最終的な実行
    └───────────┘
```

一言でまとめると：**Builder がオブジェクトを構築し、Fluent API が呼び出しを導き、Advisor Chain が拦截して拡張し、Strategy が実装を切り替え、Observation がすべてを監視し、Recursion がループを駆動する。**

### Layer 1: Builder パターン + Fluent API（流れるようなインターフェース）

**解決する問題**：ChatClient の設定項目は多数ある（system prompt、tools、advisors、options...）。Builder を使わないと、10 個のパラメータを持つコンストラクタになってしまう。

```java
// Builder パターン：複雑なオブジェクトを段階的に構築
ChatClient chatClient = ChatClient.builder(chatModel)    // ① Builder を作成
        .defaultSystem("...")                             // ② 段階的にパラメータを設定
        .defaultTools(new BashTool())
        .defaultAdvisors(new ToolCallLoggingAdvisor())
        .build();                                         // ③ 最終オブジェクトを構築

// Fluent API（流れるようなインターフェース）：メソッドチェーン、毎回 this を返す
chatClient.prompt()     // → ChatClientRequestSpec
    .user(msg)          // → ChatClientRequestSpec（自分自身を返す）
    .call()             // → CallResponseSpec（別のオブジェクトに切り替え）
    .chatResponse();    // → ChatResponse
```

**設計思想**：Builder は「構築フェーズ」（不変の設定）を担当し、Fluent API は「使用フェーズ」（リクエストごとのパラメータ）を担当する。2つのフェーズは異なる型のオブジェクトを返すため、コンパイラが構築フェーズで `.user()` を呼び出すのを防ぐことができる。

ソースコードでの体現：
- `DefaultChatClientBuilder.java` -- 構築フェーズ
- `DefaultChatClientRequestSpec`（`DefaultChatClient.java:564`）-- 使用フェーズ
- `DefaultCallResponseSpec`（`DefaultChatClient.java:341`）-- レスポンスフェーズ

### Layer 2: Advisor Chain -- 責任チェーンパターン（Chain of Responsibility）

**解決する問題**：コアの呼び出しロジックを変更することなく、横断的関心事（ログ、キャッシュ、権限、ツール呼び出し記録）を挿入する。

```
リクエスト → ToolCallLoggingAdvisor → ChatModelCallAdvisor → HTTP
                  ↓                        ↓
              ツール呼び出しを出力        chatModel.call() を呼び出し
```

```java
// DefaultChatClient.java:921-931 -- チェーンの構築
private BaseAdvisorChain buildAdvisorChain() {
    // ユーザーが登録した advisor
    this.advisors.add(new ToolCallLoggingAdvisor());

    // チェーンの末尾：実際に AI を呼び出す advisor（フレームワークが自動追加するターミネータ）
    this.advisors.add(ChatModelCallAdvisor.builder()
            .chatModel(this.chatModel).build());

    return DefaultAroundAdvisorChain.builder(observationRegistry)
            .pushAll(this.advisors)
            .build();
}
```

各 Advisor のインターフェース（Servlet Filter に類似）：

```java
// CallAdvisor.java -- Filter インターフェースに類似
public interface CallAdvisor {
    ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain);
    //                                                リクエスト          チェーン（次を呼び出す）
}

// ToolCallLoggingAdvisor -- Filter.doFilter() に類似
public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
    ChatClientResponse response = chain.nextCall(request);  // 先に後続に渡す
    // その後 response からツール呼び出し情報を抽出して出力
    ... print toolCalls ...
    return response;
}
```

**類似例**：Servlet Filter、Spring HandlerInterceptor、Express Middleware と全く同じ構造。`chain.next()` = `filterChain.doFilter()`。

### Layer 3: Strategy パターン（ストラテジーパターン）

**解決する問題**：あなたのコードは `ChatModel` インターフェースに対してのみプログラムし、実行時には Anthropic、OpenAI、または任意の実装を使用できる。

```java
// ChatModel インターフェース -- ストラテジーの抽象
public interface ChatModel {
    ChatResponse call(Prompt prompt);
}

// AnthropicChatModel -- ストラテジー実装 A
// OpenAiChatModel   -- ストラテジー実装 B
```

あなたのコードには `AnthropicChatModel` は直接登場しない：

```java
// S01AgentLoop.java:52 -- 注入されるのはインターフェース、実装ではない
public S01AgentLoop(AiConfig aiConfig) {
    this.chatClient = ChatClient.builder(aiConfig.get())  // aiConfig.get() は ChatModel を返す
            ...
```

Spring Boot の自動設定が classpath 上の starter に基づいて注入する実装を決定する：

| classpath 上にあるもの | 注入される実装 |
|---|---|
| `spring-ai-starter-model-anthropic` | `AnthropicChatModel` |
| `spring-ai-starter-model-openai` | `OpenAiChatModel` |

### Layer 4: Observation パターン（オブザーバーパターン / オブザーバビリティ）

**解決する問題**：呼び出しプロセス全体に計測ポイントを埋め込み、ビジネスロジックに侵入しない。Micrometer メトリクス、分散トレーシング、ログなどをサポート。

```java
// DefaultChatClient.java:464-474 -- advisor chain 呼び出し全体をラップ
var observation = ChatClientObservationDocumentation.AI_CHAT_CLIENT
    .observation(this.observationConvention, ..., this.observationRegistry);

var chatClientResponse = observation.observe(() -> {
    // ← 監視される「アクション」
    return this.advisorChain.nextCall(chatClientRequest);
});
// observation が自動的に記録: 開始時刻、終了時刻、例外、タグなど
```

同じパターンが `AnthropicChatModel.internalCall()` にも1層あり、`DefaultToolCallingManager.executeToolCall()` にもさらにもう1層ある。3層の Observation がネストしている：

```
ChatClient Observation          ← 第 1 層：call() プロセス全体
  └─ ChatModel Observation      ← 第 2 層：単一の HTTP リクエスト
       └─ ToolCall Observation   ← 第 3 層：個々のツール実行
```

**設計思想**：オブザーバーパターンとデコレータのハイブリッド。`observation.observe(supplier)` は本質的に supplier の前後に `onStart()` / `onStop()` / `onError()` フックを追加している。

### Layer 5: Recursive + Callback -- 再帰 + コールバック

**解決する問題**：AI は連続して複数回ツールを呼び出す可能性があり、ループメカニズムが必要。

```java
// AnthropicChatModel.java:206-220 -- 再帰ループ
if (hasToolUse) {
    var result = toolCallingManager.executeToolCalls(prompt, response);
    return this.internalCall(        // ← 再帰！自分自身を呼び出し
        new Prompt(result.conversationHistory(), prompt.getOptions()),
        response
    );
}
return response;  // tool_use なし → 終了
```

ツール実行のコールバックメカニズム：

```java
// DefaultToolCallingManager.java:186-243
for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
    // ツール名で登録した ToolCallback を検索
    ToolCallback toolCallback = toolCallbacks.stream()
        .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
        .findFirst()...;

    // コールバック：リフレクションで @Tool アノテーション付きメソッドを呼び出し
    String result = toolCallback.call(arguments, toolContext);
}
```

**設計思想**：
- **再帰**が while ループの代わりとなり、累積状態（token 使用量）の受け渡しに自然に適している
- **Callback パターン**（`ToolCallback`）が「AI がどのツールを呼び出したいか」と「ツールをどう実行するか」を疎結合にする

### まとめ対応表

| パターン | 場所 | 解決する問題 |
|---|---|---|
| **Builder** | `ChatClient.builder()` | 複雑なオブジェクトの段階的構築 |
| **Fluent API** | `.prompt().user().call()` | 呼び出しインターフェースの可読性と型安全性 |
| **Chain of Responsibility** | Advisor Chain | 横断的関心事のプラグイン可能性（ログ、監視） |
| **Strategy** | `ChatModel` インターフェース | 複数 AI プロバイダーの透過的切り替え |
| **Observer** | Micrometer Observation | 非侵入なオブザーバビリティの計測ポイント |
| **Recursive + Callback** | `internalCall()` + `ToolCallback` | ツールループとツール実行の疎結合化 |
