package com.diagnostic.agent.eval;

import com.diagnostic.agent.tool.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalCaseLoaderTest {

    private final EvalCaseLoader loader = new EvalCaseLoader();

    @Test
    void shouldLoadAllDomainSets() {
        List<EvalCaseSet> allSets = loader.loadAll();
        assertThat(allSets).isNotEmpty();
        assertThat(allSets).extracting(EvalCaseSet::domain)
                .contains("sql", "cpu", "memory", "jvm", "disk");
    }

    @Test
    void shouldFilterByDomain() {
        List<EvalCase> sqlCases = loader.loadByDomain("sql");
        assertThat(sqlCases).hasSize(7);
        assertThat(sqlCases).extracting(EvalCase::id)
                .containsExactly("sql-001", "sql-002", "sql-003", "sql-004", "sql-005", "sql-006", "sql-007");
    }

    @Test
    void shouldLoadAllWithWildcard() {
        List<EvalCase> all = loader.loadByDomain("*");
        assertThat(all).hasSize(22);
    }

    @Test
    void shouldParseExpectedFields() {
        List<EvalCase> cases = loader.loadByDomain("cpu");
        EvalCase cpu001 = cases.get(0);
        assertThat(cpu001.id()).isEqualTo("cpu-001");
        assertThat(cpu001.description()).isNotBlank();
        assertThat(cpu001.problem()).isNotBlank();
        assertThat(cpu001.expected().agent()).isEqualTo("CpuDiagnosisAgent");
        assertThat(cpu001.expected().risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(cpu001.expected().keywords()).isNotEmpty();
        assertThat(cpu001.expected().recommendations()).isNotEmpty();
    }

    @Test
    void shouldParseRecommendationsForAllCases() {
        List<EvalCaseSet> sets = loader.loadAll();
        for (EvalCaseSet set : sets) {
            for (EvalCase c : set.cases()) {
                assertThat(c.expected().recommendations())
                        .as("Case %s should have recommendations", c.id())
                        .isNotEmpty();
            }
        }
    }
}
