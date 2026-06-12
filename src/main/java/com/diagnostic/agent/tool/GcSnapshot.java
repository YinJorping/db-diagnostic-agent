package com.diagnostic.agent.tool;

public record GcSnapshot(String name, long collectionCount, long collectionTimeMs) {
}
