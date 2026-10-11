package com.elvischang.ailinebot.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeLinksTest {

    @Test
    void findsTheVideoInEveryCommonLinkShape() {
        String[] links = {
                "看這個 https://www.youtube.com/watch?v=dQw4w9WgXcQ 很重要",
                "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=30",
                "https://youtu.be/dQw4w9WgXcQ?si=abc",
                "https://youtube.com/shorts/dQw4w9WgXcQ",
                "https://www.youtube.com/live/dQw4w9WgXcQ",
        };
        for (String link : links) {
            assertThat(YouTubeLinks.firstVideoId(link)).as(link).contains("dQw4w9WgXcQ");
        }
    }

    @Test
    void ignoresOtherSitesAndBrokenIds() {
        assertThat(YouTubeLinks.firstVideoId(null, "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/short", "https://www.youtube.com/watch?v=dQw4w9WgXcQextra")).isEmpty();
        assertThat(YouTubeLinks.firstVideoId(null, "問題沒有連結", "https://youtu.be/dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
        assertThat(YouTubeLinks.videoIdOf("https://www.google.com/url?q=https://youtu.be/dQw4w9WgXcQ")).isEmpty();
        assertThat(YouTubeLinks.videoIdOf("https://youtu.be/dQw4w9WgXcQ?si=x")).contains("dQw4w9WgXcQ");
    }
}
