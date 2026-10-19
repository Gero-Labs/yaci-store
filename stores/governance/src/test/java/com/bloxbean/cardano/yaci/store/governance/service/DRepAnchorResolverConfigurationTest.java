package com.bloxbean.cardano.yaci.store.governance.service;

import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl;
import com.bloxbean.cardano.yaci.store.common.config.StoreProperties;
import com.bloxbean.cardano.yaci.store.plugin.scheduler.SchedulerConfiguration;
import com.bloxbean.cardano.yaci.store.plugin.scheduler.SchedulerService;
import com.bloxbean.cardano.yaci.store.plugin.metrics.PluginMetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DRepAnchorResolverConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DRepAnchorResolverConfiguration.class);
    @Test void pluginSchedulerServiceStartsAlongsideTheDedicatedResolverScheduler() {
        context.withUserConfiguration(SchedulerConfiguration.class, SchedulerService.class, SchedulingProbe.class)
                .withBean(StoreProperties.class, StoreProperties::new)
                .withBean(PluginMetricsCollector.class, () -> new PluginMetricsCollector(new StoreProperties(), null))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DSLContext.class, () -> DSL.using(SQLDialect.POSTGRES))
                .withBean(DRepAnchorResolutionStorageImpl.class, () -> mock(DRepAnchorResolutionStorageImpl.class))
                .withPropertyValues("store.governance.anchor-resolver.enabled=true", "store.governance.anchor-resolution-enabled=true")
                .run(c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(SchedulerService.class);
                    assertThat(c.getBean(org.springframework.scheduling.TaskScheduler.class))
                            .isSameAs(c.getBean("pluginTaskScheduler"));
                    var probe = c.getBean(ProbeJobs.class);
                    try {
                        assertThat(probe.resolverStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        assertThat(probe.generalRan.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        assertThat(probe.resolverThread).startsWith("drep-anchor-resolver-");
                        assertThat(probe.generalThread).startsWith("plugin-scheduler-");
                    } finally { probe.release.countDown(); }
                });
    }
    @Test void defaultsAndPartialFlagsDoNotCreateNetworkOrSchedulerBeans() {
        context.run(c -> assertThat(c).doesNotHaveBean(DRepAnchorResolver.class).doesNotHaveBean(DRepAnchorHttpFetcher.class));
        context.withPropertyValues("store.governance.anchor-resolver.enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(DRepAnchorResolver.class).doesNotHaveBean(DRepAnchorHttpFetcher.class));
        context.withPropertyValues("store.governance.anchor-resolution-enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(DRepAnchorResolver.class).doesNotHaveBean(DRepAnchorHttpFetcher.class));
    }
    @Test void readerReplicaNeverCreatesResolverEvenWithBothFlags() {
        context.withPropertyValues("store.governance.anchor-resolver.enabled=true", "store.governance.anchor-resolution-enabled=true", "store.read-only-mode=true")
                .run(c -> assertThat(c).doesNotHaveBean(DRepAnchorResolver.class).doesNotHaveBean(DRepAnchorHttpFetcher.class));
    }
    @Test void dedicatedSingleThreadSchedulerDoesNotDelayUnrelatedScheduling() throws Exception {
        var resolverScheduler = new DRepAnchorResolverConfiguration().drepAnchorTaskScheduler(new org.springframework.beans.factory.support.DefaultListableBeanFactory());
        var generalScheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        resolverScheduler.initialize(); generalScheduler.initialize();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var unrelated = new java.util.concurrent.CountDownLatch(1);
        var second = new java.util.concurrent.CountDownLatch(1);
        try {
            resolverScheduler.schedule(() -> {
                started.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, java.time.Instant.now());
            org.assertj.core.api.Assertions.assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            resolverScheduler.schedule(second::countDown, java.time.Instant.now());
            generalScheduler.schedule(unrelated::countDown, java.time.Instant.now());
            org.assertj.core.api.Assertions.assertThat(unrelated.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThat(second.getCount()).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(resolverScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            release.countDown();
            org.assertj.core.api.Assertions.assertThat(second.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var scheduled = DRepAnchorResolver.class.getMethod("resolveBatch").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
            org.assertj.core.api.Assertions.assertThat(scheduled.scheduler()).isEqualTo("drepAnchorTaskScheduler");
        } finally { release.countDown(); resolverScheduler.shutdown(); generalScheduler.shutdown(); }
    }
    @Test void actualSpringSchedulerRoutesUnqualifiedJobsAwayFromResolver() {
        context.withUserConfiguration(SchedulingProbe.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DSLContext.class, () -> DSL.using(SQLDialect.POSTGRES))
                .withBean(DRepAnchorResolutionStorageImpl.class, () -> mock(DRepAnchorResolutionStorageImpl.class))
                .withPropertyValues("store.governance.anchor-resolver.enabled=true", "store.governance.anchor-resolution-enabled=true")
                .run(c -> {
                    var probe = c.getBean(ProbeJobs.class);
                    try {
                        assertThat(probe.resolverStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        assertThat(probe.generalRan.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        assertThat(probe.resolverThread).startsWith("drep-anchor-resolver-");
                        assertThat(probe.generalThread).doesNotStartWith("drep-anchor-resolver-");
                    } finally { probe.release.countDown(); }
                });
    }
    // Registered explicitly by ApplicationContextRunner; keep it out of GovernanceStoreConfiguration scanning.
    @org.springframework.scheduling.annotation.EnableScheduling
    static class SchedulingProbe {
        @org.springframework.context.annotation.Bean ProbeJobs probeJobs() { return new ProbeJobs(); }
    }
    static class ProbeJobs {
        final java.util.concurrent.CountDownLatch resolverStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch generalRan = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        volatile String resolverThread, generalThread;
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000, scheduler = "drepAnchorTaskScheduler")
        public void resolverWork() throws InterruptedException {
            resolverThread = Thread.currentThread().getName(); resolverStarted.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
        public void unrelatedWork() {
            generalThread = Thread.currentThread().getName(); generalRan.countDown();
        }
    }
    @Test void actualSpringKeepsExistingDefaultSchedulerForUnqualifiedJobs() {
        context.withUserConfiguration(SchedulingProbe.class)
                .withBean("taskScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class, () -> {
                    var general = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
                    general.setThreadNamePrefix("existing-yaci-scheduler-"); return general;
                })
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DSLContext.class, () -> DSL.using(SQLDialect.POSTGRES))
                .withBean(DRepAnchorResolutionStorageImpl.class, () -> mock(DRepAnchorResolutionStorageImpl.class))
                .withPropertyValues("store.governance.anchor-resolver.enabled=true", "store.governance.anchor-resolution-enabled=true")
                .run(c -> {
                    var probe = c.getBean(ProbeJobs.class);
                    try {
                        assertThat(probe.resolverStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        assertThat(probe.generalRan.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        assertThat(probe.resolverThread).startsWith("drep-anchor-resolver-");
                        assertThat(probe.generalThread).startsWith("existing-yaci-scheduler-");
                    } finally { probe.release.countDown(); }
                });
    }
    @Test void explicitlyEnabledWriterBindsFiniteSettings() {
        context.withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DSLContext.class, () -> DSL.using(SQLDialect.POSTGRES))
                .withBean(DRepAnchorResolutionStorageImpl.class, () -> mock(DRepAnchorResolutionStorageImpl.class))
                .withPropertyValues("store.governance.anchor-resolver.enabled=true", "store.governance.anchor-resolution-enabled=true",
                        "store.governance.anchor-resolver.batch-size=2", "store.governance.anchor-resolver.ipfs-gateways[0]=https://gateway.example")
                .run(c -> {
                    assertThat(c).hasSingleBean(DRepAnchorResolver.class).hasSingleBean(DRepAnchorHttpFetcher.class);
                    assertThat(c.getBean(DRepAnchorResolverProperties.class).getBatchSize()).isEqualTo(2);
                    assertThat(c.getBean(DRepAnchorResolverProperties.class).getIpfsGateways()).containsExactly("https://gateway.example");
                });
    }
}
