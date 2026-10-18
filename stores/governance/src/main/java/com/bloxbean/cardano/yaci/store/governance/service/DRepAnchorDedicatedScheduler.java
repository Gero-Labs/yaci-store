package com.bloxbean.cardano.yaci.store.governance.service;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TaskSchedulerRouter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Routes qualified DRep work here while preserving a separate scheduler for ordinary Yaci tasks. */
public class DRepAnchorDedicatedScheduler extends ThreadPoolTaskScheduler implements SchedulingConfigurer {
    private final BeanFactory beans;
    private TaskSchedulerRouter router;
    private ScheduledExecutorService fallback;

    public DRepAnchorDedicatedScheduler(BeanFactory beans) { this.beans = beans; }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (registrar.getScheduler() != null && registrar.getScheduler().getClass() != TaskSchedulerRouter.class) return;
        router = new TaskSchedulerRouter() {
            @Override
            protected TaskScheduler determineDefaultScheduler() {
                if (beans.containsBean("taskScheduler")) return beans.getBean("taskScheduler", TaskScheduler.class);
                if (beans instanceof ListableBeanFactory listable) {
                    var others = listable.getBeansOfType(TaskScheduler.class).values().stream()
                            .filter(candidate -> candidate != DRepAnchorDedicatedScheduler.this).toList();
                    if (others.size() == 1) return others.getFirst();
                    var executors = listable.getBeansOfType(ScheduledExecutorService.class);
                    if (executors.size() == 1) return new ConcurrentTaskScheduler(executors.values().iterator().next());
                }
                // This is Spring's ordinary no-scheduler fallback, kept separate from DRep HTTP work.
                fallback = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "yaci-default-scheduler"); thread.setDaemon(true); return thread;
                });
                return new ConcurrentTaskScheduler(fallback);
            }
        };
        router.setBeanFactory(beans);
        registrar.setTaskScheduler(router);
    }

    @Override
    public void destroy() {
        if (router != null) router.destroy();
        if (fallback != null) fallback.shutdownNow();
        super.destroy();
    }
}
