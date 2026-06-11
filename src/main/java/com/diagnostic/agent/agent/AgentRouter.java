package com.diagnostic.agent.agent;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRouter {

    private static final List<String> SQL_TOPIC_KEYWORDS = List.of(
            "sql", "数据库", "查询", "索引", "执行计划", "慢查询",
            "postgres", "postgresql", "mysql",
            "select", "insert", "update", "delete"
    );

    private final SqlDiagnosisAgent sqlDiagnosisAgent;

    public AgentRouter(SqlDiagnosisAgent sqlDiagnosisAgent) {
        this.sqlDiagnosisAgent = sqlDiagnosisAgent;
    }

    public Agent route(String problem) {
        if (problem == null || problem.isBlank()) {
            return null;
        }
        String lower = problem.toLowerCase();
        if (SQL_TOPIC_KEYWORDS.stream().anyMatch(lower::contains)) {
            return sqlDiagnosisAgent;
        }
        return null;
    }
}
