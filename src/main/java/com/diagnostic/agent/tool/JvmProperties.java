package com.diagnostic.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diagnostic.jvm")
public class JvmProperties {

    private double thresholdHeapHigh = 0.85;
    private double thresholdHeapMedium = 0.70;
    private double thresholdNonHeapHigh = 0.90;
    private int thresholdThreadCount = 500;

    public double getThresholdHeapHigh() { return thresholdHeapHigh; }
    public void setThresholdHeapHigh(double thresholdHeapHigh) { this.thresholdHeapHigh = thresholdHeapHigh; }

    public double getThresholdHeapMedium() { return thresholdHeapMedium; }
    public void setThresholdHeapMedium(double thresholdHeapMedium) { this.thresholdHeapMedium = thresholdHeapMedium; }

    public double getThresholdNonHeapHigh() { return thresholdNonHeapHigh; }
    public void setThresholdNonHeapHigh(double thresholdNonHeapHigh) { this.thresholdNonHeapHigh = thresholdNonHeapHigh; }

    public int getThresholdThreadCount() { return thresholdThreadCount; }
    public void setThresholdThreadCount(int thresholdThreadCount) { this.thresholdThreadCount = thresholdThreadCount; }
}
