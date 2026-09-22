package com.github.h3yow.sagaorchestratorjava.config;

import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceCaller;
import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配下游调用防护组件（超时 / 重试 / 熔断）。
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public ResilienceCaller resilienceCaller(ResilienceProperties properties) {
        return new ResilienceCaller(properties);
    }
}
