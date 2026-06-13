package com.diagnostic.agent.eval;

import java.util.List;

public record EvalCaseSet(String domain, String description, List<EvalCase> cases) {
}
