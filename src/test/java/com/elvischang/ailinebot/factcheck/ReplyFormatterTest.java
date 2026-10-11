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
                這是假的喔，2021 年就在流傳的舊謠言
                ・電話裡按 1 或按 2，不會讓手機被控制
                ・疾管署說，他們不會打電話問你有沒有打疫苗
                連結先別點，驗證碼和帳號密碼也不要給人，不放心就打 165 問問
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
                這是真的
                ・確實有這項補助
                （訊息裡的連結我沒有點開）
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
                .isEqualTo("這個我查不太到，還不敢說對不對\n・" + BotReplies.NOTHING_RELIABLE_FOUND);
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
        assertThat(text).startsWith("說對了一部分，重點\n・");
    }

    private static final FactCheckResult.Source CWA =
            new FactCheckResult.Source("https://www.cwa.gov.tw/V8/C/W/County/County.html?CID=63", "臺北市天氣", "中央氣象署", "");

    private static FactCheckResult answer(boolean highRisk, List<String> points, List<FactCheckResult.Source> sources) {
        return new FactCheckResult(Verdict.NOT_APPLICABLE, false, "明天台北會下雨，傘記得帶", List.of(), false, sources,
                ReplyMode.ANSWER, highRisk, points);
    }

    @Test
    void formatsAnAnswerLikeTheSpecExample() {
        assertThat(ReplyFormatter.format(answer(true, List.of("降雨機率 70%，出門前看一下天色"), List.of(CWA)), false))
                .isEqualTo("""
                        明天台北會下雨，傘記得帶
                        ・降雨機率 70%，出門前看一下天色
                        來源：中央氣象署
                        https://www.cwa.gov.tw/V8/C/W/County/County.html?CID=63""");
    }

    @Test
    void aHighRiskAnswerWithoutSourcesIsReplacedByTheFixedReply() {
        assertThat(ReplyFormatter.format(answer(true, List.of("降血壓藥配葡萄柚沒問題"), List.of()), false))
                .isEqualTo(BotReplies.HIGH_RISK_NO_SOURCE);
    }

    @Test
    void aLowRiskAnswerWithoutSourcesIsSentAsIs() {
        assertThat(ReplyFormatter.format(answer(false, List.of("滷肉先炒糖色再下醬油"), List.of()), true))
                .isEqualTo("明天台北會下雨，傘記得帶\n・滷肉先炒糖色再下醬油\n" + BotReplies.LINK_NOT_OPENED);
    }

    @Test
    void anAnswerNeverAsksPeopleToStopForwardingAndKeepsSafetyTips() {
        FactCheckResult scamAnswer = new FactCheckResult(Verdict.NOT_APPLICABLE, false, "不要給驗證碼", List.of(), true,
                List.of(CWA), ReplyMode.ANSWER, true, List.of("銀行不會打電話要驗證碼"));

        assertThat(ReplyFormatter.format(scamAnswer, false)).contains(BotReplies.SAFETY_TIP)
                .doesNotContain(BotReplies.NO_FORWARD_NEEDED);
    }

    @Test
    void needMaterialAsksToQuote() {
        FactCheckResult need = new FactCheckResult(Verdict.NOT_APPLICABLE, false, "", List.of(), false, List.of(),
                ReplyMode.NEED_MATERIAL, false, List.of());
        assertThat(ReplyFormatter.format(need, false)).isEqualTo(BotReplies.ASK_TO_QUOTE);
    }

    @Test
    void stripsLinksFromAnswerPointsAndPublishers() {
        FactCheckResult.Source sneaky = new FactCheckResult.Source("https://www.cwa.gov.tw/a", "t", "加 LINE https://evil.example", "");
        FactCheckResult.Source onlyLink = new FactCheckResult.Source("https://www.cwa.gov.tw/b", "t", "www.evil.example", "");

        String text = ReplyFormatter.format(answer(false, List.of("看這裡 https://evil.example/x",
                "小火滷1小時。([evil.example](https://evil.example/r?utm_source=openai))"), List.of(sneaky, onlyLink)), false);

        assertThat(text).doesNotContain("evil.example").contains("・看這裡\n").contains("・小火滷1小時。\n")
                .doesNotContain("](").doesNotContain("・\n")
                .contains("來源：加 LINE\nhttps://www.cwa.gov.tw/a").contains("來源：cwa.gov.tw\nhttps://www.cwa.gov.tw/b");
    }

    @Test
    void anAnswerWhoseWordsWereAllLinksSaysNothingReliableWasFound() {
        FactCheckResult allLinks = new FactCheckResult(Verdict.NOT_APPLICABLE, false, "https://evil.example", List.of(), false,
                List.of(), ReplyMode.ANSWER, false, List.of("www.evil.example"));
        assertThat(ReplyFormatter.format(allLinks, false)).isEqualTo("・" + BotReplies.NOTHING_RELIABLE_FOUND);
    }

    @Test
    void anAnswerNeverExceedsThreeHundredCharacters() {
        String longPoint = "很長的回答".repeat(40);
        String text = ReplyFormatter.format(answer(false, List.of(longPoint, longPoint), List.of(CWA, CWA)), false);

        assertThat(text.length()).isLessThanOrEqualTo(300);
        assertThat(text).startsWith("明天台北會下雨，傘記得帶\n・");
    }

    @Test
    void aHighRiskAnswerKeepsOneSourceWhenItHasToBeShortened() {
        FactCheckResult.Source longGov = new FactCheckResult.Source(
                "https://www.mohw.gov.tw/cp-" + "1".repeat(80) + ".html", "t", "衛生福利部", "2026-09-01");
        FactCheckResult risky = new FactCheckResult(Verdict.NOT_APPLICABLE, false, "葡萄柚先不要配降血壓藥", List.of(), true,
                List.of(longGov, longGov), ReplyMode.ANSWER, true, List.of("很長的說明".repeat(20), "第二點".repeat(20)));

        String text = ReplyFormatter.format(risky, true);

        assertThat(text.length()).isLessThanOrEqualTo(300);
        assertThat(text).contains("來源：衛生福利部（2026-09-01）\n" + longGov.url()).contains(BotReplies.SAFETY_TIP)
                .doesNotContain("第二點");
    }
}
