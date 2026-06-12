package com.diagnostic.agent.tool;

public record CpuMetrics(
        double systemCpuLoad,
        double processCpuLoad,
        double systemLoadAverage,
        int availableProcessors) {
}
