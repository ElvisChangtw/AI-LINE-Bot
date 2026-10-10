package com.elvischang.ailinebot.factcheck;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CitationFilterTest {

    private static FactCheckResult.Source source(String url) {
        return new FactCheckResult.Source(url, "t", "p", "");
    }

    private static FactCheckResult result(Verdict verdict, String... urls) {
        return FactCheckResult.factCheck(verdict, false, "重點",
                List.of(new FactCheckResult.Claim("c", verdict, "理由")), false,
                java.util.Arrays.stream(urls).map(CitationFilterTest::source).toList());
    }

    @Test
    void normalizesEquivalentUrls() {
        String expected = CitationFilter.normalize("https://tfc-taiwan.org.tw/?p=108215");
        assertThat(CitationFilter.normalize("http://www.TFC-taiwan.org.tw/?p=108215&utm_source=line#top")).isEqualTo(expected);
        assertThat(CitationFilter.normalize("https://tfc-taiwan.org.tw/?fbclid=x&p=108215")).isEqualTo(expected);
        assertThat(CitationFilter.normalize("https://a.gov.tw/news/1/")).isEqualTo(CitationFilter.normalize("https://a.gov.tw/news/1"));
        assertThat(CitationFilter.normalize("https://a.gov.tw/news?id=1")).isNotEqualTo(CitationFilter.normalize("https://a.gov.tw/news?id=2"));
    }

    @Test
    void comparesChineseAndPercentEncodedUrlsDecoded() {
        String raw = CitationFilter.normalize("https://zh.wikipedia.org/wiki/疫苗");
        assertThat(CitationFilter.normalize("https://zh.wikipedia.org/wiki/%E7%96%AB%E8%8B%97")).isEqualTo(raw);
        assertThat(CitationFilter.normalize("https://zh.wikipedia.org/wiki/%e7%96%ab%e8%8b%97")).isEqualTo(raw);
        assertThat(CitationFilter.normalize("https://a.gov.tw/search?q=疫苗"))
                .isEqualTo(CitationFilter.normalize("https://a.gov.tw/search?q=%E7%96%AB%E8%8B%97"));
    }

    @Test
    void ignoresBlankUrls() {
        FactCheckResult filtered = CitationFilter.apply(result(Verdict.INCORRECT, " "), Set.of("", " "));

        assertThat(filtered.overall()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(filtered.sources()).isEmpty();
    }

    @Test
    void keepsOnlySourcesTheModelActuallyConsulted() {
        FactCheckResult filtered = CitationFilter.apply(
                result(Verdict.INCORRECT, "https://tfc-taiwan.org.tw/?p=108215", "https://example.com/made-up"),
                Set.of("https://tfc-taiwan.org.tw/?p=108215&utm_source=line"));

        assertThat(filtered.overall()).isEqualTo(Verdict.INCORRECT);
        assertThat(filtered.sources()).extracting(FactCheckResult.Source::url)
                .containsExactly("https://tfc-taiwan.org.tw/?p=108215");
    }

    @Test
    void downgradesWhenNoSourceSurvives() {
        FactCheckResult filtered = CitationFilter.apply(result(Verdict.CORRECT, "https://example.com/made-up"), Set.of());

        assertThat(filtered.overall()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        assertThat(filtered.claims()).isEmpty();
        assertThat(filtered.headline()).isEmpty();
        assertThat(filtered.sources()).isEmpty();
    }

    @Test
    void opinionNeedsNoSource() {
        FactCheckResult filtered = CitationFilter.apply(result(Verdict.OPINION), Set.of());
        assertThat(filtered.overall()).isEqualTo(Verdict.OPINION);
    }

    @Test
    void survivesMalformedUrls() {
        FactCheckResult filtered = CitationFilter.apply(result(Verdict.INCORRECT, "not a url %%"), Set.of("not a url %%"));
        assertThat(filtered.sources()).hasSize(1);
    }
}
