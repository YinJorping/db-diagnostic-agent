package com.diagnostic.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DbDiagnosticAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbDiagnosticAgentApplication.class, args);
    }
}
