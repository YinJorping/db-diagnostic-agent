package com.diagnostic.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorConfigTest {

    @Test
    void shouldCreateAgentExecutorWithExpectedConfiguration() {
        AgentExecutorConfig config = new AgentExecutorConfig();
        // 通过反射注入 @Value 字段（不加载 Spring 上下文）
        injectField(config, "corePoolSize", 2);
        injectField(config, "maxPoolSize", 4);
        injectField(config, "queueCapacity", 100);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) config.agentExecutor();

        assertThat(executor).isNotNull();
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
