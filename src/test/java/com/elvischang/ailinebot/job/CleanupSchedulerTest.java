package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.store.InboundEventRepository;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CleanupSchedulerTest extends IntegrationTestSupport {

    @Autowired
    CleanupScheduler cleanup;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    @Autowired
    InboundEventRepository inboundEvents;

    @Autowired
    MediaStore mediaStore;

    @Test
    void removesExpiredMaterialsFilesWaitingResultsAndOldRows() throws Exception {
        long start = clock.millis();
        materials.insert(new Material("Cg1", "200", MaterialKind.IMAGE, null, false, MediaStatus.STORED, "h", 0, start, start + 1000));
        var file = mediaStore.pathFor("Cg1", "200");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "abc");
        inboundEvents.insertIfAbsent("e1", start, false);

        String waiting = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "1", "1", "q", "rt", "qt", null), start);
        jobs.claim(waiting, start, 1000);
        jobs.complete(waiting, "結果", start);
        jobs.markWaiting(List.of(waiting), start);

        clock.advance(Duration.ofDays(8));
        cleanup.cleanup();

        assertThat(materials.find("Cg1", "200")).isEmpty();
        assertThat(Files.exists(file)).isFalse();
        assertThat(jobs.find(waiting).orElseThrow().sendState()).isEqualTo(SendState.EXPIRED);
        assertThat(jobs.find(waiting).orElseThrow().resultText()).isNull();
        assertThat(inboundEvents.insertIfAbsent("e1", clock.millis(), false)).isTrue();

        clock.advance(Duration.ofDays(91));
        cleanup.cleanup();
        assertThat(jobs.find(waiting)).isEmpty();
    }

    @Test
    void cleanupContinuesWhenAFileCannotBeDeleted() throws Exception {
        long start = clock.millis();
        materials.insert(new Material("Cg1", "201", MaterialKind.IMAGE, null, false, MediaStatus.STORED, "h", 0, start, start + 1000));
        materials.insert(new Material("Cg1", "202", MaterialKind.TEXT, "x", false, MediaStatus.NONE, null, 0, start, start + 1000));
        var undeletable = mediaStore.pathFor("Cg1", "201");
        Files.createDirectories(undeletable);
        Files.writeString(undeletable.resolve("inner"), "abc");

        String waiting = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "1", "1", "q", "rt", "qt", null), start);
        jobs.claim(waiting, start, 1000);
        jobs.complete(waiting, "結果", start);
        jobs.markWaiting(List.of(waiting), start);

        clock.advance(Duration.ofDays(8));
        try {
            cleanup.cleanup();

            assertThat(materials.find("Cg1", "201")).isEmpty();
            assertThat(materials.find("Cg1", "202")).isEmpty();
            assertThat(jobs.find(waiting).orElseThrow().sendState()).isEqualTo(SendState.EXPIRED);
        } finally {
            Files.deleteIfExists(undeletable.resolve("inner"));
            Files.deleteIfExists(undeletable);
        }
    }
}
