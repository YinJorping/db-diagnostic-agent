package com.diagnostic.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    @Test
    void shouldRegisterAndLookupTool() {
        Tool dummy = dummyTool("explain");
        ToolRegistry registry = new ToolRegistry(List.of(dummy));

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.names()).contains("explain");
        assertThat(registry.get("explain")).isPresent();
    }

    @Test
    void shouldReturnEmptyForUnknownTool() {
        ToolRegistry registry = new ToolRegistry(List.of());

        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void shouldRegisterMultipleTools() {
        ToolRegistry registry = new ToolRegistry(List.of(
                dummyTool("t1"), dummyTool("t2"), dummyTool("t3")
        ));

        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.names()).containsExactlyInAnyOrder("t1", "t2", "t3");
    }

    @Test
    void shouldReturnAllTools() {
        ToolRegistry registry = new ToolRegistry(List.of(
                dummyTool("t1"), dummyTool("t2")
        ));

        assertThat(registry.getAllTools()).hasSize(2);
        assertThat(registry.getAllTools().stream().map(Tool::getName))
                .containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void getAllToolsShouldReturnImmutableCopy() {
        ToolRegistry registry = new ToolRegistry(List.of(dummyTool("t1")));

        List<Tool> tools = registry.getAllTools();
        assertThat(tools).hasSize(1);
    }

    private static Tool dummyTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "mock"; }
            @Override public ToolResult execute(Map<String, Object> params) { return null; }
        };
    }
}
