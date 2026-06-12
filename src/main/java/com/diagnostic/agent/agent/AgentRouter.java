package com.diagnostic.agent.agent;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class AgentRouter {

    private final List<Agent> agents;

    public AgentRouter(List<Agent> agents) {
        this.agents = agents.stream()
                .sorted(Comparator.comparingInt(AgentRouter::priority))
                .toList();
    }

    /**
     * 返回第一个匹配的 Agent，无匹配返回 null。
     * 匹配顺序由 {@link Order} 注解决定（值越小越优先），未标注默认最低优先级。
     */
    public Agent route(String problem) {
        if (problem == null || problem.isBlank()) {
            return null;
        }
        String lower = problem.toLowerCase();
        return agents.stream()
                .filter(a -> matches(a, lower))
                .findFirst()
                .orElse(null);
    }

    /**
     * 返回所有匹配的 Agent，无匹配返回空列表。
     */
    public List<Agent> routeAll(String problem) {
        if (problem == null || problem.isBlank()) {
            return List.of();
        }
        String lower = problem.toLowerCase();
        return agents.stream()
                .filter(a -> matches(a, lower))
                .toList();
    }

    private static boolean matches(Agent agent, String lowerProblem) {
        return agent.getKeywords().stream().anyMatch(lowerProblem::contains);
    }

    private static int priority(Agent agent) {
        Order order = agent.getClass().getAnnotation(Order.class);
        return order != null ? order.value() : Integer.MAX_VALUE;
    }
}
