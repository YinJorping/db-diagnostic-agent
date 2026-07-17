package com.diagnostic.agent.agent;

import com.diagnostic.agent.common.security.DefaultSensitiveDataMasker;
import com.diagnostic.agent.common.security.SensitiveDataMasker;
import com.diagnostic.agent.config.DiagnosticMetrics;
import com.diagnostic.agent.tool.ToolRegistry;
import com.diagnostic.agent.trace.ExecutionTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AgentRouterTest {

    @Mock private ToolRegistry toolRegistry;
    @Mock private PromptService promptService;
    @Mock private LlmClient llmClient;
    @Mock private PromptContextBuilder promptContextBuilder;
    @Mock private ExecutionTraceRepository traceRepository;
    private final DiagnosticMetrics metrics =
            new DiagnosticMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final SensitiveDataMasker masker = new DefaultSensitiveDataMasker();

    private AgentRouter router;
    private SqlDiagnosisAgent sqlAgent;
    private Agent cpuAgent;
    private Agent memoryAgent;
    private Agent jvmAgent;
    private Agent diskAgent;

    @BeforeEach
    void setup() {
        sqlAgent = new SqlDiagnosisAgent(toolRegistry, promptService, llmClient, promptContextBuilder,
                traceRepository, metrics, masker);
        cpuAgent = new TestKeywordAgent("CpuAgent", List.of("cpu", "负载", "load", "CPU飙高", "CPU 100%"));
        memoryAgent = new TestKeywordAgent("MemoryAgent",
                List.of("内存", "memory", "缓存", "buffer", "shared_buffers", "work_mem", "命中率", "内存不足"));
        jvmAgent = new TestKeywordAgent("JvmAgent",
                List.of("jvm", "heap", "gc", "full gc", "young gc", "old gc", "metaspace", "oom", "堆", "垃圾回收", "元空间"));
        diskAgent = new TestKeywordAgent("DiskAgent",
                List.of("disk", "磁盘", "io", "i/o", "存储", "空间", "容量", "空间不足", "磁盘满", "磁盘使用率"));
        router = new AgentRouter(List.of(sqlAgent, cpuAgent, memoryAgent, jvmAgent, diskAgent));
    }

    // ---- route() — 向后兼容 ----

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

    @Test
    void shouldRouteQuerySlowToSqlAgent() {
        Agent agent = router.route("why is my query slow");
        assertThat(agent).isNotNull();
        assertThat(agent.getName()).isEqualTo("SqlDiagnosisAgent");
    }

    @Test
    void shouldRouteQuerySlowInRouteAll() {
        List<Agent> agents = router.routeAll("why is my query slow");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).getName()).isEqualTo("SqlDiagnosisAgent");
    }

    @Test
    void shouldReturnNullForNonSqlProblem() {
        assertThat(router.route("今天天气怎么样")).isNull();
    }

    @Test
    void shouldReturnNullForJavaCodeProblem() {
        assertThat(router.route("帮我写Java代码")).isNull();
    }

    // ---- null / blank ----

    @Test
    void shouldReturnNullForNullProblemInRoute() {
        assertThat(router.route(null)).isNull();
    }

    @Test
    void shouldReturnNullForEmptyProblemInRoute() {
        assertThat(router.route("  ")).isNull();
    }

    // ---- routeAll() ----

    @Test
    void shouldReturnSingleAgentForSqlOnlyProblem() {
        List<Agent> agents = router.routeAll("SELECT * FROM t");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0)).isSameAs(sqlAgent);
    }

    @Test
    void shouldReturnSingleAgentForCpuOnlyProblem() {
        List<Agent> agents = router.routeAll("CPU负载很高");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).getName()).isEqualTo("CpuAgent");
    }

    @Test
    void shouldReturnMultipleAgentsForCrossDomainProblem() {
        List<Agent> agents = router.routeAll("数据库查询很慢且CPU负载100%");
        assertThat(agents).hasSize(2);
    }

    @Test
    void shouldMatchBothAgentsForSqlAndCpu100Problem() {
        List<Agent> agents = router.routeAll("数据库慢且CPU 100%");
        assertThat(agents).hasSize(2);
        assertThat(agents.stream().map(Agent::getName))
                .containsExactlyInAnyOrder("SqlDiagnosisAgent", "CpuAgent");
    }

    @Test
    void shouldReturnEmptyListForNoMatch() {
        List<Agent> agents = router.routeAll("今天天气怎么样");
        assertThat(agents).isEmpty();
    }

    @Test
    void shouldReturnEmptyListForNullProblem() {
        assertThat(router.routeAll(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyListForBlankProblem() {
        assertThat(router.routeAll("  ")).isEmpty();
    }

    // ---- Memory Agent 路由 ----

    @Test
    void shouldRouteToMemoryAgentOnly() {
        List<Agent> agents = router.routeAll("缓存命中率低");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).getName()).isEqualTo("MemoryAgent");
    }

    @Test
    void shouldRouteToAllThreeAgentsForCrossDomainProblem() {
        List<Agent> agents = router.routeAll("数据库慢且CPU高内存不足");
        assertThat(agents).hasSize(3);
        assertThat(agents.stream().map(Agent::getName))
                .containsExactlyInAnyOrder("SqlDiagnosisAgent", "CpuAgent", "MemoryAgent");
    }

    @Test
    void shouldNotMatchAnyAgentForUnrelatedInput() {
        List<Agent> agents = router.routeAll("今天天气怎么样");
        assertThat(agents).isEmpty();
    }

    // ---- JVM Agent 路由 ----

    @Test
    void shouldRouteToJvmAgentOnly() {
        List<Agent> agents = router.routeAll("Full GC 频繁触发");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).getName()).isEqualTo("JvmAgent");
    }

    @Test
    void shouldRouteToSqlAndJvmAgents() {
        List<Agent> agents = router.routeAll("数据库慢且heap高");
        assertThat(agents).hasSize(2);
        assertThat(agents.stream().map(Agent::getName))
                .containsExactlyInAnyOrder("SqlDiagnosisAgent", "JvmAgent");
    }

    @Test
    void shouldRouteToAllFourAgents() {
        List<Agent> agents = router.routeAll("数据库慢且CPU高内存不足且JVM堆满");
        assertThat(agents).hasSize(4);
        assertThat(agents.stream().map(Agent::getName))
                .containsExactlyInAnyOrder("SqlDiagnosisAgent", "CpuAgent", "MemoryAgent", "JvmAgent");
    }

    @Test
    void shouldNotRouteMemoryKeywordToJvmAgent() {
        List<Agent> agents = router.routeAll("数据库内存不足");
        assertThat(agents.stream().map(Agent::getName)).doesNotContain("JvmAgent");
    }

    // ---- Disk Agent 路由 ----

    @Test
    void shouldRouteToDiskAgentOnly() {
        List<Agent> agents = router.routeAll("磁盘空间不足");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).getName()).isEqualTo("DiskAgent");
    }

    @Test
    void shouldRouteToSqlAndDiskAgents() {
        List<Agent> agents = router.routeAll("数据库慢且I/O很高");
        assertThat(agents).hasSize(2);
        assertThat(agents.stream().map(Agent::getName))
                .contains("SqlDiagnosisAgent", "DiskAgent");
    }

    @Test
    void shouldRouteToAllFiveAgents() {
        List<Agent> agents = router.routeAll("数据库慢且CPU高内存不足JVM堆满且磁盘满");
        assertThat(agents).hasSize(5);
        assertThat(agents.stream().map(Agent::getName))
                .contains("SqlDiagnosisAgent", "CpuAgent", "MemoryAgent", "JvmAgent", "DiskAgent");
    }

    // ---- helper ----

    static class TestKeywordAgent implements Agent {
        private final String name;
        private final List<String> keywords;

        TestKeywordAgent(String name, List<String> keywords) {
            this.name = name;
            this.keywords = keywords;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "test"; }
        @Override public List<String> getKeywords() { return keywords; }
        @Override public DiagnosisResult diagnose(DiagnosisContext ctx) {
            throw new UnsupportedOperationException();
        }
    }
}
