package com.diagnostic.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diagnostic.disk")
public class DiskProperties {

    private String dataDir = "/var/lib/postgresql/data";
    private double thresholdUsageHigh = 0.85;
    private double thresholdUsageMedium = 0.70;
    private long thresholdFreeBytesLow = 10L * 1024 * 1024 * 1024; // 10GB

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public double getThresholdUsageHigh() { return thresholdUsageHigh; }
    public void setThresholdUsageHigh(double thresholdUsageHigh) { this.thresholdUsageHigh = thresholdUsageHigh; }

    public double getThresholdUsageMedium() { return thresholdUsageMedium; }
    public void setThresholdUsageMedium(double thresholdUsageMedium) { this.thresholdUsageMedium = thresholdUsageMedium; }

    public long getThresholdFreeBytesLow() { return thresholdFreeBytesLow; }
    public void setThresholdFreeBytesLow(long thresholdFreeBytesLow) { this.thresholdFreeBytesLow = thresholdFreeBytesLow; }
}
