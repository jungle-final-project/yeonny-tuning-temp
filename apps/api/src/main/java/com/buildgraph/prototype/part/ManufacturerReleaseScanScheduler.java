package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DemoFreezeGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "part.manufacturer-release-intake", name = "enabled", havingValue = "true")
public class ManufacturerReleaseScanScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ManufacturerReleaseScanScheduler.class);

    private final ManufacturerReleaseIntakeService manufacturerReleaseIntakeService;
    private final DemoFreezeGuard demoFreezeGuard;

    public ManufacturerReleaseScanScheduler(ManufacturerReleaseIntakeService manufacturerReleaseIntakeService, DemoFreezeGuard demoFreezeGuard) {
        this.manufacturerReleaseIntakeService = manufacturerReleaseIntakeService;
        this.demoFreezeGuard = demoFreezeGuard;
    }

    @Scheduled(cron = "${part.manufacturer-release-intake.cron:0 0 6 * * *}", zone = "${part.manufacturer-release-intake.zone:Asia/Seoul}")
    public void scanManufacturerReleaseSources() {
        if (demoFreezeGuard.frozen()) {
            LOGGER.info("Manufacturer release intake scan skipped: demo freeze is on");
            return;
        }
        Map<String, Object> result = manufacturerReleaseIntakeService.scanAll(20, true);
        LOGGER.info("Manufacturer release intake scan finished: {}", result);
    }
}
