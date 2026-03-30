package io.mybatis.learn.core.config;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 请求/响应日志拦截器。
 * 打印 Spring AI 发送给 LLM API 的完整请求和响应 body。
 */
public class HttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final String ANSI_BLUE = "\033[34m";
    private static final String ANSI_MAGENTA = "\033[35m";
    private static final String ANSI_RESET = "\033[0m";
    private static final int MAX_BODY_LENGTH = 5000;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        // 打印请求
        String reqBody = new String(body, StandardCharsets.UTF_8);
        System.out.printf("%s>>> %s %s%n%s%n%s%n",
                ANSI_BLUE, request.getMethod(), request.getURI(),
                truncate(reqBody), ANSI_RESET);

        // 执行请求
        ClientHttpResponse response = execution.execute(request, body);

        // 读取并打印响应（需要包装以便后续还能读取）
        byte[] respBytes = response.getBody().readAllBytes();
        String respBody = new String(respBytes, StandardCharsets.UTF_8);
        System.out.printf("%s<<< %s %s%n%s%n%s%n",
                ANSI_MAGENTA, response.getStatusCode(), request.getURI(),
                truncate(respBody), ANSI_RESET);

        // 返回可重复读取的 response
        return new RepeatableResponse(response, respBytes);
    }

    private static String truncate(String text) {
        if (text.length() > MAX_BODY_LENGTH) {
            return text.substring(0, MAX_BODY_LENGTH) + "\n... (truncated, total " + text.length() + " chars)";
        }
        return text;
    }

    private static class RepeatableResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        RepeatableResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public ByteArrayInputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
