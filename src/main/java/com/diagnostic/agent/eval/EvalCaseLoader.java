package com.diagnostic.agent.eval;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class EvalCaseLoader {

    private static final String CASES_LOCATION = "classpath:eval-cases/*.yml";

    public List<EvalCaseSet> loadAll() {
        Yaml yaml = new Yaml();
        List<EvalCaseSet> result = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(CASES_LOCATION);
            for (Resource res : resources) {
                try (Reader reader = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                    Map<String, Object> raw = yaml.load(reader);
                    result.add(parse(raw));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load eval cases", e);
        }
        return result;
    }

    public List<EvalCase> loadByDomain(String domain) {
        return loadAll().stream()
                .filter(s -> "*".equals(domain) || s.domain().equals(domain))
                .flatMap(s -> s.cases().stream())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private EvalCaseSet parse(Map<String, Object> raw) {
        String domain = (String) raw.get("domain");
        String description = (String) raw.get("description");
        List<Map<String, Object>> caseList = (List<Map<String, Object>>) raw.get("cases");
        List<EvalCase> cases = new ArrayList<>();
        for (Map<String, Object> c : caseList) {
            cases.add(parseCase(c));
        }
        return new EvalCaseSet(domain, description, cases);
    }

    @SuppressWarnings("unchecked")
    private EvalCase parseCase(Map<String, Object> raw) {
        String id = (String) raw.get("id");
        String description = (String) raw.get("description");
        String problem = (String) raw.get("problem");
        Map<String, Object> exp = (Map<String, Object>) raw.get("expected");
        String agent = (String) exp.get("agent");
        com.diagnostic.agent.tool.RiskLevel risk = parseRisk((String) exp.get("risk"));
        List<String> keywords = (List<String>) exp.getOrDefault("keywords", List.of());
        int minKeywordMatches = exp.get("minKeywordMatches") instanceof Integer
                ? (Integer) exp.get("minKeywordMatches") : 1;
        List<String> recommendations = (List<String>) exp.getOrDefault("recommendations", List.of());
        return new EvalCase(id, description, problem,
                new EvalCase.ExpectedCriteria(agent, risk, keywords, minKeywordMatches, recommendations));
    }

    private com.diagnostic.agent.tool.RiskLevel parseRisk(String risk) {
        if (risk == null) return com.diagnostic.agent.tool.RiskLevel.UNKNOWN;
        return switch (risk.toUpperCase()) {
            case "HIGH" -> com.diagnostic.agent.tool.RiskLevel.HIGH;
            case "MEDIUM" -> com.diagnostic.agent.tool.RiskLevel.MEDIUM;
            case "LOW" -> com.diagnostic.agent.tool.RiskLevel.LOW;
            default -> com.diagnostic.agent.tool.RiskLevel.UNKNOWN;
        };
    }
}
