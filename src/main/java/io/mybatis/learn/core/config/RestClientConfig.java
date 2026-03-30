package io.mybatis.learn.core.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(Duration.ofSeconds(120));

        return RestClient.builder()
                // 拦截器按注册顺序执行：
                // 先注册的在最外层，后注册的最接近实际 HTTP 调用
                // 请求路径：HttpLogging -> ErrorHandling -> 实际HTTP
                // 响应路径：实际HTTP -> ErrorHandling -> HttpLogging
                .requestInterceptor(new HttpLoggingInterceptor())
                .requestInterceptor(new ErrorHandlingInterceptor())
                .requestFactory(factory);
    }
}
