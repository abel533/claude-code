package io.mybatis.learn.core.config;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

/**
 * HTTP 异常拦截器。
 * 捕获响应异常，分类为可重试或不可重试。
 */
public class ErrorHandlingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        try {
            return execution.execute(request, body);
        } catch (Exception e) {
            ErrorType errorType = classifyError(e);
            throw new AgentApiException(errorType, e.getMessage(), e);
        }
    }

    /**
     * 分类错误类型
     */
    public static ErrorType classifyError(Throwable e) {
        if (e instanceof HttpClientErrorException) {
            int status = ((HttpClientErrorException) e).getStatusCode().value();
            switch (status) {
                case 401: return ErrorType.AUTH_FAILED;
                case 403: return ErrorType.FORBIDDEN;
                case 404: return ErrorType.NOT_FOUND;
                case 429: return ErrorType.RATE_LIMITED;
                default:  return ErrorType.CLIENT_ERROR;
            }
        }
        if (e instanceof org.springframework.web.client.HttpServerErrorException) {
            return ErrorType.SERVER_ERROR;
        }
        if (e instanceof ResourceAccessException) {
            String msg = e.getMessage().toLowerCase();
            if (msg.contains("timeout")) {
                return ErrorType.TIMEOUT;
            }
            return ErrorType.IO_ERROR;
        }
        if (e.getCause() instanceof java.net.SocketTimeoutException) {
            return ErrorType.TIMEOUT;
        }
        if (e instanceof IOException) {
            return ErrorType.IO_ERROR;
        }
        return ErrorType.UNKNOWN;
    }

    public enum ErrorType {
        TIMEOUT,           // 请求超时 - 可重试
        IO_ERROR,          // 网络错误 - 可重试
        SERVER_ERROR,      // 5xx - 可重试
        AUTH_FAILED,       // 401 - 不可重试
        FORBIDDEN,         // 403 - 不可重试
        NOT_FOUND,         // 404 - 不可重试
        RATE_LIMITED,      // 429 - 不可重试
        CLIENT_ERROR,      // 其他 4xx - 不可重试
        UNKNOWN;           // 未知错误 - 不可重试

        public boolean isRetryable() {
            return this == TIMEOUT || this == IO_ERROR || this == SERVER_ERROR;
        }
    }

    /**
     * 自定义异常，携带错误类型信息
     */
    public static class AgentApiException extends RuntimeException {
        private final ErrorType errorType;

        public AgentApiException(ErrorType errorType, String message, Throwable cause) {
            super(message, cause);
            this.errorType = errorType;
        }

        public ErrorType getErrorType() {
            return errorType;
        }
    }
}
