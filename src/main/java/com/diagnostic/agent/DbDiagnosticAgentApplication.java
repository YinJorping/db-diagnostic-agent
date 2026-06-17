package com.diagnostic.agent;

import com.diagnostic.agent.agent.LlmClient;
import com.diagnostic.agent.agent.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
public class DbDiagnosticAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(DbDiagnosticAgentApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(DbDiagnosticAgentApplication.class, args);
    }

    @Bean
    @ConditionalOnBean({LlmProperties.class, LlmClient.class})
    CommandLineRunner logLlmProvider(LlmProperties props, LlmClient client) {
        return args -> log.info("Current LLM Provider = {}, Client = {}",
                props.getProvider(), client.getClass().getSimpleName());
    }
}
