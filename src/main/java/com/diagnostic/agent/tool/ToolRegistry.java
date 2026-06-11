package com.diagnostic.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool 注册中心。
 * 启动时自动收集所有 Tool Bean，按 name 索引。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<Tool> toolList) {
        toolList.forEach(tool -> tools.put(tool.getName(), tool));
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public int size() {
        return tools.size();
    }

    public List<Tool> getAllTools() {
        return List.copyOf(tools.values());
    }
}
