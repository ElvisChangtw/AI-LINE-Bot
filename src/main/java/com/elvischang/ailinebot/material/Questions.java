package com.elvischang.ailinebot.material;

import java.util.regex.Pattern;

public final class Questions {

    private static final Pattern PUNCTUATION = Pattern.compile("[\\s\\p{P}～~]+");
    // "Is this true?" in its many spellings carries no question of its own; treating them alike keeps answers consistent.
    private static final Pattern PLAIN_CHECK = Pattern.compile(
            "^(請問)?(這|那)?(個|則|篇)?(訊息|消息|內容)?(是不是)?"
                    + "(真的假的|是真的|是假的|真的|假的|可信|能信|可以信|可靠)?(嗎|嘛|麼|ㄇ|呢|吧)?$");

    private Questions() {
    }

    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        String bare = PUNCTUATION.matcher(question).replaceAll("");
        return PLAIN_CHECK.matcher(bare).matches() ? "" : question.strip();
    }
}
