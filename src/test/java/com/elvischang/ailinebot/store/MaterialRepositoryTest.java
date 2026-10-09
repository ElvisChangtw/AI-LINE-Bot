package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialRepositoryTest extends IntegrationTestSupport {

    @Autowired
    MaterialRepository materials;

    private static Material text(String group, String id, long at, boolean trigger) {
        return new Material(group, id, MaterialKind.TEXT, "t" + id, trigger, MediaStatus.NONE, null, 0, at, at + 1_000_000);
    }

    @Test
    void findsOnlyWithinTheSameGroup() {
        materials.insert(text("Cg1", "1", 100, false));
        assertThat(materials.find("Cg1", "1")).get().extracting(Material::text).isEqualTo("t1");
        assertThat(materials.find("Cg2", "1")).isEmpty();
    }

    @Test
    void insertIgnoresDuplicates() {
        materials.insert(text("Cg1", "1", 100, false));
        materials.insert(new Material("Cg1", "1", MaterialKind.TEXT, "changed", false, MediaStatus.NONE, null, 0, 100, 200));
        assertThat(materials.find("Cg1", "1")).get().extracting(Material::text).isEqualTo("t1");
    }

    @Test
    void recentCandidatesExcludeTriggersBotRepliesOldAndOtherGroups() {
        materials.insert(text("Cg1", "old", 10, false));
        materials.insert(text("Cg1", "a", 100, false));
        materials.insert(text("Cg1", "trigger", 110, true));
        materials.insert(new Material("Cg1", "bot", MaterialKind.BOT_REPLY, null, false, MediaStatus.NONE, null, 0, 120, 999));
        materials.insert(text("Cg2", "other", 130, false));
        materials.insert(text("Cg1", "self", 140, false));

        assertThat(materials.recentCandidates("Cg1", 50, "self"))
                .extracting(Material::messageId).containsExactly("a");
    }

    @Test
    void recordsMediaFailuresUntilMax() {
        materials.insert(new Material("Cg1", "9", MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, 100, 999));
        assertThat(materials.recordMediaFailure("Cg1", "9", 2)).isEqualTo(1);
        assertThat(materials.find("Cg1", "9")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.PENDING);
        assertThat(materials.recordMediaFailure("Cg1", "9", 2)).isEqualTo(2);
        assertThat(materials.find("Cg1", "9")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.FAILED);
    }

    @Test
    void listsPendingMediaAndExpiredRows() {
        materials.insert(new Material("Cg1", "9", MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, 100, 500));
        materials.insert(text("Cg1", "1", 100, false));
        assertThat(materials.pendingMedia(101)).extracting(Material::messageId).containsExactly("9");
        assertThat(materials.expired(600)).extracting(Material::messageId).containsExactly("9");
        assertThat(materials.deleteExpired(600)).isEqualTo(1);
        materials.markMedia("Cg1", "1", MediaStatus.STORED, "abc");
        assertThat(materials.find("Cg1", "1")).get().extracting(Material::mediaSha256).isEqualTo("abc");
    }
}
