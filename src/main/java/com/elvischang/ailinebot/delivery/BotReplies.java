package com.elvischang.ailinebot.delivery;

public final class BotReplies {

    public static final String QUOTE_NOT_FOUND =
            "找不到你引用的那則訊息（可能超過 7 天，或是在我加入群組之前），請重新貼一次再 @ 我。";
    public static final String QUOTED_BOT_REPLY = "這是我之前的回覆喔。";
    public static final String MEDIA_UNSUPPORTED = "語音和影片之後才支援，目前請傳文字或圖片。";
    public static final String IMAGE_GONE = "這張圖已經拿不到了，請重新傳一次再引用我。";
    public static final String IMAGE_TOO_LARGE = "這張圖太大了（超過 10MB），請傳小一點的圖。";
    public static final String IMAGINE_NEEDS_PROMPT = "請在 /imagine 後面寫你想畫的內容，例如：/imagine 一隻戴帽子的貓";
    public static final String ASK_TO_QUOTE = "請「引用」你要查的那則訊息，再 @ 我。";
    public static final String TOO_MANY_CANDIDATES = "最近有好幾則訊息，請「引用」你要查的那一則再 @ 我。";
    public static final String JOB_FAILED = "這題我卡住了，再問我一次好嗎？";
    public static final String QUOTA_EXCEEDED = "今天的額度被大家問光了，我先去充電，明天見 🔋";
    public static final String OUT_OF_CREDIT = "小幫手沒電了，請叫 Elvis 加值 🔋";
    public static final String HIGH_RISK_NO_SOURCE =
            "這題我查不到可靠的資料，不敢亂回答。健康、用藥請問醫師或藥師；補助、法規可以打 1999 問。";
    public static final String IMAGE_NOT_READY = "這張圖還在處理，請過一分鐘再問我一次";
    public static final String DELAYED_PREFIX = "（回覆剛才的問題）\n";
    public static final String JOIN_NOTICE = """
            大家好，我是小幫手。
            ・有問題可以直接 @ 我，我會上網查資料回答
            ・要查證某則訊息，請「引用」那則訊息，再 @ 我
            ・沒被 @ 的時候，我不會讀內容、也不會回覆
            ・為了讓大家之後能引用，群組的文字和圖片會暫存 7 天，到期自動刪除
            ・你問的問題和引用的訊息，會送到 OpenAI 搜尋與回答""";
    public static final String SAFETY_TIP = "不要點連結、不要給驗證碼和帳號密碼，不放心可以打 165 問";
    public static final String LINK_NOT_OPENED = "（我沒有打開訊息裡的連結）";
    public static final String NO_FORWARD_NEEDED = "不用擔心，這則可以不用再轉傳囉";
    public static final String NOTHING_RELIABLE_FOUND = "目前查不到可靠的資料可以確認";

    private BotReplies() {
    }
}
