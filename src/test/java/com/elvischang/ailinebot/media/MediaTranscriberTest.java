package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class MediaTranscriberTest extends IntegrationTestSupport {

    private static final String TRANSCRIBE = "/v1/audio/transcriptions";

    @Autowired
    MediaTranscriber transcriber;

    @Autowired
    MaterialRepository materials;

    @Autowired
    MediaStore mediaStore;

    private Material put(String id, MaterialKind kind, MediaStatus status) {
        Material m = new Material("Cg1", id, kind, null, false, status, null, 0, clock.millis(), clock.millis() + 1_000_000);
        materials.insert(m, "Uuser", 6_000L);
        return m;
    }

    private static void stubContent(String id, String transcoding, String mime, byte[] body) {
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/" + id + "/content/transcoding"))
                .willReturn(okJson("{\"status\":\"" + transcoding + "\"}")));
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/" + id + "/content"))
                .willReturn(aResponse().withHeader("Content-Type", mime).withBody(body)));
    }

    private static void stubTranscript(String text) {
        LINE.stubFor(post(urlEqualTo(TRANSCRIBE))
                .willReturn(okJson("{\"text\":\"" + text + "\",\"usage\":{\"type\":\"duration\",\"seconds\":6}}")));
    }

    @Test
    void transcribesAVoiceMessageOnceAndKeepsTheText() {
        Material voice = put("300", MaterialKind.AUDIO, MediaStatus.NONE);
        stubContent("300", "succeeded", "audio/x-m4a", new byte[] {1, 2, 3});
        stubTranscript("健保卡要停用了");

        MediaTranscriber.Prepared first = transcriber.prepare(voice);
        MediaTranscriber.Prepared second = transcriber.prepare(materials.find("Cg1", "300").orElseThrow());

        assertThat(first).isInstanceOfSatisfying(MediaTranscriber.Prepared.Ready.class,
                r -> assertThat(r.material().text()).isEqualTo("健保卡要停用了"));
        assertThat(second).isEqualTo(first);
        LINE.verify(1, postRequestedFor(urlEqualTo(TRANSCRIBE))
                .withRequestBodyPart(aMultipart("file").withHeader("Content-Disposition", containing(".m4a")).build())
                .withRequestBodyPart(aMultipart("model").withBody(equalTo("gpt-transcribe")).build()));
        assertThat(jdbc.sql("SELECT micro_usd FROM spend_log").query(Long.class).list()).containsExactly(450L);
    }

    @Test
    void aVideoKeepsItsTranscriptAndPreviewFrame() {
        Material video = put("301", MaterialKind.VIDEO, MediaStatus.NONE);
        stubContent("301", "succeeded", "video/mp4", new byte[] {4, 5, 6});
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/301/content/preview"))
                .willReturn(aResponse().withHeader("Content-Type", "image/jpeg").withBody(new byte[] {(byte) 0xFF, (byte) 0xD8, 9})));
        stubTranscript("吃苦瓜可以治癌症");

        assertThat(transcriber.prepare(video)).isInstanceOf(MediaTranscriber.Prepared.Ready.class);

        Material stored = materials.find("Cg1", "301").orElseThrow();
        assertThat(stored.text()).isEqualTo("吃苦瓜可以治癌症");
        assertThat(stored.mediaStatus()).isEqualTo(MediaStatus.STORED);
        assertThat(mediaStore.storedFile("Cg1", "301")).isPresent();
        LINE.verify(1, postRequestedFor(urlEqualTo(TRANSCRIBE))
                .withRequestBodyPart(aMultipart("file").withHeader("Content-Disposition", containing(".mp4")).build()));
    }

    @Test
    void reportsWhyMediaCannotBeUsedWithoutCallingOpenAi() {
        stubContent("310", "processing", "audio/x-m4a", new byte[] {1});
        stubContent("311", "failed", "audio/x-m4a", new byte[] {1});

        assertThat(transcriber.prepare(put("310", MaterialKind.AUDIO, MediaStatus.NONE))).isEqualTo(new MediaTranscriber.Prepared.NotReady());
        assertThat(transcriber.prepare(put("311", MaterialKind.AUDIO, MediaStatus.NONE))).isEqualTo(new MediaTranscriber.Prepared.Gone());
        assertThat(transcriber.prepare(put("312", MaterialKind.VIDEO, MediaStatus.TOO_LARGE))).isEqualTo(new MediaTranscriber.Prepared.TooLong());
        assertThat(transcriber.prepare(put("313", MaterialKind.VIDEO, MediaStatus.FAILED))).isEqualTo(new MediaTranscriber.Prepared.Gone());
        LINE.verify(0, postRequestedFor(urlEqualTo(TRANSCRIBE)));
    }

    @Test
    void aVoiceMessageWithNoWordsIsUnclear() {
        stubContent("320", "succeeded", "audio/x-m4a", new byte[] {1});
        stubTranscript("");

        assertThat(transcriber.prepare(put("320", MaterialKind.AUDIO, MediaStatus.NONE))).isEqualTo(new MediaTranscriber.Prepared.Unclear());
    }

    @Test
    void aMissingPreviewFrameStillKeepsTheTranscript() {
        Material video = put("330", MaterialKind.VIDEO, MediaStatus.NONE);
        stubContent("330", "succeeded", "video/mp4", new byte[] {4, 5, 6});
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/330/content/preview")).willReturn(aResponse().withStatus(500)));
        stubTranscript("吃苦瓜可以治癌症");

        assertThat(transcriber.prepare(video)).isInstanceOf(MediaTranscriber.Prepared.Ready.class);
        transcriber.prepare(materials.find("Cg1", "330").orElseThrow());

        assertThat(materials.find("Cg1", "330").orElseThrow().text()).isEqualTo("吃苦瓜可以治癌症");
        assertThat(mediaStore.storedFile("Cg1", "330")).isEmpty();
        LINE.verify(1, postRequestedFor(urlEqualTo(TRANSCRIBE)));
    }

    @Test
    void twoAsksAtOnceTranscribeTheClipOnce() throws Exception {
        Material voice = put("340", MaterialKind.AUDIO, MediaStatus.NONE);
        stubContent("340", "succeeded", "audio/x-m4a", new byte[] {1});
        LINE.stubFor(post(urlEqualTo(TRANSCRIBE)).willReturn(okJson("{\"text\":\"慢慢轉\"}").withFixedDelay(300)));

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var first = pool.submit(() -> transcriber.prepare(voice));
        var second = pool.submit(() -> transcriber.prepare(voice));
        assertThat(first.get()).isInstanceOf(MediaTranscriber.Prepared.Ready.class);
        assertThat(second.get()).isInstanceOf(MediaTranscriber.Prepared.Ready.class);
        pool.shutdown();

        LINE.verify(1, postRequestedFor(urlEqualTo(TRANSCRIBE)));
    }

    @Test
    void aLineHiccupIsRetriedButMissingContentIsGone() {
        Material flaky = put("350", MaterialKind.AUDIO, MediaStatus.NONE);
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/350/content/transcoding")).willReturn(aResponse().withStatus(503)));
        Material missing = put("351", MaterialKind.AUDIO, MediaStatus.NONE);
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/351/content/transcoding")).willReturn(aResponse().withStatus(404)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transcriber.prepare(flaky)).isInstanceOf(IllegalStateException.class);
        assertThat(materials.find("Cg1", "350").orElseThrow().mediaStatus()).isEqualTo(MediaStatus.NONE);
        assertThat(transcriber.prepare(missing)).isEqualTo(new MediaTranscriber.Prepared.Gone());
    }

    @Test
    void aClipTooBigToSendIsTooLongNotSilent() {
        Material video = put("360", MaterialKind.VIDEO, MediaStatus.NONE);
        stubContent("360", "succeeded", "video/mp4", new byte[40_000]);

        assertThat(transcriber.prepare(video)).isEqualTo(new MediaTranscriber.Prepared.TooLong());
        assertThat(materials.find("Cg1", "360").orElseThrow().mediaStatus()).isEqualTo(MediaStatus.TOO_LARGE);
        LINE.verify(0, postRequestedFor(urlEqualTo(TRANSCRIBE)));
    }

    @Test
    void aRealVideoKeepsFramesFromTheWholeClipInsteadOfLinesPreview() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0, "ffmpeg not installed");
        Material video = put("370", MaterialKind.VIDEO, MediaStatus.NONE);
        byte[] clip = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(getClass().getResource("/video/tiny.mp4").toURI()));
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/370/content/transcoding")).willReturn(okJson("{\"status\":\"succeeded\"}")));
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/370/content"))
                .willReturn(aResponse().withHeader("Content-Type", "video/mp4").withBody(clip)));
        stubTranscript("嗶");

        assertThat(transcriber.prepare(video)).isInstanceOf(MediaTranscriber.Prepared.Ready.class);

        byte[] stored = java.nio.file.Files.readAllBytes(mediaStore.storedFile("Cg1", "370").orElseThrow());
        assertThat(stored[0]).isEqualTo((byte) 0xFF);
        assertThat(stored.length).isGreaterThan(1_000);
        LINE.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/v2/bot/message/370/content/preview")));
        assertThat(java.nio.file.Files.list(mediaStore.scratchDir()).toList()).isEmpty();
    }
}
