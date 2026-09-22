package com.github.h3yow.sagaorchestratorjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用 Saga 配置属性绑定。 */
@Configuration
@EnableConfigurationProperties(SagaProperties.class)
public class SagaConfig {
}
