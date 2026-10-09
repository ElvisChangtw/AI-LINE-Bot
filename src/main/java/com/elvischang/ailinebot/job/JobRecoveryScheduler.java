package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class JobRecoveryScheduler implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(JobRecoveryScheduler.class);
    private static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final ApplicationEventPublisher publisher;
    private final BotProperties props;
    private final Clock clock;

    public JobRecoveryScheduler(JobRepository jobs, MaterialRepository materials, ApplicationEventPublisher publisher,
                                BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int unknown = jobs.resetSendingToUnknown();
        if (unknown > 0) {
            log.warn("startup marked {} in-flight replies as UNKNOWN", unknown);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("startup sweep failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${bot.recovery-interval-ms:60000}", initialDelayString = "${bot.recovery-interval-ms:60000}")
    public void sweep() {
        long now = clock.millis();
        long staleBefore = now - props.staleAfter().toMillis();
        for (Job job : jobs.expiredLeases(now)) {
            if (job.attempts() >= props.jobMaxAttempts()) {
                jobs.fail(job.id(), BotReplies.JOB_FAILED, LEASE_EXPIRED, now);
            } else {
                jobs.release(job.id(), LEASE_EXPIRED);
            }
            publisher.publishEvent(new JobReadyEvent(job.id()));
        }
        jobs.stalePending(staleBefore).forEach(id -> publisher.publishEvent(new JobReadyEvent(id)));
        jobs.unsentFinished(staleBefore).forEach(id -> publisher.publishEvent(new JobReadyEvent(id)));
        long mediaStaleBefore = now - props.staleAfter().toMillis() * 2;
        for (Material material : materials.pendingMedia(mediaStaleBefore)) {
            publisher.publishEvent(new ImageReceivedEvent(material.groupId(), material.messageId()));
        }
    }
}
