package com.diagnostic.agent.tool;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

@Primary
@Component
public class JmxJvmMetricsProvider implements JvmMetricsProvider {

    @Override
    public JvmMetrics sample() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed();
        long nonHeapCommitted = memoryBean.getNonHeapMemoryUsage().getCommitted();

        List<GcSnapshot> gcSnapshots = new ArrayList<>();
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcSnapshots.add(new GcSnapshot(
                    gcBean.getName(),
                    gcBean.getCollectionCount(),
                    gcBean.getCollectionTime()));
        }

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadBean.getThreadCount();
        int peakThreadCount = threadBean.getPeakThreadCount();
        int daemonThreadCount = threadBean.getDaemonThreadCount();

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        return new JvmMetrics(heapUsed, heapMax, nonHeapUsed, nonHeapCommitted,
                gcSnapshots, threadCount, peakThreadCount, daemonThreadCount, uptimeMs);
    }
}
