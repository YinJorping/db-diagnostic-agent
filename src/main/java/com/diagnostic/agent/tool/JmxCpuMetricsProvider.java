package com.diagnostic.agent.tool;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

@Primary
@Component
public class JmxCpuMetricsProvider implements CpuMetricsProvider {

    @Override
    public CpuMetrics sample() {
        OperatingSystemMXBean bean = (OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        return new CpuMetrics(
                bean.getSystemCpuLoad(),
                bean.getProcessCpuLoad(),
                bean.getSystemLoadAverage(),
                bean.getAvailableProcessors());
    }
}
