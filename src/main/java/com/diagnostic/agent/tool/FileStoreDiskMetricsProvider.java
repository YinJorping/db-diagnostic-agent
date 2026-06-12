package com.diagnostic.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Primary
@Component
public class FileStoreDiskMetricsProvider implements DiskMetricsProvider {

    private static final Logger log = LoggerFactory.getLogger(FileStoreDiskMetricsProvider.class);

    private final DiskProperties diskProps;

    public FileStoreDiskMetricsProvider(DiskProperties diskProps) {
        this.diskProps = diskProps;
    }

    @Override
    public DiskMetrics sample() {
        try {
            Path path = Path.of(diskProps.getDataDir());
            Path absolutePath = path.toAbsolutePath();
            var store = Files.getFileStore(absolutePath);
            return new DiskMetrics(
                    absolutePath.toString(),
                    store.getTotalSpace(),
                    store.getUsableSpace());
        } catch (Exception e) {
            log.warn("FileStore 采样失败: path={}, {}", diskProps.getDataDir(), e.getMessage());
            return new DiskMetrics(diskProps.getDataDir(), 0, 0);
        }
    }
}
