package com.diagnostic.agent.agent;

import com.diagnostic.agent.common.security.DefaultSensitiveDataMasker;
import com.diagnostic.agent.common.security.SensitiveDataMasker;
import com.diagnostic.agent.tool.DiskMetricsProvider;
import com.diagnostic.agent.tool.DiskProperties;
import com.diagnostic.agent.tool.DiskUsageTool;
import com.diagnostic.agent.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiskDiagnosisAgentTest {

    @Mock private com.diagnostic.agent.tool.ToolRegistry toolRegistry;
    @Mock private PromptService promptService;
    @Mock private LlmClient llmClient;
    @Mock private PromptContextBuilder promptContextBuilder;
    @Mock private DiskMetricsProvider diskMetricsProvider;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement stmt;
    @Mock private com.diagnostic.agent.trace.ExecutionTraceRepository traceRepository;
    private final com.diagnostic.agent.config.DiagnosticMetrics metrics =
            new com.diagnostic.agent.config.DiagnosticMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final SensitiveDataMasker masker = new DefaultSensitiveDataMasker();

    private DiskUsageTool diskUsageTool;
    private DiskDiagnosisAgent agent;

    @BeforeEach
    void setup() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(stmt);
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(stmt.executeQuery()).thenReturn(rs);
        diskUsageTool = spy(new DiskUsageTool(diskMetricsProvider, new DiskProperties(), dataSource));
        agent = new DiskDiagnosisAgent(toolRegistry, promptService, llmClient, promptContextBuilder,
                traceRepository, metrics, masker);
    }

    @Test
    void shouldReturnCorrectName() {
        assertThat(agent.getName()).isEqualTo("DiskDiagnosisAgent");
    }

    @Test
    void shouldReturnDiskKeywords() {
        assertThat(agent.getKeywords()).contains("disk", "磁盘", "io", "存储", "空间不足");
    }

    @Test
    void shouldAssignDiskUsageTool() {
        assertThat(agent.assignedTools()).containsExactly("DiskUsageTool");
    }

    @Test
    void shouldIncludeToolResultInLlmPrompt() throws Exception {
        when(toolRegistry.get("DiskUsageTool")).thenReturn(Optional.of(diskUsageTool));
        when(diskMetricsProvider.sample()).thenReturn(
                new com.diagnostic.agent.tool.DiskMetrics("/data", 100L * 1024 * 1024 * 1024, 50L * 1024 * 1024 * 1024));
        when(promptService.loadTemplate(anyString())).thenReturn("You are a disk expert.");
        when(promptContextBuilder.buildContext(anyString())).thenReturn("");
        when(llmClient.chat(anyString(), anyString())).thenReturn("磁盘空间正常。");

        DiagnosisResult result = agent.diagnose(new DiagnosisContext("sess-001", "磁盘空间不足"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("DiskDiagnosisAgent");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).contains("DiskUsageTool");
    }

    @Test
    void shouldExecuteAllToolsAlways() {
        assertThat(agent.shouldExecuteTool(diskUsageTool, "任意问题")).isTrue();
    }

    @Test
    void shouldPassEmptyParametersToTool() {
        Map<String, Object> params = agent.buildToolParameters(diskUsageTool, "任意问题");
        assertThat(params).isEmpty();
    }
}
