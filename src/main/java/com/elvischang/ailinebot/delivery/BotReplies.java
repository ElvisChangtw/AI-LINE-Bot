package com.elvischang.ailinebot.delivery;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class BotReplies {

    public static final String QUOTE_NOT_FOUND =
            "你引用的那則我找不到耶（可能超過 7 天，或是我進群組之前傳的），再貼一次然後 @ 我就好";
    public static final String QUOTED_BOT_REPLY = "這是我之前回的喔 😊";
    public static final String MEDIA_NOT_READY = "這段影音我還在看，等一分鐘再問我一次喔";
    public static final String MEDIA_TOO_LONG = "這段影音太長了（我最多看 5 分鐘），可以截一張重點畫面給我嗎？";
    public static final String MEDIA_GONE = "這段影音我拿不到了，再傳一次，然後引用它 @ 我就好";
    public static final String MEDIA_UNCLEAR = "這段語音我聽不太出來在說什麼，可以打字跟我說嗎？";
    public static final String IMAGE_GONE = "這張圖我拿不到了，再傳一次，然後引用它 @ 我就好";
    public static final String IMAGE_TOO_LARGE = "這張圖太大了（超過 10MB），我吞不下，傳小一點的給我好嗎？";
    public static final String IMAGINE_NEEDS_PROMPT = "/imagine 後面寫你想畫什麼就好，例如：/imagine 一隻戴帽子的貓";
    public static final String ASK_TO_QUOTE = "你要問哪一則呀？「引用」那則訊息再 @ 我，我就知道了";
    public static final String CANNOT_SEE_MATERIAL = "這則訊息裡的內容（例如影片）我打不開 😅 可以打字說一下它在講什麼，我再幫你查。";
    public static final String JOB_FAILED = "這題我卡住了，再問我一次好嗎？";
    public static final String QUOTA_EXCEEDED = "今天的額度被大家問光了，我先去充電，明天見 🔋";
    public static final String OUT_OF_CREDIT = "AskBot 沒電了，請叫 Elvis 加值 🔋";
    public static final String HIGH_RISK_NO_SOURCE =
            "這題我查不到可靠的資料，不敢亂講。健康、用藥問醫師或藥師最安心；補助、法規可以打 1999 問；天氣看中央氣象署 App 最準。";
    public static final String IMAGE_NOT_READY = "這張圖我還在看，等一分鐘再問我一次喔";
    public static final String DELAYED_PREFIX = "（剛才那題的答案來了）\n";
    private static final int LOOKED_AT_SNIPPET = 12;
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Taipei"));
    public static final String JOIN_NOTICE = """
            大家好，我是 AskBot 👋
            ・有問題就 @ 我，我上網幫你查
            ・看到可疑的訊息，「引用」它再 @ 我最準；沒引用也沒關係，我會往前翻 30 分鐘，猜你在問哪一則
            ・沒人 @ 我的時候，我就安靜待著，不看內容也不插嘴
            ・為了讓大家之後能引用，群組的文字和圖片我會先留 7 天，時間到自動刪掉；語音和影片要有人問我才會下載，轉成文字後一樣留 7 天
            ・有人 @ 我的時候，問題、引用的訊息，還有沒引用時最近 30 分鐘的聊天（含暱稱），會交給 OpenAI 幫忙查和回答；YouTube 連結會交給 Google 的 AI 幫我看影片""";
    public static final String SAFETY_TIP = "連結先別點，驗證碼和帳號密碼也不要給人，不放心就打 165 問問";
    public static final String LINK_NOT_OPENED = "（訊息裡的連結我沒有點開）";
    public static final String NO_FORWARD_NEEDED = "不用擔心，這則可以不用再轉傳囉";
    public static final String NOTHING_RELIABLE_FOUND = "網路上還找不到可靠的說法";

    private BotReplies() {
    }

    public static String lookedAt(String senderName, long sentAt, String text) {
        String snippet = text == null ? "圖片" : text.replaceAll("\\s+", " ").strip();
        if (snippet.length() > LOOKED_AT_SNIPPET) {
            snippet = snippet.substring(0, LOOKED_AT_SNIPPET) + "…";
        }
        String when = CLOCK.format(Instant.ofEpochMilli(sentAt));
        String which = senderName == null ? when + "「" + snippet + "」那則" : senderName + " " + when + " 傳的「" + snippet + "」";
        return "（我猜你問的是 " + which + "，猜錯的話「引用」那則再 @ 我）\n";
    }
}
