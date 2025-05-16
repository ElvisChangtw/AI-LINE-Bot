package com.elvischang.ailinebot.service;

import com.elvischang.ailinebot.helper.LineCommandFactory;
import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import com.linecorp.bot.webhook.model.*;
import io.micrometer.common.util.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

@Service
public class LineMessageService {
    private final LineCommandFactory lineCommandFactory;
    private final MessagingApiBlobClient messagingApiBlobClient;
    private final WhisperService whisperService;

    public LineMessageService(LineCommandFactory lineCommandFactory, MessagingApiBlobClient messagingApiBlobClient, WhisperService whisperService) {
        this.lineCommandFactory = lineCommandFactory;
        this.messagingApiBlobClient = messagingApiBlobClient;
        this.whisperService = whisperService;
    }

    private static final int CACHE_LIMIT = 1000;
    private final Map<String, String> recentTextCache =
            Collections.synchronizedMap(new LinkedHashMap<>(CACHE_LIMIT, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    public void handleTextMessage(MessageEvent event) {
        TextMessageContent msg = (TextMessageContent) event.message();
        String rawUserText = msg.text();
        String quotedId = msg.quotedMessageId();

        recentTextCache.put(msg.id(), msg.text());
        String quotedText = quotedId == null ? null : recentTextCache.get(quotedId);

        boolean isDirectChat = event.source() instanceof UserSource;
        Mention mention = msg.mention();
        boolean botIsMentioned = Optional.ofNullable(mention)
                .map(Mention::mentionees)
                .stream()
                .flatMap(Collection::stream)
                .anyMatch(m -> m instanceof UserMentionee u && Boolean.TRUE.equals(u.isSelf()));

        if (!isDirectChat && !botIsMentioned) {
            return;
        }

        String userText = botIsMentioned
                ? stripSelfMentions(rawUserText, mention)
                : rawUserText;

        String prompt = buildPrompt(userText, quotedText);

        lineCommandFactory.resolve(userText).handle(event, prompt);
    }

    public void handleAudioMessage(MessageEvent event) {

        try (InputStream in = messagingApiBlobClient.getMessageContent(event.message().id())
                .get().body().byteStream()) {

            byte[] audioBytes = in.readAllBytes();

            String text = whisperService.speechToText(audioBytes);
            String prompt = buildPrompt(text, Strings.EMPTY);
            lineCommandFactory.resolve(text).handle(event, prompt);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("抱歉，語音翻譯失敗，請稍後再試！");
        }
    }

    private String buildPrompt(String userText, String quotedText) {
        if (StringUtils.isBlank(quotedText)) {
            return userText;
        }
        return String.format("%s%n---%n%s", userText.trim(), quotedText.trim());
    }

    private String stripSelfMentions(String text, Mention mention) {
        if (mention == null || mention.mentionees() == null) return text;
        StringBuilder sb = new StringBuilder(text);
        mention.mentionees().stream()
                .filter(m -> m instanceof UserMentionee u && Boolean.TRUE.equals(u.isSelf()))
                .sorted(Comparator.comparingInt(Mentionee::index).reversed())
                .forEach(m -> sb.delete(m.index(), m.index() + m.length()));

        return sb.toString().trim();
    }

}