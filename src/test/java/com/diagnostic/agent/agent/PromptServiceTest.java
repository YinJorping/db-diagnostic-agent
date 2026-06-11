package com.diagnostic.agent.agent;

import com.diagnostic.agent.common.BusinessException;
import com.diagnostic.agent.repository.PromptTemplate;
import com.diagnostic.agent.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    private PromptTemplateRepository repository;

    private PromptService promptService;

    @BeforeEach
    void setup() {
        promptService = new PromptService(repository);
    }

    // ---- Scenario 1: 正常加载模板 ----

    @Test
    void shouldLoadTemplateByKey() {
        when(repository.findByTemplateKey("sql_diagnosis_system"))
                .thenReturn(Optional.of(new PromptTemplate(
                        "sql_diagnosis_system", "SQL诊断专家",
                        "你是一位经验丰富的数据库性能优化专家。")));

        String content = promptService.loadTemplate("sql_diagnosis_system");

        assertThat(content).contains("数据库性能优化专家");
    }

    // ---- Scenario 2: 模板不存在 ----

    @Test
    void shouldThrowWhenTemplateKeyNotFound() {
        when(repository.findByTemplateKey("nonexistent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> promptService.loadTemplate("nonexistent"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板未找到")
                .hasMessageContaining("nonexistent");
    }

    // ---- Scenario 3: 单变量替换 ----

    @Test
    void shouldRenderSingleVariable() {
        String result = promptService.render("Hello {name}",
                Map.of("name", "World"));

        assertThat(result).isEqualTo("Hello World");
    }

    // ---- Scenario 4: 多变量替换 ----

    @Test
    void shouldRenderMultipleVariables() {
        String template = "Key1={a}, Key2={b}";
        String result = promptService.render(template,
                Map.of("a", "X", "b", "Y"));

        assertThat(result).isEqualTo("Key1=X, Key2=Y");
    }

    // ---- Scenario 5: 未知变量保持原样 ----

    @Test
    void shouldLeaveUnknownVariablesUnchanged() {
        String result = promptService.render("Hello {name}", Map.of());

        assertThat(result).isEqualTo("Hello {name}");
    }

    // ---- Scenario 6: 空 Map 不修改模板 ----

    @Test
    void shouldNotModifyTemplateWithEmptyMap() {
        String template = "保持不变 {var}";

        String result = promptService.render(template, Map.of());

        assertThat(result).isEqualTo(template);
    }
}
