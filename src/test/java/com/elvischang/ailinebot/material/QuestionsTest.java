package com.elvischang.ailinebot.material;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionsTest {

    @Test
    void plainIsItTrueQuestionsAllMeanCheckThis() {
        for (String generic : new String[] {"這是真的嗎", "這是真的？", " 真的嗎?! ", "是真的嗎～", "真的假的", "這個是真的嗎",
                "這可信嗎", "這則訊息是真的嗎", "?", "", null}) {
            assertThat(Questions.normalize(generic)).as(generic).isEmpty();
        }
    }

    @Test
    void realQuestionsAreKeptAsTyped() {
        assertThat(Questions.normalize(" 聽說65歲可以領5000是真的嗎 ")).isEqualTo("聽說65歲可以領5000是真的嗎");
        assertThat(Questions.normalize("那為何你還說是假消息")).isEqualTo("那為何你還說是假消息");
        assertThat(Questions.normalize("明天會下雨嗎")).isEqualTo("明天會下雨嗎");
    }
}
