package com.elvischang.ailinebot.service;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.Encoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class WhisperService {

    private final OpenAiAudioTranscriptionModel transcriptionModel;

    public WhisperService(OpenAiAudioTranscriptionModel transcriptionModel) {
        this.transcriptionModel = transcriptionModel;
    }

    public String speechToText(byte[] bytes) throws IOException {
        Path inputFile = Files.createTempFile("input-", ".m4a");
        Files.write(inputFile, bytes);

        Path outputFile = Files.createTempFile("output-", ".mp3");

        try {
            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("libmp3lame");
            audio.setBitRate(128000);
            audio.setChannels(2);
            audio.setSamplingRate(44100);

            // 設置編碼屬性
            EncodingAttributes attrs = new EncodingAttributes();
            attrs.setOutputFormat("mp3");
            attrs.setAudioAttributes(audio);

            // 執行轉換
            Encoder encoder = new Encoder();
            encoder.encode(new MultimediaObject(inputFile.toFile()), outputFile.toFile(), attrs);
            Resource audioResource = new FileSystemResource(outputFile);

            AudioTranscriptionPrompt prompt = new AudioTranscriptionPrompt(
                    audioResource,
                    OpenAiAudioTranscriptionOptions.builder()
                            .responseFormat(OpenAiAudioApi.TranscriptResponseFormat.TEXT)
                            .build()
            );

            AudioTranscriptionResponse res = transcriptionModel.call(prompt);
            return res.getResult().getOutput();
        } catch (Exception e) {
            throw new IOException("音頻轉換失敗: " + e.getMessage(), e);
        } finally {
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
        }
    }
}
