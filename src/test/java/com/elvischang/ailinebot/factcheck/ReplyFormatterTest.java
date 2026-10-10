package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyFormatterTest {

    private static final FactCheckResult.Source TFC =
            new FactCheckResult.Source("https://tfc-taiwan.org.tw/?p=108215", "網傳按1", "台灣事實查核中心", "");

    private static FactCheckResult vaccine(boolean political) {
        return FactCheckResult.factCheck(Verdict.INCORRECT, political, "2021 年就在流傳的舊謠言",
                List.of(new FactCheckResult.Claim("a", Verdict.INCORRECT, "電話裡按 1 或按 2，不會讓手機被控制"),
                        new FactCheckResult.Claim("b", Verdict.INCORRECT, "疾管署說，他們不會打電話問你有沒有打疫苗")),
                true, List.of(TFC));
    }

    @Test
    void formatsTheVaccineRumourLikeTheSpecExample() {
        assertThat(ReplyFormatter.format(vaccine(false), false)).isEqualTo("""
                【查證結果】這是假消息，2021 年就在流傳的舊謠言
                ・電話裡按 1 或按 2，不會讓手機被控制
                ・疾管署說，他們不會打電話問你有沒有打疫苗
                不要點連結、不要給驗證碼和帳號密碼，不放心可以打 165 問
                不用擔心，這則可以不用再轉傳囉
                來源：台灣事實查核中心
                https://tfc-taiwan.org.tw/?p=108215""");
    }

    @Test
    void politicalContentNeverAsksPeopleToStopForwarding() {
        assertThat(ReplyFormatter.format(vaccine(true), false)).doesNotContain(BotReplies.NO_FORWARD_NEEDED);
    }

    @Test
    void correctContentHasNoForwardingAdvice() {
        FactCheckResult correct = FactCheckResult.factCheck(Verdict.CORRECT, false, "", List.of(
                new FactCheckResult.Claim("a", Verdict.CORRECT, "確實有這項補助")), false,
                List.of(new FactCheckResult.Source("https://www.gov.tw/a", "t", "", "2026-09-15")));

        assertThat(ReplyFormatter.format(correct, true)).isEqualTo("""
                【查證結果】內容正確
                ・確實有這項補助
                （我沒有打開訊息裡的連結）
                來源：gov.tw（2026-09-15）
                https://www.gov.tw/a""");
    }

    @Test
    void stripsLinksFromTheModelsOwnWords() {
        FactCheckResult withLinks = FactCheckResult.factCheck(Verdict.INCORRECT, false, "點這裡 https://evil.example/x 領補助",
                List.of(new FactCheckResult.Claim("a", Verdict.INCORRECT, "請加 www.evil.example"),
                        new FactCheckResult.Claim("b", Verdict.INCORRECT, "https://evil.example/only"),
                        new FactCheckResult.Claim("c", Verdict.INCORRECT, "\u3000www.evil.example\u3000")),
                false, List.of(TFC));

        String text = ReplyFormatter.format(withLinks, false);

        assertThat(text).doesNotContain("evil.example")
                .contains("・請加\n")
                .doesNotContain("・\n")
                .doesNotContain("・\u3000")
                .contains(TFC.url());
    }

    @Test
    void insufficientEvidenceSaysSoPlainly() {
        FactCheckResult nothing = FactCheckResult.factCheck(Verdict.INSUFFICIENT_EVIDENCE, false, "", List.of(), false, List.of());
        assertThat(ReplyFormatter.format(nothing, false))
                .isEqualTo("【查證結果】查不到足夠資料，還不能確定\n・" + BotReplies.NOTHING_RELIABLE_FOUND);
    }

    @Test
    void neverExceedsFiveHundredCharacters() {
        String longReason = "很長的理由".repeat(40);
        FactCheckResult verbose = FactCheckResult.factCheck(Verdict.PARTLY_CORRECT, false, "重點",
                List.of(new FactCheckResult.Claim("a", Verdict.CORRECT, longReason),
                        new FactCheckResult.Claim("b", Verdict.CORRECT, longReason),
                        new FactCheckResult.Claim("c", Verdict.CORRECT, longReason)),
                false, List.of(TFC, TFC));

        String text = ReplyFormatter.format(verbose, false);

        assertThat(text.length()).isLessThanOrEqualTo(500);
        assertThat(text).startsWith("【查證結果】部分正確，重點\n・");
    }
}
