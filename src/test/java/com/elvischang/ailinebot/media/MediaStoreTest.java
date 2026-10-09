package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.time.Duration;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupImage;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MediaStoreTest extends IntegrationTestSupport {

    @Autowired
    MediaStore mediaStore;

    @Autowired
    MaterialRepository materials;

    private void pendingImage(String id) {
        long now = clock.millis();
        materials.insert(new Material("Cg1", id, MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, now, now + 1_000_000));
    }

    private static void stubContent(String id, int status, byte[] body) {
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/" + id + "/content"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "image/jpeg").withBody(body)));
    }

    private MediaStatus status(String id) {
        return materials.find("Cg1", id).map(Material::mediaStatus).orElseThrow();
    }

    @Test
    void storesTheImageAndItsHash() throws Exception {
        stubContent("200", 200, "abc".getBytes());
        pendingImage("200");

        mediaStore.download("Cg1", "200");

        Material material = materials.find("Cg1", "200").orElseThrow();
        assertThat(material.mediaStatus()).isEqualTo(MediaStatus.STORED);
        assertThat(material.mediaSha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Files.readString(mediaStore.storedFile("Cg1", "200").orElseThrow())).isEqualTo("abc");
    }

    @Test
    void rejectsImagesOverTheSizeLimit() {
        stubContent("201", 200, new byte[2048]);
        pendingImage("201");

        mediaStore.download("Cg1", "201");

        assertThat(status("201")).isEqualTo(MediaStatus.TOO_LARGE);
        assertThat(mediaStore.storedFile("Cg1", "201")).isEmpty();
    }

    @Test
    void retriesNotFoundAndEmptyContentThenGivesUp() {
        stubContent("202", 404, new byte[0]);
        stubContent("203", 200, new byte[0]);
        pendingImage("202");
        pendingImage("203");

        mediaStore.download("Cg1", "202");
        mediaStore.download("Cg1", "203");
        assertThat(status("202")).isEqualTo(MediaStatus.PENDING);
        assertThat(status("203")).isEqualTo(MediaStatus.PENDING);

        mediaStore.download("Cg1", "202");
        mediaStore.download("Cg1", "203");
        assertThat(status("202")).isEqualTo(MediaStatus.FAILED);
        assertThat(status("203")).isEqualTo(MediaStatus.FAILED);
    }

    @Test
    void refusesMessageIdsThatAreNotNumeric() {
        assertThatThrownBy(() -> mediaStore.pathFor("Cg1", "../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesStoredFiles() {
        stubContent("204", 200, "abc".getBytes());
        pendingImage("204");
        mediaStore.download("Cg1", "204");

        mediaStore.delete("Cg1", "204");

        assertThat(mediaStore.storedFile("Cg1", "204")).isEmpty();
    }

    @Test
    void imageWebhookIsDownloadedInTheBackground() throws Exception {
        stubContent("205", 200, "abc".getBytes());

        postWebhook(body(groupImage("e1", "Cg1", "205")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status("205")).isEqualTo(MediaStatus.STORED));
    }
}
