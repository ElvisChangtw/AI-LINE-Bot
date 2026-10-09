package com.elvischang.ailinebot.media;

import java.io.IOException;

public class MediaTooLargeException extends IOException {

    public MediaTooLargeException(long limit) {
        super("media exceeds " + limit + " bytes");
    }
}
