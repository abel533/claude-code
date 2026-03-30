package io.mybatis.learn.core;

import io.mybatis.learn.core.config.ErrorHandlingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;
import java.util.function.Function;

/**
 * Agent 交互式 REPL（Read-Eval-Print Loop）工具类。
 * <p>
 * TIP: 对应 Python 中每个 session 的 {@code if __name__ == "__main__"} 交互循环。
 * Python 使用 {@code input()} 读取输入，Java 使用 {@code Scanner}。
 */
public class AgentRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_GREEN = "\033[32m";
    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_RESET = "\033[0m";
    private static int maxRetries = 3;
    private static int retryDelaySeconds = 2;

    static {
        try {
            maxRetries = Integer.parseInt(System.getProperty("app.agent.max-retries", "3"));
            retryDelaySeconds = Integer.parseInt(System.getProperty("app.agent.retry-delay", "2"));
        } catch (NumberFormatException e) {
            // 使用默认值
        }
    }

    /**
     * 启动交互式 REPL 循环。
     *
     * @param prefix  提示符前缀（如 "s01"）
     * @param handler 处理用户输入并返回 Agent 响应的函数
     */
    public static void interactive(String prefix, Function<String, String> handler) {
        log.info("启动交互循环，prefix={}", prefix);
        Scanner scanner = new Scanner(System.in);
        System.out.println("输入 'q' 或 'exit' 退出");
        System.out.println();
        while (true) {
            System.out.print(ANSI_CYAN + prefix + " >> " + ANSI_RESET);
            String input;
            try {
                if (!scanner.hasNextLine()) break;
                input = scanner.nextLine().trim();
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    System.out.printf("💭 读取输入异常，结束交互循环: prefix=%s, error=%s%n", prefix, e.getMessage());
                }
                break;
            }
            if (input.isEmpty() || "exit".equalsIgnoreCase(input) || "q".equalsIgnoreCase(input)) {
                log.info("收到退出指令，结束交互循环，prefix={}", prefix);
                break;
            }
            try {
                String response = handler.apply(input);
                if (response != null && !response.isBlank()) {
                    printAssistantResponse(response);
                }
            } catch (Exception e) {
                handleException(prefix, input, handler, e);
            }
            System.out.println();
        }
        log.info("交互循环结束，prefix={}", prefix);
        System.out.println("Bye!");
    }

    private static void handleException(String prefix, String input,
                                        Function<String, String> handler, Exception e) {
        ErrorHandlingInterceptor.AgentApiException apiEx =
                e instanceof ErrorHandlingInterceptor.AgentApiException
                    ? (ErrorHandlingInterceptor.AgentApiException) e
                    : null;

        if (apiEx != null && apiEx.getErrorType().isRetryable()) {
            for (int i = 0; i < maxRetries; i++) {
                System.out.printf("%s🔄 %s，正在重试 (%d/%d)...%s%n",
                        ANSI_YELLOW, getErrorHint(apiEx.getErrorType()), i + 1, maxRetries, ANSI_RESET);
                try {
                    Thread.sleep(retryDelaySeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    String response = handler.apply(input);
                    if (response != null && !response.isBlank()) {
                        printAssistantResponse(response);
                    }
                    return;
                } catch (Exception retryEx) {
                    ErrorHandlingInterceptor.AgentApiException retryApiEx =
                            retryEx instanceof ErrorHandlingInterceptor.AgentApiException
                                ? (ErrorHandlingInterceptor.AgentApiException) retryEx
                                : null;
                    if (retryApiEx != null && !retryApiEx.getErrorType().isRetryable()) {
                        break;
                    }
                }
            }
            log.warn("处理输入失败，已重试多次，prefix={}, error={}", prefix, e.getMessage());
            printAssistantError("请求失败，请稍后重试。错误: " + truncate(e.getMessage(), 200));
        } else {
            String hint = apiEx != null ? getErrorHint(apiEx.getErrorType()) : "请求失败";
            log.warn("处理输入失败，prefix={}, error={}", prefix, e.getMessage());
            printAssistantError(hint + ": " + truncate(e.getMessage(), 200));
        }
    }

    private static String getErrorHint(ErrorHandlingInterceptor.ErrorType type) {
        switch (type) {
            case TIMEOUT:      return "请求超时";
            case IO_ERROR:     return "网络错误";
            case SERVER_ERROR: return "服务器错误";
            case AUTH_FAILED:  return "API Key 无效，请检查配置";
            case FORBIDDEN:    return "无访问权限，请检查权限配置";
            case NOT_FOUND:    return "请求资源不存在";
            case RATE_LIMITED: return "API 请求过于频繁，请稍后重试";
            case CLIENT_ERROR: return "请求错误";
            default:          return "请求失败";
        }
    }

    private static void printAssistantResponse(String response) {
        System.out.println(ANSI_GREEN + "assistant >>" + ANSI_RESET);
        System.out.println(ANSI_CYAN + response + ANSI_RESET);
    }

    private static void printAssistantError(String errorMessage) {
        System.out.println(ANSI_RED + "assistant !!" + ANSI_RESET);
        System.out.println(ANSI_RED + "Error: " + errorMessage + ANSI_RESET);
    }

    /**
     * 截断过长输出。
     * TIP: 对应 Python 中 {@code out[:50000]} 的截断逻辑。
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "...(truncated)" : text;
    }
}
