package com.diagnostic.agent.tool;

public record DiskMetrics(String dataDirPath, long totalBytes, long usableBytes) {
}
