package com.github.xingjianluo.remediationorchestratorjava.engine;

import com.github.xingjianluo.remediationorchestratorjava.model.MaintenanceWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 维护窗口登记与命中判定。命中时仅产生抑制审计，不创建任务、不执行任何动作。 */
public class MaintenanceWindowService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWindowService.class);

    private final Clock clock;
    private final CopyOnWriteArrayList<MaintenanceWindow> windows = new CopyOnWriteArrayList<>();

    public MaintenanceWindowService(Clock clock) {
        this.clock = clock;
    }

    public MaintenanceWindow register(String id, String targetPrefix, Instant start, Instant end) {
        MaintenanceWindow w = new MaintenanceWindow(id, targetPrefix, start, end);
        windows.add(w);
        log.info("[WINDOW] registered id={} targetPrefix={} window=[{},{})",
                id, targetPrefix, start, end);
        return w;
    }

    public List<MaintenanceWindow> all() {
        return List.copyOf(windows);
    }

    public MaintenanceWindow findCovering(String target, Instant now) {
        for (MaintenanceWindow w : windows) {
            if (w.covers(target, now)) {
                return w;
            }
        }
        return null;
    }

    public MaintenanceWindow findCoveringNow(String target) {
        return findCovering(target, Instant.now(clock));
    }
}
