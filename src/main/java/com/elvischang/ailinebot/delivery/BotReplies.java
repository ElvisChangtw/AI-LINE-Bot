package com.elvischang.ailinebot.delivery;

public final class BotReplies {

    public static final String QUOTE_NOT_FOUND =
            "找不到你引用的那則訊息（可能超過 7 天，或是在我加入群組之前），請重新貼一次再 @ 我。";
    public static final String QUOTED_BOT_REPLY = "這是我之前的回覆喔。";
    public static final String MEDIA_UNSUPPORTED = "語音和影片之後才支援，目前請傳文字或圖片。";
    public static final String IMAGE_GONE = "這張圖已經拿不到了，請重新傳一次再引用我。";
    public static final String IMAGE_TOO_LARGE = "這張圖太大了（超過 10MB），請傳小一點的圖。";
    public static final String ASK_TO_QUOTE = "請「引用」你要查的那則訊息，再 @ 我。";
    public static final String TOO_MANY_CANDIDATES = "最近有好幾則訊息，請「引用」你要查的那一則再 @ 我。";
    public static final String JOB_FAILED = "這則沒查成功，請再問我一次。";
    public static final String QUOTA_EXCEEDED = "今天的查證次數用完了，明天再問我";
    public static final String IMAGE_NOT_READY = "這張圖還在處理，請過一分鐘再問我一次";
    public static final String DELAYED_PREFIX = "（回覆剛才的問題）\n";
    public static final String JOIN_NOTICE = """
            大家好，我是查證小幫手。
            ・要查證時，請「引用」那則訊息，再 @ 我
            ・沒被 @ 的時候，我不會讀內容、也不會回覆
            ・為了讓大家之後能引用，群組的文字和圖片會暫存 7 天，到期自動刪除
            ・查證時，內容會送到 OpenAI 搜尋與判讀""";
    public static final String SAFETY_TIP = "不要點連結、不要給驗證碼和帳號密碼，不放心可以打 165 問";
    public static final String LINK_NOT_OPENED = "（我沒有打開訊息裡的連結）";
    public static final String NO_FORWARD_NEEDED = "不用擔心，這則可以不用再轉傳囉";
    public static final String NOTHING_RELIABLE_FOUND = "目前查不到可靠的資料可以確認";

    private BotReplies() {
    }
}
