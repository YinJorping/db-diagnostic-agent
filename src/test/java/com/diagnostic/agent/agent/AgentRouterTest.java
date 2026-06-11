package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AgentRouterTest {

    @Mock private ToolRegistry toolRegistry;
    @Mock private PromptService promptService;
    @Mock private LlmClient llmClient;

    private AgentRouter router;

    @BeforeEach
    void setup() {
        SqlDiagnosisAgent sqlAgent = new SqlDiagnosisAgent(toolRegistry, promptService, llmClient);
        router = new AgentRouter(sqlAgent);
    }

    // ---- SQL 主题 → SqlDiagnosisAgent ----

    @Test
    void shouldRouteSqlProblemToSqlAgent() {
        assertThat(router.route("数据库查询很慢")).isNotNull();
    }

    @Test
    void shouldRouteExplainPlanProblemToSqlAgent() {
        assertThat(router.route("SQL执行计划异常")).isNotNull();
    }

    @Test
    void shouldRoutePostgresProblemToSqlAgent() {
        assertThat(router.route("PostgreSQL慢查询")).isNotNull();
    }

    @Test
    void shouldRouteSelectStatementToSqlAgent() {
        assertThat(router.route("SELECT * FROM user")).isNotNull();
    }

    @Test
    void shouldRouteDatabaseCpuProblemToSqlAgent() {
        assertThat(router.route("数据库CPU很高")).isNotNull();
    }

    @Test
    void shouldRouteCaseInsensitiveSqlKeywordToSqlAgent() {
        assertThat(router.route("sElEcT count(*) from orders")).isNotNull();
    }

    // ---- 非 SQL 主题 → null ----

    @Test
    void shouldReturnNullForNonSqlProblem() {
        assertThat(router.route("今天天气怎么样")).isNull();
    }

    @Test
    void shouldReturnNullForJavaCodeProblem() {
        assertThat(router.route("帮我写Java代码")).isNull();
    }
}
