package com.diagnostic.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diagnostic.memory")
public class MemoryProperties {

    private double thresholdBufferHitHigh = 0.99;
    private double thresholdBufferHitMedium = 0.95;
    private int thresholdTempFilesCount = 100;
    private long thresholdTempBytesBytes = 1_073_741_824L;
    private int thresholdSharedBuffersMB = 256;
    private int thresholdWorkMemMB = 256;

    public double getThresholdBufferHitHigh() { return thresholdBufferHitHigh; }
    public void setThresholdBufferHitHigh(double v) { this.thresholdBufferHitHigh = v; }

    public double getThresholdBufferHitMedium() { return thresholdBufferHitMedium; }
    public void setThresholdBufferHitMedium(double v) { this.thresholdBufferHitMedium = v; }

    public int getThresholdTempFilesCount() { return thresholdTempFilesCount; }
    public void setThresholdTempFilesCount(int v) { this.thresholdTempFilesCount = v; }

    public long getThresholdTempBytesBytes() { return thresholdTempBytesBytes; }
    public void setThresholdTempBytesBytes(long v) { this.thresholdTempBytesBytes = v; }

    public int getThresholdSharedBuffersMB() { return thresholdSharedBuffersMB; }
    public void setThresholdSharedBuffersMB(int v) { this.thresholdSharedBuffersMB = v; }

    public int getThresholdWorkMemMB() { return thresholdWorkMemMB; }
    public void setThresholdWorkMemMB(int v) { this.thresholdWorkMemMB = v; }
}
