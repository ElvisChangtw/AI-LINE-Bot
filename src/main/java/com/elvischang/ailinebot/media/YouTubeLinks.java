package com.elvischang.ailinebot.media;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class YouTubeLinks {

    private static final Pattern VIDEO = Pattern.compile(
            "https?://(?:(?:www\\.|m\\.)?youtube\\.com/(?:watch\\?(?:[^\\s#]*&)?v=|shorts/|live/|embed/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])",
            Pattern.CASE_INSENSITIVE);

    private YouTubeLinks() {
    }

    public static Optional<String> firstVideoId(String... texts) {
        return Stream.of(texts).filter(Objects::nonNull)
                .map(VIDEO::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .findFirst();
    }

    public static boolean isYouTube(String url) {
        return VIDEO.matcher(url).find();
    }

    public static String watchUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
