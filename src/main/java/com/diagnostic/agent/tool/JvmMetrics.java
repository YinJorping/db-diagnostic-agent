package com.diagnostic.agent.tool;

import java.util.List;

public record JvmMetrics(
        long heapUsedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long nonHeapCommittedBytes,
        List<GcSnapshot> gcSnapshots,
        int threadCount,
        int peakThreadCount,
        int daemonThreadCount,
        long uptimeMs) {
}
