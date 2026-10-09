package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.store.InboundEventRepository;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final MaterialRepository materials;
    private final JobRepository jobs;
    private final InboundEventRepository inboundEvents;
    private final MediaStore mediaStore;
    private final BotProperties props;
    private final Clock clock;

    public CleanupScheduler(MaterialRepository materials, JobRepository jobs, InboundEventRepository inboundEvents,
                            MediaStore mediaStore, BotProperties props, Clock clock) {
        this.materials = materials;
        this.jobs = jobs;
        this.inboundEvents = inboundEvents;
        this.mediaStore = mediaStore;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${bot.cleanup-cron:0 30 3 * * *}", zone = "Asia/Taipei")
    public void cleanup() {
        long now = clock.millis();
        for (Material material : materials.expired(now)) {
            try {
                mediaStore.delete(material.groupId(), material.messageId());
            } catch (IllegalArgumentException e) {
                log.debug("material {} has no media file", material.messageId());
            }
        }
        int deletedMaterials = materials.deleteExpired(now);
        int expiredWaiting = jobs.expireWaiting(now - props.waitingTtl().toMillis());
        int clearedJobs = jobs.clearContent(now - props.materialTtl().toMillis());
        int deletedJobs = jobs.deleteOlderThan(now - props.jobRetention().toMillis());
        int deletedEvents = inboundEvents.deleteOlderThan(now - props.materialTtl().toMillis());
        log.info("cleanup materials={} expiredWaiting={} clearedJobs={} deletedJobs={} deletedEvents={}",
                deletedMaterials, expiredWaiting, clearedJobs, deletedJobs, deletedEvents);
    }
}
