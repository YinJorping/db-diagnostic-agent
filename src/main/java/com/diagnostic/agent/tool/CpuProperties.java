package com.diagnostic.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diagnostic.cpu")
public class CpuProperties {

    private double thresholdSystemHigh = 0.9;
    private double thresholdSystemMedium = 0.7;
    private double thresholdProcessHigh = 0.8;
    private double thresholdLoadHighMultiplier = 1.5;
    private double thresholdLoadMediumMultiplier = 1.0;

    public double getThresholdSystemHigh() { return thresholdSystemHigh; }
    public void setThresholdSystemHigh(double thresholdSystemHigh) { this.thresholdSystemHigh = thresholdSystemHigh; }

    public double getThresholdSystemMedium() { return thresholdSystemMedium; }
    public void setThresholdSystemMedium(double thresholdSystemMedium) { this.thresholdSystemMedium = thresholdSystemMedium; }

    public double getThresholdProcessHigh() { return thresholdProcessHigh; }
    public void setThresholdProcessHigh(double thresholdProcessHigh) { this.thresholdProcessHigh = thresholdProcessHigh; }

    public double getThresholdLoadHighMultiplier() { return thresholdLoadHighMultiplier; }
    public void setThresholdLoadHighMultiplier(double thresholdLoadHighMultiplier) { this.thresholdLoadHighMultiplier = thresholdLoadHighMultiplier; }

    public double getThresholdLoadMediumMultiplier() { return thresholdLoadMediumMultiplier; }
    public void setThresholdLoadMediumMultiplier(double thresholdLoadMediumMultiplier) { this.thresholdLoadMediumMultiplier = thresholdLoadMediumMultiplier; }
}
