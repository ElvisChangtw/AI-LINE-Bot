package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.factcheck.FactCheckProperties;
import com.elvischang.ailinebot.factcheck.OutOfCreditException;
import com.elvischang.ailinebot.factcheck.SpendRepository;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.audio.transcriptions.TranscriptionCreateResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

@Component
public class AudioTranscriber {

    private static final String INSUFFICIENT_QUOTA = "insufficient_quota";
    private static final long SECONDS_PER_MINUTE = 60;
    private static final int FIRST_SERVER_ERROR_STATUS = 500;

    private final TranscribeProperties props;
    private final SpendRepository spending;
    private final Clock clock;
    private final OpenAIClient client;

    public AudioTranscriber(TranscribeProperties props, FactCheckProperties openAi, SpendRepository spending, Clock clock) {
        this.props = props;
        this.spending = spending;
        this.clock = clock;
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(openAi.baseUrl())
                .apiKey(openAi.apiKey())
                .timeout(props.requestTimeout())
                .maxRetries(0)
                .build();
    }

    @PreDestroy
    void close() {
        client.close();
    }

    // The file name's extension tells OpenAI the format, so the caller passes a file named .m4a or .mp4.
    public String transcribe(Path file, long expectedSeconds) {
        TranscriptionCreateParams params = TranscriptionCreateParams.builder()
                .file(file)
                .model(props.model())
                .responseFormat(AudioResponseFormat.JSON)
                .build();
        TranscriptionCreateResponse response;
        try {
            response = client.audio().transcriptions().create(params);
        } catch (OpenAIIoException e) {
            spending.record(clock.millis(), cost(expectedSeconds));
            throw e;
        } catch (OpenAIServiceException e) {
            if (e.code().filter(INSUFFICIENT_QUOTA::equals).isPresent()) {
                throw new OutOfCreditException(e);
            }
            if (e.statusCode() >= FIRST_SERVER_ERROR_STATUS) {
                spending.record(clock.millis(), cost(expectedSeconds));
            }
            throw e;
        }
        Transcription transcription;
        try {
            transcription = response.asTranscription();
        } catch (RuntimeException e) {
            // OpenAI already did the work, so count it even though the answer is unusable.
            spending.record(clock.millis(), cost(expectedSeconds));
            throw e;
        }
        long billedSeconds = transcription._usage().asKnown()
                .filter(Transcription.Usage::isDuration)
                .flatMap(u -> u.asDuration()._seconds().asKnown())
                .map(s -> (long) Math.ceil(s))
                .orElse(expectedSeconds);
        spending.record(clock.millis(), cost(billedSeconds));
        return Optional.ofNullable(transcription._text().asString().orElse(null)).map(String::strip).orElse("");
    }

    private long cost(long seconds) {
        return Math.ceilDiv(seconds * props.microUsdPerMinute(), SECONDS_PER_MINUTE);
    }
}
