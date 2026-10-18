package com.bloxbean.cardano.yaci.store.governance.service;

import com.bloxbean.cardano.yaci.store.core.annotation.ReadOnly;
import com.bloxbean.cardano.yaci.store.governance.storage.impl.DRepAnchorResolutionStorageImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ReadOnly(false)
@ConditionalOnProperty(name = {"store.governance.anchor-resolver.enabled", "store.governance.anchor-resolution-enabled"}, havingValue = "true")
@EnableConfigurationProperties(DRepAnchorResolverProperties.class)
public class DRepAnchorResolverConfiguration {
    @Bean
    DRepAnchorDedicatedScheduler drepAnchorTaskScheduler(org.springframework.beans.factory.BeanFactory beans) {
        var scheduler = new DRepAnchorDedicatedScheduler(beans);
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("drep-anchor-resolver-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    DRepAnchorHttpFetcher anchorHttpFetcher(ObjectMapper json,
            @Value("${store.governance.anchor-resolution-max-body-bytes:3000000}") int max) {
        return new DRepAnchorHttpFetcher(json, max);
    }

    @Bean
    DRepAnchorResolverWork anchorResolverWork(DSLContext dsl) {
        return new DRepAnchorResolverWork(dsl);
    }

    @Bean
    DRepAnchorResolver anchorResolver(DRepAnchorResolutionStorageImpl storage, DRepAnchorHttpFetcher fetcher,
                                     DRepAnchorResolverWork work, DRepAnchorResolverProperties properties) {
        return new DRepAnchorResolver(storage, fetcher, work, properties);
    }
}
