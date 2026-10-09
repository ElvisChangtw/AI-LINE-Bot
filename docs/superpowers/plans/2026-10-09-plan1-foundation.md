# Plan 1：地基（webhook、持久化、工作、送出）實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 webhook 改成「驗簽 → SQLite 持久化 → 背景工作 → 只用 reply 送出（來不及就搭下一則訊息）」，查證本身先用 placeholder，跑通整條路。

**Architecture:** 單一 Spring Boot。controller 驗簽後在一個 SQLite transaction 裡去重、存素材、建 job；commit 後由 `@TransactionalEventListener` + `@Async` 立刻執行。job 列是恢復紀錄，`JobRecoveryScheduler` 補救卡住的工作。送出只用 reply：超過 45 秒的結果標 `WAITING_NEXT`，同群組下一則訊息進來時由它的 reply token 帶出去。

**Tech Stack:** Java 21、Spring Boot 3.3.0、Spring `JdbcClient`、SQLite（`sqlite-jdbc`）、Flyway 10、LINE SDK 9.7.0、JUnit 5、AssertJ、WireMock 3、Awaitility。

**Spec:** `docs/superpowers/specs/2026-10-09-family-fact-check-bot-design.md`（§4–§7、§10.2、§12、§14、§15 中屬於地基的部分）

本計畫是三份中的第一份：Plan 2 做真正的查證（OpenAI、來源過濾、排版、快取、用量），Plan 3 做部署與 CI/CD。Plan 1 完成後，bot 收到 @ 會回 placeholder 文字。

## 與 spec 的細部差異（實作時以本計畫為準，spec 會同步更新）

- job 多兩種 kind：`NOTICE`（固定文字，例如「請引用」、加入群組說明）和 `DELIVERY`（非 @ 訊息被拿來搭載待送結果時建立，自己沒有內容）。
- `send_state` 多 `CARRIED`（已掛到某個 carrier job 上，等它送出）和 `NOTHING_TO_SEND`。
- 不做「同群組一次只跑一件」：job 之間沒有共用可寫的 context，唯一的共享寫入（認領待送結果）本身是原子的。為了讓順序錯開時也看得懂，每則結果都用 `quoteToken` 引用觸發的那則 @ 訊息。
- job 用 `material_message_id`（要查的那則素材）取代 spec 的 `quoted_message_id`。素材在 ingest 時就決定好。
- `/imagine` 在素材判斷之前先檢查，避免長 prompt 被當成「自帶素材」。

## Global Constraints

- 版本不升級：Java 21、Spring Boot 3.3.0、Spring AI 1.0.0-M8、LINE SDK `linebot.version` 9.7.0。
- Maven：`~/.m2/settings.xml` 指向公司的 Sportybet Nexus，本專案連不上也不該用。**所有 mvn 指令都用** `export MVN="mvn -s /private/tmp/claude-501/-Users-elvischang-dagoo/da52d601-39bf-48a2-80c3-90d63788bec6/scratchpad/central-settings.xml"` 然後 `$MVN ...`。不要修改 `~/.m2/settings.xml`。
- `src/main` 裡不得出現任何 push API 呼叫（`pushMessage`、`multicast`、`broadcast`）。所有測試的 `@AfterEach` 會驗證 WireMock 收到的 push 請求數為 0。
- 測試不用真 key、不連外網。LINE API 一律走 WireMock。
- 資料存取只用 `JdbcClient`，不用 JPA。不能在 transaction 裡呼叫模型或 LINE API。
- Hikari pool 大小刻意設 1：所有 DB 存取在 app 內排隊。log 裡看到等待連線是預期行為，不要把 pool 調大（SQLite 只有一個 writer）。
- subagent 每開一個新 shell 都要重新 `export MVN=...`（見上一條 Maven 設定）。
- 只存群組（`GroupSource`）和多人聊天室（`RoomSource`）的訊息；1:1 不存、不回。不存 LINE `userId` 和顯示名稱。
- log 不得包含：訊息文字、回覆全文、reply token、quote token、任何 secret。群組只記 `Hashes.shortHash(groupId)`。
- 給使用者看的文字只能放在 `BotReplies`（繁體中文）。
- 程式風格：沿用 repo 的明確建構子注入；資料用 record；註解只准單行、不寫 ticket 編號；SLF4J log。
- 每個 task 結束 commit 一次，Conventional Commits，不加 `Co-Authored-By`，不 push。branch：`feat/fact-check-bot`。
- 判斷測試結果只看這次 maven 的輸出，不看 `target/surefire-reports` 的舊檔。

## Review Focus

1. LINE console 的「Verify」按鈕會送 `events: []` 的 webhook：必須回 200。→ Task 3 測試。
2. 同一個 webhook 裡混了未知的 event 或訊息類型：其他正常的 event 照常處理，不能整批失敗。→ Task 8 測試。
3. mention 前面有 emoji，或 LINE 給的 index 超出字串長度：不能丟 exception，去掉 mention 的結果要對。→ Task 4 測試。
4. 只打「@bot」沒引用時，bot 自己前一則回覆不能被當成候選素材。→ Task 7 測試。
5. 圖片下載回 404 或空內容：重試到上限後標 `FAILED`，不能讓 worker 掛掉；之後引用這張圖要回「拿不到了」。→ Task 11 測試（加上 Task 7 的 FAILED 測試）。

## 檔案結構

```
src/main/java/com/elvischang/ailinebot/
  AiLineBotApplication.java                 modify：@ConfigurationPropertiesScan
  config/BotProperties.java                 create：bot.* 設定
  config/ClockConfig.java                   create：Clock bean
  config/AsyncConfig.java                   create：jobExecutor、mediaExecutor
  config/SchedulingConfig.java              create：@EnableScheduling（可用 bot.scheduling-enabled 關掉）
  config/LineConfig.java                    delete（改用 SDK autoconfig，才吃得到 endpoint 設定）
  controller/LineBotController.java         modify：原始 body 驗簽 → ingest
  webhook/LineSignatureVerifier.java        create
  webhook/WebhookIngestService.java         create
  line/LineSources.java                     create：取 groupId／roomId
  line/Mentions.java                        create：判斷與去掉 @bot
  line/Hashes.java                          create：sha256、shortHash
  store/Material.java, MaterialKind.java, MediaStatus.java, MaterialRepository.java      create
  store/Job.java, NewJob.java, JobKind.java, JobStatus.java, SendState.java, JobRepository.java  create
  store/InboundEventRepository.java         create
  material/Trigger.java, Resolution.java, MaterialResolver.java                         create
  job/JobReadyEvent.java, ImageReceivedEvent.java, JobEventListener.java, JobRunner.java create
  job/JobRecoveryScheduler.java, CleanupScheduler.java                                  create
  delivery/ReplySender.java                 create
  delivery/BotReplies.java                  create
  factcheck/FactCheckService.java, PlaceholderFactCheckService.java                      create
  media/MediaStore.java, MediaTooLargeException.java                                     create
  service/LineMessageService.java, OllamaService.java, OpenAIService.java                delete（Task 13）
  helper/*                                  delete（Task 13）
src/main/resources/
  application.yaml                          modify
  db/migration/V1__init.sql                 create
src/test/java/com/elvischang/ailinebot/
  support/IntegrationTestSupport.java, TestBeans.java, MutableClock.java, Webhooks.java, RecordingFactCheckService.java
  各 task 的測試
src/test/resources/application-test.yaml    create
```

---

### Task 1：測試不用真 key 也能跑

**Files:**
- Create: `src/test/resources/application-test.yaml`
- Modify: `src/test/java/com/elvischang/ailinebot/AiLineBotApplicationTests.java`

**Interfaces:**
- Produces：`test` profile，之後所有測試都用它。

- [ ] **Step 1：確認現在的測試在沒有 env 時會失敗**

Run: `cd ~/Java_Projects/AI-LINE-Bot && export MVN="mvn -s /private/tmp/claude-501/-Users-elvischang-dagoo/da52d601-39bf-48a2-80c3-90d63788bec6/scratchpad/central-settings.xml" && env -u OPENAI_API_KEY -u LINE_CHANNEL_TOKEN -u LINE_CHANNEL_SECRET $MVN test -Dtest=AiLineBotApplicationTests`
Expected: FAIL，錯誤包含 `Could not resolve placeholder`。

- [ ] **Step 2：建立 test profile**

`src/test/resources/application-test.yaml`：

```yaml
line:
  bot:
    channel-token: test-token
    channel-secret: test-secret
    read-timeout: 2s
spring:
  ai:
    openai:
      api-key: test-key
      chat:
        options:
          model: test-model
          temperature: 0
```

`AiLineBotApplicationTests.java`：

```java
package com.elvischang.ailinebot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiLineBotApplicationTests {

    @Test
    void contextLoads() {
    }

}
```

- [ ] **Step 3：確認通過**

Run: `env -u OPENAI_API_KEY -u LINE_CHANNEL_TOKEN -u LINE_CHANNEL_SECRET $MVN test -Dtest=AiLineBotApplicationTests`
Expected: `Tests run: 1, Failures: 0, Errors: 0` 且 BUILD SUCCESS。

- [ ] **Step 4：Commit**

```bash
git add src/test
git commit -m "test: run context test with a test profile instead of real keys"
```

---

### Task 2：SQLite、Flyway、設定與測試基礎

**Files:**
- Modify: `pom.xml`、`src/main/resources/application.yaml`、`src/main/java/com/elvischang/ailinebot/AiLineBotApplication.java`、`src/test/resources/application-test.yaml`
- Create: `src/main/resources/db/migration/V1__init.sql`、`config/BotProperties.java`、`config/ClockConfig.java`
- Create（test）：`support/MutableClock.java`、`support/TestBeans.java`、`support/IntegrationTestSupport.java`、`store/SchemaMigrationTest.java`

**Interfaces:**
- Produces：`BotProperties`（全部欄位見下方）、`Clock` bean、`IntegrationTestSupport`（欄位 `jdbc`、`clock`、`mvc`、靜態 `LINE` WireMockServer、`stubReplyOk(String sentMessageId)`、`postWebhook(String body)`）、`MutableClock`（`set(Instant)`、`advance(Duration)`）。

- [ ] **Step 1：加 dependency**

`pom.xml` 的 `<dependencies>` 加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.46.1.0</version>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.linecorp.bot</groupId>
            <artifactId>line-bot-parser</artifactId>
            <version>${linebot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>3.13.1</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/support/MutableClock.java`：

```java
package com.elvischang.ailinebot.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public void advance(Duration duration) {
        now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
```

`support/TestBeans.java`：

```java
package com.elvischang.ailinebot.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

@TestConfiguration
public class TestBeans {

    public static final Instant START = Instant.parse("2026-10-10T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
```

`support/IntegrationTestSupport.java`：

```java
package com.elvischang.ailinebot.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestBeans.class)
public abstract class IntegrationTestSupport {

    protected static final WireMockServer LINE = new WireMockServer(options().dynamicPort());

    static {
        LINE.start();
    }

    @DynamicPropertySource
    static void lineEndpoints(DynamicPropertyRegistry registry) {
        registry.add("line.bot.api-end-point", () -> LINE.baseUrl() + "/");
        registry.add("line.bot.blob-end-point", () -> LINE.baseUrl() + "/");
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected MockMvc mvc;

    @BeforeEach
    void resetSharedState() {
        jdbc.sql("DELETE FROM job").update();
        jdbc.sql("DELETE FROM material").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        LINE.resetAll();
        clock.set(TestBeans.START);
    }

    @AfterEach
    void neverPush() {
        LINE.verify(0, postRequestedFor(urlPathMatching("/v2/bot/message/(push|multicast|broadcast|narrowcast).*")));
    }

    protected static void stubReplyOk(String sentMessageId) {
        LINE.stubFor(post(urlEqualTo("/v2/bot/message/reply")).atPriority(5)
                .willReturn(okJson("{\"sentMessages\":[{\"id\":\"" + sentMessageId + "\",\"quoteToken\":\"q" + sentMessageId + "\"}]}")));
    }

    protected void postWebhook(String body) throws Exception {
        mvc.perform(post("/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }
}
```

`postWebhook` 用到 Task 3 才建立的 `Webhooks`。為了讓這個 task 先編譯過，Step 2 先建一個只有 `sign` 的版本，Task 3 再補完整：

`support/Webhooks.java`：

```java
package com.elvischang.ailinebot.support;

import com.linecorp.bot.parser.LineSignatureValidator;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Webhooks {

    public static final String CHANNEL_SECRET = "test-secret";

    private static final LineSignatureValidator SIGNER =
            new LineSignatureValidator(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8));

    private Webhooks() {
    }

    public static String sign(String body) {
        return Base64.getEncoder().encodeToString(SIGNER.generateSignature(body.getBytes(StandardCharsets.UTF_8)));
    }
}
```

`src/test/java/com/elvischang/ailinebot/store/SchemaMigrationTest.java`：

```java
package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends IntegrationTestSupport {

    @Autowired
    BotProperties props;

    @Test
    void createsTablesInWalMode() {
        var tables = jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table'").query(String.class).list();
        assertThat(tables).contains("inbound_event", "material", "job", "flyway_schema_history");
        assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
    }

    @Test
    void bindsBotProperties() {
        assertThat(props.replyDeadline()).isEqualTo(Duration.ofSeconds(45));
        assertThat(props.materialTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(props.maxMessagesPerReply()).isEqualTo(5);
        assertThat(props.mediaMaxBytes()).isEqualTo(1024);
    }
}
```

- [ ] **Step 3：確認失敗**

Run: `$MVN test -Dtest=SchemaMigrationTest`
Expected: 編譯失敗，`BotProperties` 不存在。

- [ ] **Step 4：實作**

`src/main/resources/db/migration/V1__init.sql`：

```sql
CREATE TABLE inbound_event (
  webhook_event_id TEXT PRIMARY KEY,
  received_at      INTEGER NOT NULL,
  is_redelivery    INTEGER NOT NULL
);

CREATE TABLE material (
  group_id       TEXT    NOT NULL,
  message_id     TEXT    NOT NULL,
  kind           TEXT    NOT NULL,
  text           TEXT,
  is_bot_trigger INTEGER NOT NULL DEFAULT 0,
  media_status   TEXT    NOT NULL DEFAULT 'NONE',
  media_sha256   TEXT,
  media_attempts INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  expires_at     INTEGER NOT NULL,
  PRIMARY KEY (group_id, message_id)
);
CREATE INDEX material_recent ON material(group_id, created_at);
CREATE INDEX material_expiry ON material(expires_at);

CREATE TABLE job (
  id                  TEXT PRIMARY KEY,
  kind                TEXT    NOT NULL,
  group_id            TEXT    NOT NULL,
  trigger_message_id  TEXT,
  material_message_id TEXT,
  user_question       TEXT,
  reply_token         TEXT,
  quote_token         TEXT,
  received_at         INTEGER NOT NULL,
  status              TEXT    NOT NULL,
  attempts            INTEGER NOT NULL DEFAULT 0,
  lease_until         INTEGER,
  result_text         TEXT,
  send_state          TEXT    NOT NULL DEFAULT 'NOT_SENT',
  carrier_job_id      TEXT,
  waiting_since       INTEGER,
  verdict             TEXT,
  error_code          TEXT,
  input_tokens        INTEGER,
  output_tokens       INTEGER,
  search_calls        INTEGER,
  cost_micro_usd      INTEGER,
  created_at          INTEGER NOT NULL,
  finished_at         INTEGER
);
CREATE INDEX job_status ON job(status, created_at);
CREATE INDEX job_group_send ON job(group_id, send_state, waiting_since);
CREATE INDEX job_carrier ON job(carrier_job_id);
```

`config/BotProperties.java`：

```java
package com.elvischang.ailinebot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        Duration replyDeadline,
        Duration replyTimeout,
        Duration materialTtl,
        Duration waitingTtl,
        Duration recentWindow,
        int selfMaterialMinChars,
        Duration jobLease,
        int jobMaxAttempts,
        Duration staleAfter,
        Path mediaDir,
        long mediaMaxBytes,
        int mediaMaxAttempts,
        Duration mediaDownloadTimeout,
        Duration jobRetention,
        int maxMessagesPerReply) {
}
```

`config/ClockConfig.java`：

```java
package com.elvischang.ailinebot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

`AiLineBotApplication.java` 在 `@SpringBootApplication` 下面加 `@ConfigurationPropertiesScan`（import `org.springframework.boot.context.properties.ConfigurationPropertiesScan`）。

`application.yaml`：在既有的 `spring:` 底下加 `datasource` 與 `flyway`（不要再開第二個 `spring:` key），並在檔尾加 `bot:`：

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${BOT_DB_PATH:/data/app.db}?journal_mode=WAL
    hikari:
      maximum-pool-size: 1
  flyway:
    locations: classpath:db/migration

bot:
  reply-deadline: 45s
  reply-timeout: 15s
  material-ttl: 7d
  waiting-ttl: 24h
  recent-window: 10m
  self-material-min-chars: 20
  job-lease: 3m
  job-max-attempts: 3
  stale-after: 30s
  media-dir: ${BOT_MEDIA_DIR:/data/media}
  media-max-bytes: 10485760
  media-max-attempts: 3
  media-download-timeout: 30s
  job-retention: 90d
  max-messages-per-reply: 5
  recovery-interval-ms: 60000
  cleanup-cron: "0 30 3 * * *"
  scheduling-enabled: true
```

`application-test.yaml` 加：

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${java.io.tmpdir}/ailinebot-test-${random.uuid}.db?journal_mode=WAL
bot:
  media-dir: ${java.io.tmpdir}/ailinebot-test-media
  media-max-bytes: 1024
  media-max-attempts: 2
  job-max-attempts: 2
  scheduling-enabled: false
```

（`application-test.yaml` 原本已經有一個 `spring:`，把 `datasource` 併進去。）

- [ ] **Step 5：確認通過**

Run: `$MVN test -Dtest='SchemaMigrationTest,AiLineBotApplicationTests'`
Expected: `Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 6：Commit**

```bash
git add pom.xml src
git commit -m "feat: add SQLite persistence, Flyway schema, and bot settings"
```

---

### Task 3：webhook 用原始 body 驗簽

**Files:**
- Create: `webhook/LineSignatureVerifier.java`
- Modify: `controller/LineBotController.java`、`support/Webhooks.java`
- Test: `controller/LineBotControllerSignatureTest.java`

**Interfaces:**
- Produces：`LineSignatureVerifier.isValid(byte[] body, String signature): boolean`；`Webhooks` 的 JSON 產生器：`body(String... events)`、`parse(String body): List<Event>`、`groupText(eventId, groupId, messageId, text)`、`groupMention(eventId, groupId, messageId, textAfterMention, quotedMessageIdOrNull)`、`groupImage(eventId, groupId, messageId)`、`groupAudio(eventId, groupId, messageId)`、`userText(eventId, userId, messageId, text)`、`join(eventId, groupId)`、`unknownEvent(eventId)`。每個 event 的 reply token 是 `"rt-" + eventId`，每則訊息的 quote token 是 `"qt-" + messageId`。

- [ ] **Step 1：補完 `Webhooks`**

`support/Webhooks.java` 整個換成：

```java
package com.elvischang.ailinebot.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.parser.LineSignatureValidator;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public final class Webhooks {

    public static final String CHANNEL_SECRET = "test-secret";

    private static final LineSignatureValidator SIGNER =
            new LineSignatureValidator(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper LINE_MAPPER = ModelObjectMapper.createNewObjectMapper();

    private Webhooks() {
    }

    public static String sign(String body) {
        return Base64.getEncoder().encodeToString(SIGNER.generateSignature(body.getBytes(StandardCharsets.UTF_8)));
    }

    public static String body(String... events) {
        return "{\"destination\":\"Ubot\",\"events\":[" + String.join(",", events) + "]}";
    }

    public static List<Event> parse(String body) {
        try {
            return LINE_MAPPER.readValue(body, CallbackRequest.class).events();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String groupText(String eventId, String groupId, String messageId, String text) {
        return messageEvent(eventId, group(groupId),
                "{\"type\":\"text\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"text\":\"%s\"}"
                        .formatted(messageId, messageId, text));
    }

    public static String groupMention(String eventId, String groupId, String messageId,
                                      String textAfterMention, String quotedMessageId) {
        String quoted = quotedMessageId == null ? "" : ",\"quotedMessageId\":\"" + quotedMessageId + "\"";
        return messageEvent(eventId, group(groupId),
                ("{\"type\":\"text\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"text\":\"@bot %s\"%s,"
                        + "\"mention\":{\"mentionees\":[{\"index\":0,\"length\":4,\"type\":\"user\",\"userId\":\"Ubot\",\"isSelf\":true}]}}")
                        .formatted(messageId, messageId, textAfterMention, quoted));
    }

    public static String groupImage(String eventId, String groupId, String messageId) {
        return messageEvent(eventId, group(groupId),
                "{\"type\":\"image\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"contentProvider\":{\"type\":\"line\"}}"
                        .formatted(messageId, messageId));
    }

    public static String groupAudio(String eventId, String groupId, String messageId) {
        return messageEvent(eventId, group(groupId),
                "{\"type\":\"audio\",\"id\":\"%s\",\"duration\":1000,\"contentProvider\":{\"type\":\"line\"}}"
                        .formatted(messageId));
    }

    public static String userText(String eventId, String userId, String messageId, String text) {
        return messageEvent(eventId, "{\"type\":\"user\",\"userId\":\"" + userId + "\"}",
                "{\"type\":\"text\",\"id\":\"%s\",\"quoteToken\":\"qt-%s\",\"text\":\"%s\"}"
                        .formatted(messageId, messageId, text));
    }

    public static String join(String eventId, String groupId) {
        return ("{\"type\":\"join\",\"mode\":\"active\",\"timestamp\":1760054400000,\"webhookEventId\":\"%s\","
                + "\"deliveryContext\":{\"isRedelivery\":false},\"replyToken\":\"rt-%s\",\"source\":%s}")
                .formatted(eventId, eventId, group(groupId));
    }

    public static String unknownEvent(String eventId) {
        return ("{\"type\":\"somethingNew\",\"mode\":\"active\",\"timestamp\":1760054400000,\"webhookEventId\":\"%s\","
                + "\"deliveryContext\":{\"isRedelivery\":false}}").formatted(eventId);
    }

    private static String group(String groupId) {
        return "{\"type\":\"group\",\"groupId\":\"" + groupId + "\",\"userId\":\"Uuser\"}";
    }

    private static String messageEvent(String eventId, String source, String message) {
        return ("{\"type\":\"message\",\"mode\":\"active\",\"timestamp\":1760054400000,\"webhookEventId\":\"%s\","
                + "\"deliveryContext\":{\"isRedelivery\":false},\"replyToken\":\"rt-%s\",\"source\":%s,\"message\":%s}")
                .formatted(eventId, eventId, source, message);
    }
}
```

- [ ] **Step 2：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/controller/LineBotControllerSignatureTest.java`：

```java
package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.service.LineMessageService;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import com.elvischang.ailinebot.support.Webhooks;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LineBotControllerSignatureTest extends IntegrationTestSupport {

    @MockBean
    LineMessageService lineMessageService;

    private final String body = Webhooks.body(Webhooks.groupText("e1", "Cg1", "100", "hello"));

    @Test
    void rejectsMissingSignature() throws Exception {
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineMessageService);
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", "bogus").content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineMessageService);
    }

    @Test
    void acceptsSignedRequest() throws Exception {
        postWebhook(body);
        verify(lineMessageService).handleTextMessage(any());
    }

    @Test
    void acceptsLineConsoleVerifyRequestWithNoEvents() throws Exception {
        postWebhook(Webhooks.body());
        verifyNoInteractions(lineMessageService);
    }

    @Test
    void rejectsSignedBodyThatIsNotJson() throws Exception {
        String notJson = "not json";
        mvc.perform(post("/callback").contentType(MediaType.APPLICATION_JSON)
                        .header("x-line-signature", Webhooks.sign(notJson)).content(notJson))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineMessageService);
    }
}
```

- [ ] **Step 3：確認失敗**

Run: `$MVN test -Dtest=LineBotControllerSignatureTest`
Expected: `rejectsMissingSignature`、`rejectsWrongSignature` 失敗（現在沒驗簽，回 200），證明 spec §3 第 2 點的漏洞存在。

- [ ] **Step 4：實作**

`webhook/LineSignatureVerifier.java`：

```java
package com.elvischang.ailinebot.webhook;

import com.linecorp.bot.parser.LineSignatureValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class LineSignatureVerifier {

    private final LineSignatureValidator validator;

    public LineSignatureVerifier(@Value("${line.bot.channel-secret}") String channelSecret) {
        this.validator = new LineSignatureValidator(channelSecret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isValid(byte[] body, String signature) {
        if (body == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            return validator.validateSignature(body, signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
```

`controller/LineBotController.java` 整個換成（暫時還是交給 `LineMessageService`，Task 10 才換成 ingest）：

```java
package com.elvischang.ailinebot.controller;

import com.elvischang.ailinebot.service.LineMessageService;
import com.elvischang.ailinebot.webhook.LineSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.webhook.model.AudioMessageContent;
import com.linecorp.bot.webhook.model.CallbackRequest;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class LineBotController {

    private static final Logger log = LoggerFactory.getLogger(LineBotController.class);

    private final LineSignatureVerifier signatureVerifier;
    private final LineMessageService lineMessageService;
    private final ObjectMapper lineObjectMapper = ModelObjectMapper.createNewObjectMapper();

    public LineBotController(LineSignatureVerifier signatureVerifier, LineMessageService lineMessageService) {
        this.signatureVerifier = signatureVerifier;
        this.lineMessageService = lineMessageService;
    }

    @GetMapping("/hello")
    public void hello() {
        System.out.println("Hello World");
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> callback(@RequestHeader(name = "x-line-signature", required = false) String signature,
                                         @RequestBody(required = false) byte[] body) {
        if (!signatureVerifier.isValid(body, signature)) {
            log.warn("webhook rejected: invalid signature");
            return ResponseEntity.badRequest().build();
        }
        CallbackRequest request;
        try {
            request = lineObjectMapper.readValue(body, CallbackRequest.class);
        } catch (IOException e) {
            log.warn("webhook rejected: body is not a LINE callback");
            return ResponseEntity.badRequest().build();
        }
        for (Event event : request.events()) {
            if (event instanceof MessageEvent messageEvent) {
                if (messageEvent.message() instanceof TextMessageContent) {
                    lineMessageService.handleTextMessage(messageEvent);
                } else if (messageEvent.message() instanceof AudioMessageContent) {
                    lineMessageService.handleAudioMessage(messageEvent);
                }
            }
        }
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 5：確認通過**

Run: `$MVN test -Dtest=LineBotControllerSignatureTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`。

- [ ] **Step 6：Commit**

```bash
git add src
git commit -m "fix: verify LINE webhook signature on the raw request body"
```

---

### Task 4：mention 與來源判斷

**Files:**
- Create: `line/LineSources.java`、`line/Mentions.java`、`line/Hashes.java`
- Test: `line/MentionsTest.java`、`line/LineSourcesTest.java`、`line/HashesTest.java`

**Interfaces:**
- Produces：`LineSources.chatId(Source): Optional<String>`（group → groupId、room → roomId、其他 → empty）；`Mentions.mentionsBot(TextMessageContent): boolean`；`Mentions.stripBotMentions(TextMessageContent): String`（已 trim）；`Hashes.sha256Hex(byte[]): String`、`Hashes.shortHash(String): String`（sha256 hex 前 16 碼）。

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/line/MentionsTest.java`：

```java
package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.Mention;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UserMentionee;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MentionsTest {

    private static TextMessageContent text(String text, UserMentionee... mentionees) {
        Mention mention = mentionees.length == 0 ? null : new Mention(List.of(mentionees));
        return new TextMessageContent("1", text, null, mention, "qt", null);
    }

    @Test
    void detectsAndStripsSelfMention() {
        var msg = text("@bot 這是真的嗎", new UserMentionee(0, 4, "Ubot", true));
        assertThat(Mentions.mentionsBot(msg)).isTrue();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("這是真的嗎");
    }

    @Test
    void ignoresMentionOfOtherUsers() {
        var msg = text("@媽媽 早安", new UserMentionee(0, 3, "Umom", false));
        assertThat(Mentions.mentionsBot(msg)).isFalse();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("@媽媽 早安");
    }

    @Test
    void stripsMentionAfterEmoji() {
        var msg = text("😀@bot 你好", new UserMentionee(2, 4, "Ubot", true));
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("😀 你好");
    }

    @Test
    void keepsTextWhenMentionIndexIsOutOfRange() {
        var msg = text("短", new UserMentionee(5, 4, "Ubot", true));
        assertThat(Mentions.mentionsBot(msg)).isTrue();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("短");
    }

    @Test
    void handlesMissingMention() {
        var msg = text("沒有 mention");
        assertThat(Mentions.mentionsBot(msg)).isFalse();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("沒有 mention");
    }
}
```

`line/LineSourcesTest.java`：

```java
package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.RoomSource;
import com.linecorp.bot.webhook.model.UserSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineSourcesTest {

    @Test
    void usesGroupOrRoomIdAndIgnoresOneOnOne() {
        assertThat(LineSources.chatId(new GroupSource("Cg1", "U1"))).contains("Cg1");
        assertThat(LineSources.chatId(new RoomSource("U1", "Rr1"))).contains("Rr1");
        assertThat(LineSources.chatId(new UserSource("U1"))).isEmpty();
    }
}
```

`line/HashesTest.java`：

```java
package com.elvischang.ailinebot.line;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HashesTest {

    @Test
    void hashesWithSha256() {
        assertThat(Hashes.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashes.shortHash("abc")).isEqualTo("ba7816bf8f01cfea");
    }
}
```

SDK 9.7.0 的建構子（已用 javap 確認）：`GroupSource(groupId, userId)`、`RoomSource(userId, roomId)`、`UserSource(userId)`。

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest='MentionsTest,LineSourcesTest,HashesTest'`
Expected: 編譯失敗，`Mentions`、`LineSources`、`Hashes` 不存在。

- [ ] **Step 3：實作**

`line/LineSources.java`：

```java
package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.RoomSource;
import com.linecorp.bot.webhook.model.Source;

import java.util.Optional;

public final class LineSources {

    private LineSources() {
    }

    public static Optional<String> chatId(Source source) {
        if (source instanceof GroupSource group) {
            return Optional.ofNullable(group.groupId());
        }
        if (source instanceof RoomSource room) {
            return Optional.ofNullable(room.roomId());
        }
        return Optional.empty();
    }
}
```

`line/Mentions.java`：

```java
package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UserMentionee;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

public final class Mentions {

    private Mentions() {
    }

    public static boolean mentionsBot(TextMessageContent message) {
        return selfMentionees(message).findAny().isPresent();
    }

    public static String stripBotMentions(TextMessageContent message) {
        String text = Objects.requireNonNullElse(message.text(), "");
        StringBuilder sb = new StringBuilder(text);
        selfMentionees(message)
                .filter(m -> m.index() != null && m.length() != null
                        && m.index() >= 0 && m.index() + m.length() <= text.length())
                .sorted(Comparator.comparingInt(UserMentionee::index).reversed())
                .forEach(m -> sb.delete(m.index(), m.index() + m.length()));
        return sb.toString().trim();
    }

    private static Stream<UserMentionee> selfMentionees(TextMessageContent message) {
        if (message.mention() == null || message.mention().mentionees() == null) {
            return Stream.empty();
        }
        return message.mention().mentionees().stream()
                .filter(UserMentionee.class::isInstance)
                .map(UserMentionee.class::cast)
                .filter(m -> Boolean.TRUE.equals(m.isSelf()));
    }
}
```

`line/Hashes.java`：

```java
package com.elvischang.ailinebot.line;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    public static String shortHash(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
```

`stripsMentionAfterEmoji` 期望 `"😀 你好"`：刪掉 index 2–6 的 `@bot` 後是 `"😀 你好"`。

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest='MentionsTest,LineSourcesTest,HashesTest'`
Expected: `Tests run: 7, Failures: 0, Errors: 0`。

- [ ] **Step 5：Commit**

```bash
git add src
git commit -m "feat: add mention, chat source, and hashing helpers"
```

---

### Task 5：去重表與素材表的 repository

**Files:**
- Create: `store/MaterialKind.java`、`store/MediaStatus.java`、`store/Material.java`、`store/MaterialRepository.java`、`store/InboundEventRepository.java`
- Test: `store/MaterialRepositoryTest.java`、`store/InboundEventRepositoryTest.java`

**Interfaces:**
- Produces：
  - `enum MaterialKind { TEXT, IMAGE, AUDIO, VIDEO, BOT_REPLY }`
  - `enum MediaStatus { NONE, PENDING, STORED, FAILED, TOO_LARGE }`
  - `record Material(String groupId, String messageId, MaterialKind kind, String text, boolean botTrigger, MediaStatus mediaStatus, String mediaSha256, int mediaAttempts, long createdAt, long expiresAt)`
  - `InboundEventRepository.insertIfAbsent(String webhookEventId, long receivedAt, boolean redelivery): boolean`（第一次 true）、`deleteOlderThan(long receivedBefore): int`
  - `MaterialRepository.insert(Material)`（已存在就略過）、`find(String groupId, String messageId): Optional<Material>`、`recentCandidates(String groupId, long since, String excludeMessageId): List<Material>`（新到舊，最多 5 筆，排除 bot 觸發訊息和 `BOT_REPLY`）、`markMedia(String groupId, String messageId, MediaStatus status, String sha256)`、`recordMediaFailure(String groupId, String messageId, int maxAttempts): int`（回傳新的嘗試次數，到上限自動標 `FAILED`）、`pendingMedia(long createdBefore): List<Material>`、`expired(long now): List<Material>`、`deleteExpired(long now): int`

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/store/MaterialRepositoryTest.java`：

```java
package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialRepositoryTest extends IntegrationTestSupport {

    @Autowired
    MaterialRepository materials;

    private static Material text(String group, String id, long at, boolean trigger) {
        return new Material(group, id, MaterialKind.TEXT, "t" + id, trigger, MediaStatus.NONE, null, 0, at, at + 1_000_000);
    }

    @Test
    void findsOnlyWithinTheSameGroup() {
        materials.insert(text("Cg1", "1", 100, false));
        assertThat(materials.find("Cg1", "1")).get().extracting(Material::text).isEqualTo("t1");
        assertThat(materials.find("Cg2", "1")).isEmpty();
    }

    @Test
    void insertIgnoresDuplicates() {
        materials.insert(text("Cg1", "1", 100, false));
        materials.insert(new Material("Cg1", "1", MaterialKind.TEXT, "changed", false, MediaStatus.NONE, null, 0, 100, 200));
        assertThat(materials.find("Cg1", "1")).get().extracting(Material::text).isEqualTo("t1");
    }

    @Test
    void recentCandidatesExcludeTriggersBotRepliesOldAndOtherGroups() {
        materials.insert(text("Cg1", "old", 10, false));
        materials.insert(text("Cg1", "a", 100, false));
        materials.insert(text("Cg1", "trigger", 110, true));
        materials.insert(new Material("Cg1", "bot", MaterialKind.BOT_REPLY, null, false, MediaStatus.NONE, null, 0, 120, 999));
        materials.insert(text("Cg2", "other", 130, false));
        materials.insert(text("Cg1", "self", 140, false));

        assertThat(materials.recentCandidates("Cg1", 50, "self"))
                .extracting(Material::messageId).containsExactly("a");
    }

    @Test
    void recordsMediaFailuresUntilMax() {
        materials.insert(new Material("Cg1", "9", MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, 100, 999));
        assertThat(materials.recordMediaFailure("Cg1", "9", 2)).isEqualTo(1);
        assertThat(materials.find("Cg1", "9")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.PENDING);
        assertThat(materials.recordMediaFailure("Cg1", "9", 2)).isEqualTo(2);
        assertThat(materials.find("Cg1", "9")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.FAILED);
    }

    @Test
    void listsPendingMediaAndExpiredRows() {
        materials.insert(new Material("Cg1", "9", MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, 100, 500));
        materials.insert(text("Cg1", "1", 100, false));
        assertThat(materials.pendingMedia(101)).extracting(Material::messageId).containsExactly("9");
        assertThat(materials.expired(600)).extracting(Material::messageId).containsExactly("9");
        assertThat(materials.deleteExpired(600)).isEqualTo(1);
        materials.markMedia("Cg1", "1", MediaStatus.STORED, "abc");
        assertThat(materials.find("Cg1", "1")).get().extracting(Material::mediaSha256).isEqualTo("abc");
    }
}
```

`store/InboundEventRepositoryTest.java`：

```java
package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class InboundEventRepositoryTest extends IntegrationTestSupport {

    @Autowired
    InboundEventRepository events;

    @Test
    void acceptsEachWebhookEventOnce() {
        assertThat(events.insertIfAbsent("e1", 100, false)).isTrue();
        assertThat(events.insertIfAbsent("e1", 200, true)).isFalse();
        assertThat(events.deleteOlderThan(150)).isEqualTo(1);
        assertThat(events.insertIfAbsent("e1", 300, false)).isTrue();
    }
}
```

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest='MaterialRepositoryTest,InboundEventRepositoryTest'`
Expected: 編譯失敗，類別不存在。

- [ ] **Step 3：實作**

`store/MaterialKind.java`：

```java
package com.elvischang.ailinebot.store;

public enum MaterialKind { TEXT, IMAGE, AUDIO, VIDEO, BOT_REPLY }
```

`store/MediaStatus.java`：

```java
package com.elvischang.ailinebot.store;

public enum MediaStatus { NONE, PENDING, STORED, FAILED, TOO_LARGE }
```

`store/Material.java`：

```java
package com.elvischang.ailinebot.store;

public record Material(
        String groupId,
        String messageId,
        MaterialKind kind,
        String text,
        boolean botTrigger,
        MediaStatus mediaStatus,
        String mediaSha256,
        int mediaAttempts,
        long createdAt,
        long expiresAt) {
}
```

`store/InboundEventRepository.java`：

```java
package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InboundEventRepository {

    private final JdbcClient jdbc;

    public InboundEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(String webhookEventId, long receivedAt, boolean redelivery) {
        return jdbc.sql("""
                        INSERT OR IGNORE INTO inbound_event(webhook_event_id, received_at, is_redelivery)
                        VALUES (:id, :at, :redelivery)
                        """)
                .param("id", webhookEventId)
                .param("at", receivedAt)
                .param("redelivery", redelivery ? 1 : 0)
                .update() == 1;
    }

    public int deleteOlderThan(long receivedBefore) {
        return jdbc.sql("DELETE FROM inbound_event WHERE received_at < :before")
                .param("before", receivedBefore)
                .update();
    }
}
```

`store/MaterialRepository.java`：

```java
package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MaterialRepository {

    private static final RowMapper<Material> MAPPER = (rs, n) -> new Material(
            rs.getString("group_id"),
            rs.getString("message_id"),
            MaterialKind.valueOf(rs.getString("kind")),
            rs.getString("text"),
            rs.getInt("is_bot_trigger") == 1,
            MediaStatus.valueOf(rs.getString("media_status")),
            rs.getString("media_sha256"),
            rs.getInt("media_attempts"),
            rs.getLong("created_at"),
            rs.getLong("expires_at"));

    private final JdbcClient jdbc;

    public MaterialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Material m) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO material(group_id, message_id, kind, text, is_bot_trigger, media_status,
                                                       media_sha256, media_attempts, created_at, expires_at)
                        VALUES (:groupId, :messageId, :kind, :text, :botTrigger, :mediaStatus,
                                :sha, :attempts, :createdAt, :expiresAt)
                        """)
                .param("groupId", m.groupId())
                .param("messageId", m.messageId())
                .param("kind", m.kind().name())
                .param("text", m.text())
                .param("botTrigger", m.botTrigger() ? 1 : 0)
                .param("mediaStatus", m.mediaStatus().name())
                .param("sha", m.mediaSha256())
                .param("attempts", m.mediaAttempts())
                .param("createdAt", m.createdAt())
                .param("expiresAt", m.expiresAt())
                .update();
    }

    public Optional<Material> find(String groupId, String messageId) {
        return jdbc.sql("SELECT * FROM material WHERE group_id = :g AND message_id = :m")
                .param("g", groupId)
                .param("m", messageId)
                .query(MAPPER)
                .optional();
    }

    public List<Material> recentCandidates(String groupId, long since, String excludeMessageId) {
        return jdbc.sql("""
                        SELECT * FROM material
                        WHERE group_id = :g AND created_at >= :since AND message_id <> :exclude
                          AND is_bot_trigger = 0 AND kind <> 'BOT_REPLY'
                        ORDER BY created_at DESC
                        LIMIT 5
                        """)
                .param("g", groupId)
                .param("since", since)
                .param("exclude", excludeMessageId)
                .query(MAPPER)
                .list();
    }

    public void markMedia(String groupId, String messageId, MediaStatus status, String sha256) {
        jdbc.sql("UPDATE material SET media_status = :s, media_sha256 = :sha WHERE group_id = :g AND message_id = :m")
                .param("s", status.name())
                .param("sha", sha256)
                .param("g", groupId)
                .param("m", messageId)
                .update();
    }

    public int recordMediaFailure(String groupId, String messageId, int maxAttempts) {
        jdbc.sql("""
                        UPDATE material
                        SET media_attempts = media_attempts + 1,
                            media_status = CASE WHEN media_attempts + 1 >= :max THEN 'FAILED' ELSE media_status END
                        WHERE group_id = :g AND message_id = :m
                        """)
                .param("max", maxAttempts)
                .param("g", groupId)
                .param("m", messageId)
                .update();
        return find(groupId, messageId).map(Material::mediaAttempts).orElse(maxAttempts);
    }

    public List<Material> pendingMedia(long createdBefore) {
        return jdbc.sql("SELECT * FROM material WHERE media_status = 'PENDING' AND created_at < :before")
                .param("before", createdBefore)
                .query(MAPPER)
                .list();
    }

    public List<Material> expired(long now) {
        return jdbc.sql("SELECT * FROM material WHERE expires_at < :now")
                .param("now", now)
                .query(MAPPER)
                .list();
    }

    public int deleteExpired(long now) {
        return jdbc.sql("DELETE FROM material WHERE expires_at < :now")
                .param("now", now)
                .update();
    }
}
```

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest='MaterialRepositoryTest,InboundEventRepositoryTest'`
Expected: `Tests run: 6, Failures: 0, Errors: 0`。

- [ ] **Step 5：Commit**

```bash
git add src
git commit -m "feat: add repositories for webhook dedup and group materials"
```

---

### Task 6：job repository

**Files:**
- Create: `store/JobKind.java`、`store/JobStatus.java`、`store/SendState.java`、`store/Job.java`、`store/NewJob.java`、`store/JobRepository.java`
- Test: `store/JobRepositoryTest.java`

**Interfaces:**
- Produces：
  - `enum JobKind { FACT_CHECK, IMAGINE, NOTICE, DELIVERY }`
  - `enum JobStatus { PENDING, RUNNING, DONE, FAILED }`
  - `enum SendState { NOT_SENT, CARRIED, SENDING_REPLY, REPLIED, WAITING_NEXT, UNKNOWN, EXPIRED, NOTHING_TO_SEND }`
  - `record NewJob(JobKind kind, String groupId, String triggerMessageId, String materialMessageId, String userQuestion, String replyToken, String quoteToken, String resultText)`
  - `record Job(String id, JobKind kind, String groupId, String triggerMessageId, String materialMessageId, String userQuestion, String replyToken, String quoteToken, long receivedAt, JobStatus status, int attempts, Long leaseUntil, String resultText, SendState sendState, String carrierJobId, Long waitingSince, String errorCode, long createdAt, Long finishedAt)`
  - `JobRepository`：`insert(NewJob, long now): String`、`find(String id): Optional<Job>`、`claim(String id, long now, long leaseMs): boolean`、`complete(String id, String resultText, long now)`、`fail(String id, String resultText, String errorCode, long now)`、`release(String id, String errorCode)`、`stalePending(long createdBefore): List<String>`、`expiredLeases(long now): List<Job>`、`unsentFinished(long finishedBefore): List<String>`、`hasWaiting(String groupId, long notBefore): boolean`、`attachWaiting(String groupId, String carrierJobId, int limit, long notBefore): int`、`carried(String carrierJobId): List<Job>`、`setSendState(Collection<String> ids, SendState state)`、`markWaiting(Collection<String> ids, long now)`、`expireWaiting(long waitingBefore): int`、`resetSendingToUnknown(): int`、`clearContent(long createdBefore): int`、`deleteOlderThan(long createdBefore): int`

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/store/JobRepositoryTest.java`：

```java
package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobRepositoryTest extends IntegrationTestSupport {

    @Autowired
    JobRepository jobs;

    private String finished(String group, long now) {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, group, "t", "m", "q", "rt", "qt", null), now);
        jobs.claim(id, now, 1000);
        jobs.complete(id, "result", now);
        return id;
    }

    @Test
    void claimsOnlyOnce() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        assertThat(jobs.claim(id, 100, 1000)).isTrue();
        assertThat(jobs.claim(id, 100, 1000)).isFalse();
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.leaseUntil()).isEqualTo(1100L);
    }

    @Test
    void releasedJobCanBeClaimedAgain() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(id, 100, 1000);
        jobs.release(id, "TIMEOUT");
        assertThat(jobs.stalePending(101)).containsExactly(id);
        assertThat(jobs.claim(id, 200, 1000)).isTrue();
        assertThat(jobs.find(id).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void attachesOldestWaitingResultsOfTheSameGroupOnce() {
        String a = finished("Cg1", 100);
        String b = finished("Cg1", 100);
        String c = finished("Cg1", 100);
        String other = finished("Cg2", 100);
        jobs.markWaiting(List.of(a), 100);
        jobs.markWaiting(List.of(b), 200);
        jobs.markWaiting(List.of(c), 300);
        jobs.markWaiting(List.of(other), 100);

        assertThat(jobs.hasWaiting("Cg1", 0)).isTrue();
        assertThat(jobs.attachWaiting("Cg1", "carrier", 2, 0)).isEqualTo(2);
        assertThat(jobs.carried("carrier")).extracting(Job::id).containsExactly(a, b);
        assertThat(jobs.attachWaiting("Cg1", "carrier2", 5, 0)).isEqualTo(1);
        assertThat(jobs.attachWaiting("Cg1", "carrier3", 5, 0)).isZero();
        assertThat(jobs.find(other).orElseThrow().sendState()).isEqualTo(SendState.WAITING_NEXT);
    }

    @Test
    void ignoresWaitingResultsOlderThanTheCutoff() {
        String a = finished("Cg1", 100);
        jobs.markWaiting(List.of(a), 100);
        assertThat(jobs.hasWaiting("Cg1", 101)).isFalse();
        assertThat(jobs.attachWaiting("Cg1", "carrier", 5, 101)).isZero();
        assertThat(jobs.expireWaiting(101)).isEqualTo(1);
        assertThat(jobs.find(a).orElseThrow().sendState()).isEqualTo(SendState.EXPIRED);
    }

    @Test
    void markWaitingKeepsTheFirstWaitingTimeAndDetachesFromCarrier() {
        String a = finished("Cg1", 100);
        jobs.markWaiting(List.of(a), 100);
        jobs.attachWaiting("Cg1", "carrier", 5, 0);
        jobs.markWaiting(List.of(a), 900);
        Job job = jobs.find(a).orElseThrow();
        assertThat(job.waitingSince()).isEqualTo(100L);
        assertThat(job.carrierJobId()).isNull();
        assertThat(job.sendState()).isEqualTo(SendState.WAITING_NEXT);
    }

    @Test
    void recoveryQueries() {
        String running = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(running, 100, 50);
        String done = finished("Cg1", 100);
        String sending = finished("Cg1", 100);
        jobs.setSendState(List.of(sending), SendState.SENDING_REPLY);

        assertThat(jobs.expiredLeases(151)).extracting(Job::id).containsExactly(running);
        assertThat(jobs.unsentFinished(101)).containsExactly(done);
        assertThat(jobs.resetSendingToUnknown()).isEqualTo(1);
        assertThat(jobs.find(sending).orElseThrow().sendState()).isEqualTo(SendState.UNKNOWN);
    }

    @Test
    void clearsContentAndDeletesOldRows() {
        String a = finished("Cg1", 100);
        jobs.setSendState(List.of(a), SendState.REPLIED);
        assertThat(jobs.clearContent(101)).isEqualTo(1);
        assertThat(jobs.find(a).orElseThrow().resultText()).isNull();
        assertThat(jobs.deleteOlderThan(101)).isEqualTo(1);
        assertThat(jobs.find(a)).isEmpty();
    }
}
```

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest=JobRepositoryTest`
Expected: 編譯失敗。

- [ ] **Step 3：實作**

`store/JobKind.java`：

```java
package com.elvischang.ailinebot.store;

public enum JobKind { FACT_CHECK, IMAGINE, NOTICE, DELIVERY }
```

`store/JobStatus.java`：

```java
package com.elvischang.ailinebot.store;

public enum JobStatus { PENDING, RUNNING, DONE, FAILED }
```

`store/SendState.java`：

```java
package com.elvischang.ailinebot.store;

public enum SendState { NOT_SENT, CARRIED, SENDING_REPLY, REPLIED, WAITING_NEXT, UNKNOWN, EXPIRED, NOTHING_TO_SEND }
```

`store/NewJob.java`：

```java
package com.elvischang.ailinebot.store;

public record NewJob(
        JobKind kind,
        String groupId,
        String triggerMessageId,
        String materialMessageId,
        String userQuestion,
        String replyToken,
        String quoteToken,
        String resultText) {
}
```

`store/Job.java`：

```java
package com.elvischang.ailinebot.store;

public record Job(
        String id,
        JobKind kind,
        String groupId,
        String triggerMessageId,
        String materialMessageId,
        String userQuestion,
        String replyToken,
        String quoteToken,
        long receivedAt,
        JobStatus status,
        int attempts,
        Long leaseUntil,
        String resultText,
        SendState sendState,
        String carrierJobId,
        Long waitingSince,
        String errorCode,
        long createdAt,
        Long finishedAt) {
}
```

`store/JobRepository.java`：

```java
package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private static final RowMapper<Job> MAPPER = (rs, n) -> new Job(
            rs.getString("id"),
            JobKind.valueOf(rs.getString("kind")),
            rs.getString("group_id"),
            rs.getString("trigger_message_id"),
            rs.getString("material_message_id"),
            rs.getString("user_question"),
            rs.getString("reply_token"),
            rs.getString("quote_token"),
            rs.getLong("received_at"),
            JobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            nullableLong(rs, "lease_until"),
            rs.getString("result_text"),
            SendState.valueOf(rs.getString("send_state")),
            rs.getString("carrier_job_id"),
            nullableLong(rs, "waiting_since"),
            rs.getString("error_code"),
            rs.getLong("created_at"),
            nullableLong(rs, "finished_at"));

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public String insert(NewJob job, long now) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO job(id, kind, group_id, trigger_message_id, material_message_id, user_question,
                                        reply_token, quote_token, received_at, status, result_text, created_at)
                        VALUES (:id, :kind, :groupId, :trigger, :material, :question,
                                :replyToken, :quoteToken, :now, 'PENDING', :result, :now)
                        """)
                .param("id", id)
                .param("kind", job.kind().name())
                .param("groupId", job.groupId())
                .param("trigger", job.triggerMessageId())
                .param("material", job.materialMessageId())
                .param("question", job.userQuestion())
                .param("replyToken", job.replyToken())
                .param("quoteToken", job.quoteToken())
                .param("result", job.resultText())
                .param("now", now)
                .update();
        return id;
    }

    public Optional<Job> find(String id) {
        return jdbc.sql("SELECT * FROM job WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public boolean claim(String id, long now, long leaseMs) {
        return jdbc.sql("""
                        UPDATE job SET status = 'RUNNING', attempts = attempts + 1, lease_until = :lease
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("lease", now + leaseMs)
                .param("id", id)
                .update() == 1;
    }

    public void complete(String id, String resultText, long now) {
        jdbc.sql("""
                        UPDATE job SET status = 'DONE', result_text = :result, lease_until = NULL,
                                       error_code = NULL, finished_at = :now
                        WHERE id = :id
                        """)
                .param("result", resultText)
                .param("now", now)
                .param("id", id)
                .update();
    }

    public void fail(String id, String resultText, String errorCode, long now) {
        jdbc.sql("""
                        UPDATE job SET status = 'FAILED', result_text = :result, lease_until = NULL,
                                       error_code = :code, finished_at = :now
                        WHERE id = :id
                        """)
                .param("result", resultText)
                .param("code", errorCode)
                .param("now", now)
                .param("id", id)
                .update();
    }

    public void release(String id, String errorCode) {
        jdbc.sql("UPDATE job SET status = 'PENDING', lease_until = NULL, error_code = :code WHERE id = :id")
                .param("code", errorCode)
                .param("id", id)
                .update();
    }

    public List<String> stalePending(long createdBefore) {
        return jdbc.sql("SELECT id FROM job WHERE status = 'PENDING' AND created_at < :before ORDER BY created_at")
                .param("before", createdBefore)
                .query(String.class)
                .list();
    }

    public List<Job> expiredLeases(long now) {
        return jdbc.sql("SELECT * FROM job WHERE status = 'RUNNING' AND lease_until < :now")
                .param("now", now)
                .query(MAPPER)
                .list();
    }

    public List<String> unsentFinished(long finishedBefore) {
        return jdbc.sql("""
                        SELECT id FROM job
                        WHERE status IN ('DONE', 'FAILED') AND send_state = 'NOT_SENT' AND finished_at < :before
                        ORDER BY finished_at
                        """)
                .param("before", finishedBefore)
                .query(String.class)
                .list();
    }

    public boolean hasWaiting(String groupId, long notBefore) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM job
                        WHERE group_id = :g AND send_state = 'WAITING_NEXT' AND waiting_since >= :notBefore
                        """)
                .param("g", groupId)
                .param("notBefore", notBefore)
                .query(Integer.class)
                .single() > 0;
    }

    public int attachWaiting(String groupId, String carrierJobId, int limit, long notBefore) {
        return jdbc.sql("""
                        UPDATE job SET send_state = 'CARRIED', carrier_job_id = :carrier
                        WHERE id IN (
                            SELECT id FROM job
                            WHERE group_id = :g AND send_state = 'WAITING_NEXT' AND waiting_since >= :notBefore
                            ORDER BY waiting_since, created_at
                            LIMIT :limit)
                        """)
                .param("carrier", carrierJobId)
                .param("g", groupId)
                .param("notBefore", notBefore)
                .param("limit", limit)
                .update();
    }

    public List<Job> carried(String carrierJobId) {
        return jdbc.sql("""
                        SELECT * FROM job WHERE carrier_job_id = :carrier AND send_state = 'CARRIED'
                        ORDER BY waiting_since, created_at
                        """)
                .param("carrier", carrierJobId)
                .query(MAPPER)
                .list();
    }

    public void setSendState(Collection<String> ids, SendState state) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE job SET send_state = :state WHERE id IN (:ids)")
                .param("state", state.name())
                .param("ids", ids)
                .update();
    }

    public void markWaiting(Collection<String> ids, long now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE job SET send_state = 'WAITING_NEXT', carrier_job_id = NULL,
                                       waiting_since = COALESCE(waiting_since, :now)
                        WHERE id IN (:ids)
                        """)
                .param("now", now)
                .param("ids", ids)
                .update();
    }

    public int expireWaiting(long waitingBefore) {
        return jdbc.sql("UPDATE job SET send_state = 'EXPIRED' WHERE send_state = 'WAITING_NEXT' AND waiting_since < :before")
                .param("before", waitingBefore)
                .update();
    }

    public int resetSendingToUnknown() {
        return jdbc.sql("UPDATE job SET send_state = 'UNKNOWN' WHERE send_state = 'SENDING_REPLY'").update();
    }

    public int clearContent(long createdBefore) {
        return jdbc.sql("""
                        UPDATE job SET result_text = NULL, user_question = NULL
                        WHERE created_at < :before AND send_state NOT IN ('NOT_SENT', 'CARRIED', 'WAITING_NEXT', 'SENDING_REPLY')
                          AND (result_text IS NOT NULL OR user_question IS NOT NULL)
                        """)
                .param("before", createdBefore)
                .update();
    }

    public int deleteOlderThan(long createdBefore) {
        return jdbc.sql("DELETE FROM job WHERE created_at < :before")
                .param("before", createdBefore)
                .update();
    }
}
```

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest=JobRepositoryTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`。

- [ ] **Step 5：Commit**

```bash
git add src
git commit -m "feat: add job repository with claim, waiting, and carrier queries"
```

---

### Task 7：找素材（MaterialResolver）

**Files:**
- Create: `delivery/BotReplies.java`、`material/Trigger.java`、`material/Resolution.java`、`material/MaterialResolver.java`
- Test: `material/MaterialResolverTest.java`

**Interfaces:**
- Consumes：`MaterialRepository`、`BotProperties`
- Produces：
  - `record Trigger(String groupId, String messageId, String textWithoutMention, String quotedMessageId)`
  - `sealed interface Resolution permits Resolution.FactCheck, Resolution.Imagine, Resolution.Notice`，其中 `record FactCheck(Material material)`、`record Imagine(String prompt)`、`record Notice(String text)`
  - `MaterialResolver.resolve(Trigger trigger, long now): Resolution`
  - `BotReplies` 常數：`QUOTE_NOT_FOUND`、`QUOTED_BOT_REPLY`、`MEDIA_UNSUPPORTED`、`IMAGE_GONE`、`IMAGE_TOO_LARGE`、`ASK_TO_QUOTE`、`TOO_MANY_CANDIDATES`、`JOB_FAILED`、`DELAYED_PREFIX`、`JOIN_NOTICE`、`FACT_CHECK_NOT_READY`

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/material/MaterialResolverTest.java`：

```java
package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialResolverTest extends IntegrationTestSupport {

    private static final long NOW = 10_000_000L;

    @Autowired
    MaterialResolver resolver;

    @Autowired
    MaterialRepository materials;

    private void put(String id, MaterialKind kind, MediaStatus media, boolean trigger, long at) {
        materials.insert(new Material("Cg1", id, kind, "t" + id, trigger, media, null, 0, at, at + 1_000_000_000L));
    }

    private Resolution resolve(String text, String quoted) {
        put("trigger", MaterialKind.TEXT, MediaStatus.NONE, true, NOW);
        return resolver.resolve(new Trigger("Cg1", "trigger", text, quoted), NOW);
    }

    @Test
    void usesQuotedTextMaterial() {
        put("99", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 5_000_000);
        assertThat(resolve("這是真的嗎", "99")).isInstanceOfSatisfying(Resolution.FactCheck.class,
                f -> assertThat(f.material().messageId()).isEqualTo("99"));
    }

    @Test
    void quotedMessageFromAnotherGroupIsNotFound() {
        materials.insert(new Material("Cg2", "99", MaterialKind.TEXT, "x", false, MediaStatus.NONE, null, 0, NOW, NOW + 1));
        assertThat(resolve("這是真的嗎", "99")).isEqualTo(new Resolution.Notice(BotReplies.QUOTE_NOT_FOUND));
    }

    @Test
    void quotedBotReplyAudioAndBrokenImagesGetNotices() {
        put("bot", MaterialKind.BOT_REPLY, MediaStatus.NONE, false, NOW);
        put("voice", MaterialKind.AUDIO, MediaStatus.NONE, false, NOW);
        put("gone", MaterialKind.IMAGE, MediaStatus.FAILED, false, NOW);
        put("big", MaterialKind.IMAGE, MediaStatus.TOO_LARGE, false, NOW);
        put("ok", MaterialKind.IMAGE, MediaStatus.PENDING, false, NOW);
        assertThat(resolve("?", "bot")).isEqualTo(new Resolution.Notice(BotReplies.QUOTED_BOT_REPLY));
        assertThat(resolve("?", "voice")).isEqualTo(new Resolution.Notice(BotReplies.MEDIA_UNSUPPORTED));
        assertThat(resolve("?", "gone")).isEqualTo(new Resolution.Notice(BotReplies.IMAGE_GONE));
        assertThat(resolve("?", "big")).isEqualTo(new Resolution.Notice(BotReplies.IMAGE_TOO_LARGE));
        assertThat(resolve("?", "ok")).isInstanceOf(Resolution.FactCheck.class);
    }

    @Test
    void longMentionTextIsItsOwnMaterial() {
        assertThat(resolve("聽說六十五歲以上每個人都可以領五千元是真的嗎", null))
                .isInstanceOfSatisfying(Resolution.FactCheck.class,
                        f -> assertThat(f.material().messageId()).isEqualTo("trigger"));
    }

    @Test
    void usesTheOnlyRecentCandidate() {
        put("recent", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 60_000);
        put("tooOld", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 11 * 60_000);
        assertThat(resolve("真的嗎", null)).isInstanceOfSatisfying(Resolution.FactCheck.class,
                f -> assertThat(f.material().messageId()).isEqualTo("recent"));
    }

    @Test
    void asksToQuoteWhenNothingOrTooMuchIsRecent() {
        assertThat(resolve("真的嗎", null)).isEqualTo(new Resolution.Notice(BotReplies.ASK_TO_QUOTE));
        put("a", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 60_000);
        put("b", MaterialKind.IMAGE, MediaStatus.STORED, false, NOW - 30_000);
        assertThat(resolve("真的嗎", null)).isEqualTo(new Resolution.Notice(BotReplies.TOO_MANY_CANDIDATES));
    }

    @Test
    void botsOwnRecentReplyIsNotACandidate() {
        put("bot", MaterialKind.BOT_REPLY, MediaStatus.NONE, false, NOW - 10_000);
        put("a", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 60_000);
        assertThat(resolve("真的嗎", null)).isInstanceOfSatisfying(Resolution.FactCheck.class,
                f -> assertThat(f.material().messageId()).isEqualTo("a"));
    }

    @Test
    void imagineCommandWinsOverLongText() {
        assertThat(resolve("/imagine 一隻在台北街頭散步的柴犬穿著雨衣", null))
                .isEqualTo(new Resolution.Imagine("一隻在台北街頭散步的柴犬穿著雨衣"));
    }
}
```

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest=MaterialResolverTest`
Expected: 編譯失敗。

- [ ] **Step 3：實作**

`delivery/BotReplies.java`：

```java
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
    public static final String FACT_CHECK_NOT_READY = "查證功能準備中，這則先不判斷。";
    public static final String DELAYED_PREFIX = "（回覆剛才的問題）\n";
    public static final String JOIN_NOTICE = """
            大家好，我是查證小幫手。
            ・要查證時，請「引用」那則訊息，再 @ 我
            ・沒被 @ 的時候，我不會讀內容、也不會回覆
            ・為了讓大家之後能引用，群組的文字和圖片會暫存 7 天，到期自動刪除
            ・查證時，內容會送到 OpenAI 搜尋與判讀""";

    private BotReplies() {
    }
}
```

`material/Trigger.java`：

```java
package com.elvischang.ailinebot.material;

public record Trigger(String groupId, String messageId, String textWithoutMention, String quotedMessageId) {
}
```

`material/Resolution.java`：

```java
package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.store.Material;

public sealed interface Resolution permits Resolution.FactCheck, Resolution.Imagine, Resolution.Notice {

    record FactCheck(Material material) implements Resolution {
    }

    record Imagine(String prompt) implements Resolution {
    }

    record Notice(String text) implements Resolution {
    }
}
```

`material/MaterialResolver.java`：

```java
package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MaterialResolver {

    private static final String IMAGINE_PREFIX = "/imagine ";

    private final MaterialRepository materials;
    private final BotProperties props;

    public MaterialResolver(MaterialRepository materials, BotProperties props) {
        this.materials = materials;
        this.props = props;
    }

    public Resolution resolve(Trigger trigger, long now) {
        String text = trigger.textWithoutMention();
        if (text.startsWith(IMAGINE_PREFIX)) {
            return new Resolution.Imagine(text.substring(IMAGINE_PREFIX.length()).trim());
        }
        if (trigger.quotedMessageId() != null) {
            return materials.find(trigger.groupId(), trigger.quotedMessageId())
                    .map(this::checkable)
                    .orElse(new Resolution.Notice(BotReplies.QUOTE_NOT_FOUND));
        }
        if (text.codePointCount(0, text.length()) >= props.selfMaterialMinChars()) {
            return materials.find(trigger.groupId(), trigger.messageId())
                    .<Resolution>map(Resolution.FactCheck::new)
                    .orElse(new Resolution.Notice(BotReplies.ASK_TO_QUOTE));
        }
        long since = now - props.recentWindow().toMillis();
        List<Material> candidates = materials.recentCandidates(trigger.groupId(), since, trigger.messageId());
        if (candidates.isEmpty()) {
            return new Resolution.Notice(BotReplies.ASK_TO_QUOTE);
        }
        if (candidates.size() > 1) {
            return new Resolution.Notice(BotReplies.TOO_MANY_CANDIDATES);
        }
        return checkable(candidates.getFirst());
    }

    private Resolution checkable(Material material) {
        return switch (material.kind()) {
            case BOT_REPLY -> new Resolution.Notice(BotReplies.QUOTED_BOT_REPLY);
            case AUDIO, VIDEO -> new Resolution.Notice(BotReplies.MEDIA_UNSUPPORTED);
            case IMAGE -> switch (material.mediaStatus()) {
                case FAILED -> new Resolution.Notice(BotReplies.IMAGE_GONE);
                case TOO_LARGE -> new Resolution.Notice(BotReplies.IMAGE_TOO_LARGE);
                default -> new Resolution.FactCheck(material);
            };
            case TEXT -> new Resolution.FactCheck(material);
        };
    }
}
```

`longMentionTextIsItsOwnMaterial` 的文字是 22 個字，超過門檻 20。

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest=MaterialResolverTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`。

- [ ] **Step 5：Commit**

```bash
git add src
git commit -m "feat: resolve which group material an @bot message refers to"
```

---

### Task 8：WebhookIngestService（去重、存素材、建 job、認領待送結果）

**Files:**
- Create: `job/JobReadyEvent.java`、`job/ImageReceivedEvent.java`、`webhook/WebhookIngestService.java`
- Test: `webhook/WebhookIngestServiceTest.java`

**Interfaces:**
- Consumes：`InboundEventRepository`、`MaterialRepository`、`JobRepository`、`MaterialResolver`、`Mentions`、`LineSources`、`BotProperties`、`Clock`
- Produces：`record JobReadyEvent(String jobId)`、`record ImageReceivedEvent(String groupId, String messageId)`、`WebhookIngestService.ingest(List<Event> events)`（`@Transactional`，commit 後才發出事件）

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/webhook/WebhookIngestServiceTest.java`：

```java
package com.elvischang.ailinebot.webhook;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.job.ImageReceivedEvent;
import com.elvischang.ailinebot.job.JobReadyEvent;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupAudio;
import static com.elvischang.ailinebot.support.Webhooks.groupImage;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.elvischang.ailinebot.support.Webhooks.join;
import static com.elvischang.ailinebot.support.Webhooks.parse;
import static com.elvischang.ailinebot.support.Webhooks.unknownEvent;
import static com.elvischang.ailinebot.support.Webhooks.userText;
import static org.assertj.core.api.Assertions.assertThat;

@RecordApplicationEvents
class WebhookIngestServiceTest extends IntegrationTestSupport {

    @Autowired
    WebhookIngestService ingest;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    @Autowired
    ApplicationEvents events;

    private List<Job> allJobs() {
        return jdbc.sql("SELECT id FROM job ORDER BY created_at").query(String.class).list()
                .stream().map(id -> jobs.find(id).orElseThrow()).toList();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void ignoresOneOnOneChats() {
        ingest.ingest(parse(body(userText("e1", "U1", "100", "hi"))));
        assertThat(count("inbound_event") + count("material") + count("job")).isZero();
    }

    @Test
    void storesPlainGroupTextWithoutCreatingAJob() {
        ingest.ingest(parse(body(groupText("e1", "Cg1", "100", "轉傳的訊息"))));
        assertThat(materials.find("Cg1", "100")).get().extracting(Material::text).isEqualTo("轉傳的訊息");
        assertThat(count("job")).isZero();
        assertThat(events.stream(JobReadyEvent.class)).isEmpty();
    }

    @Test
    void storesImagesAsPendingDownloadsAndAudioWithoutDownload() {
        ingest.ingest(parse(body(groupImage("e1", "Cg1", "200"), groupAudio("e2", "Cg1", "201"))));
        assertThat(materials.find("Cg1", "200")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.PENDING);
        assertThat(materials.find("Cg1", "201")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.NONE);
        assertThat(events.stream(ImageReceivedEvent.class)).containsExactly(new ImageReceivedEvent("Cg1", "200"));
        assertThat(count("job")).isZero();
    }

    @Test
    void mentionWithQuoteCreatesAFactCheckJob() {
        ingest.ingest(parse(body(groupText("e1", "Cg1", "99", "轉傳的訊息"))));
        ingest.ingest(parse(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99"))));

        Job job = allJobs().getFirst();
        assertThat(job.kind()).isEqualTo(JobKind.FACT_CHECK);
        assertThat(job.materialMessageId()).isEqualTo("99");
        assertThat(job.replyToken()).isEqualTo("rt-e2");
        assertThat(job.quoteToken()).isEqualTo("qt-100");
        assertThat(job.userQuestion()).isEqualTo("這是真的嗎");
        assertThat(materials.find("Cg1", "100")).get().extracting(Material::botTrigger).isEqualTo(true);
        assertThat(events.stream(JobReadyEvent.class)).containsExactly(new JobReadyEvent(job.id()));
    }

    @Test
    void ambiguousMentionCreatesANoticeJob() {
        ingest.ingest(parse(body(groupMention("e1", "Cg1", "100", "真的嗎", null))));
        Job job = allJobs().getFirst();
        assertThat(job.kind()).isEqualTo(JobKind.NOTICE);
        assertThat(job.resultText()).isEqualTo(BotReplies.ASK_TO_QUOTE);
    }

    @Test
    void redeliveredEventIsIgnored() {
        String webhook = body(groupMention("e1", "Cg1", "100", "真的嗎", null));
        ingest.ingest(parse(webhook));
        ingest.ingest(parse(webhook));
        assertThat(count("job")).isEqualTo(1);
    }

    @Test
    void joinCreatesTheDataNotice() {
        ingest.ingest(parse(body(join("e1", "Cg1"))));
        Job job = allJobs().getFirst();
        assertThat(job.kind()).isEqualTo(JobKind.NOTICE);
        assertThat(job.resultText()).isEqualTo(BotReplies.JOIN_NOTICE);
        assertThat(job.replyToken()).isEqualTo("rt-e1");
    }

    @Test
    void unknownEventsDoNotStopTheRestOfTheBatch() {
        ingest.ingest(parse(body(unknownEvent("e0"), groupText("e1", "Cg1", "100", "hi"))));
        assertThat(materials.find("Cg1", "100")).isPresent();
    }

    @Test
    void plainMessageCarriesWaitingResultsInADeliveryJob() {
        String waiting = waitingResult("Cg1");
        ingest.ingest(parse(body(groupText("e1", "Cg1", "100", "早安"))));

        Job delivery = allJobs().stream().filter(j -> j.kind() == JobKind.DELIVERY).findFirst().orElseThrow();
        assertThat(delivery.replyToken()).isEqualTo("rt-e1");
        assertThat(jobs.find(waiting).orElseThrow().carrierJobId()).isEqualTo(delivery.id());
        assertThat(events.stream(JobReadyEvent.class)).containsExactly(new JobReadyEvent(delivery.id()));
    }

    @Test
    void mentionCarriesWaitingResultsOnItsOwnJob() {
        String waiting = waitingResult("Cg1");
        ingest.ingest(parse(body(groupMention("e1", "Cg1", "100", "真的嗎", null))));

        Job notice = allJobs().stream().filter(j -> j.kind() == JobKind.NOTICE).findFirst().orElseThrow();
        assertThat(jobs.find(waiting).orElseThrow().carrierJobId()).isEqualTo(notice.id());
        assertThat(allJobs()).noneMatch(j -> j.kind() == JobKind.DELIVERY);
    }

    @Test
    void messagesInOtherGroupsDoNotCarryWaitingResults() {
        String waiting = waitingResult("Cg1");
        ingest.ingest(parse(body(groupText("e1", "Cg2", "100", "早安"))));
        assertThat(jobs.find(waiting).orElseThrow().sendState()).isEqualTo(SendState.WAITING_NEXT);
    }

    private String waitingResult(String group) {
        long now = clock.millis();
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, group, "1", "1", "q", "rt-old", "qt-1", null), now);
        jobs.claim(id, now, 1000);
        jobs.complete(id, "結果", now);
        jobs.markWaiting(List.of(id), now);
        return id;
    }
}
```

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest=WebhookIngestServiceTest`
Expected: 編譯失敗。

- [ ] **Step 3：實作**

`job/JobReadyEvent.java`：

```java
package com.elvischang.ailinebot.job;

public record JobReadyEvent(String jobId) {
}
```

`job/ImageReceivedEvent.java`：

```java
package com.elvischang.ailinebot.job;

public record ImageReceivedEvent(String groupId, String messageId) {
}
```

`webhook/WebhookIngestService.java`：

```java
package com.elvischang.ailinebot.webhook;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.job.ImageReceivedEvent;
import com.elvischang.ailinebot.job.JobReadyEvent;
import com.elvischang.ailinebot.line.LineSources;
import com.elvischang.ailinebot.line.Mentions;
import com.elvischang.ailinebot.material.MaterialResolver;
import com.elvischang.ailinebot.material.Resolution;
import com.elvischang.ailinebot.material.Trigger;
import com.elvischang.ailinebot.store.InboundEventRepository;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.linecorp.bot.webhook.model.AudioMessageContent;
import com.linecorp.bot.webhook.model.DeliveryContext;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.JoinEvent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.VideoMessageContent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class WebhookIngestService {

    private final InboundEventRepository inboundEvents;
    private final MaterialRepository materials;
    private final JobRepository jobs;
    private final MaterialResolver resolver;
    private final ApplicationEventPublisher publisher;
    private final BotProperties props;
    private final Clock clock;

    public WebhookIngestService(InboundEventRepository inboundEvents, MaterialRepository materials, JobRepository jobs,
                                MaterialResolver resolver, ApplicationEventPublisher publisher,
                                BotProperties props, Clock clock) {
        this.inboundEvents = inboundEvents;
        this.materials = materials;
        this.jobs = jobs;
        this.resolver = resolver;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public void ingest(List<Event> events) {
        long now = clock.millis();
        for (Event event : events) {
            if (event instanceof MessageEvent message) {
                ingestMessage(message, now);
            } else if (event instanceof JoinEvent join) {
                ingestJoin(join, now);
            }
        }
    }

    private void ingestMessage(MessageEvent event, long now) {
        Optional<String> chat = LineSources.chatId(event.source());
        if (chat.isEmpty() || !isFirstDelivery(event.webhookEventId(), event.deliveryContext(), now)) {
            return;
        }
        String chatId = chat.get();
        String jobId = null;
        switch (event.message()) {
            case TextMessageContent text -> jobId = ingestText(chatId, text, event.replyToken(), now);
            case ImageMessageContent image -> storeMedia(chatId, image.id(), MaterialKind.IMAGE, now);
            case AudioMessageContent audio -> storeMedia(chatId, audio.id(), MaterialKind.AUDIO, now);
            case VideoMessageContent video -> storeMedia(chatId, video.id(), MaterialKind.VIDEO, now);
            default -> {
            }
        }
        carryWaitingResults(chatId, jobId, event.replyToken(), now);
    }

    private void ingestJoin(JoinEvent event, long now) {
        Optional<String> chat = LineSources.chatId(event.source());
        if (chat.isEmpty() || !isFirstDelivery(event.webhookEventId(), event.deliveryContext(), now)) {
            return;
        }
        createJob(new NewJob(JobKind.NOTICE, chat.get(), null, null, null,
                event.replyToken(), null, BotReplies.JOIN_NOTICE), now);
    }

    private boolean isFirstDelivery(String webhookEventId, DeliveryContext context, long now) {
        boolean redelivery = context != null && Boolean.TRUE.equals(context.isRedelivery());
        return inboundEvents.insertIfAbsent(webhookEventId, now, redelivery);
    }

    private String ingestText(String chatId, TextMessageContent text, String replyToken, long now) {
        boolean mentioned = Mentions.mentionsBot(text);
        String body = mentioned ? Mentions.stripBotMentions(text) : text.text();
        materials.insert(new Material(chatId, text.id(), MaterialKind.TEXT, body, mentioned,
                MediaStatus.NONE, null, 0, now, expiry(now)));
        if (!mentioned) {
            return null;
        }
        Resolution resolution = resolver.resolve(new Trigger(chatId, text.id(), body, text.quotedMessageId()), now);
        return createJob(toNewJob(resolution, chatId, text, body, replyToken), now);
    }

    private NewJob toNewJob(Resolution resolution, String chatId, TextMessageContent text, String body, String replyToken) {
        return switch (resolution) {
            case Resolution.FactCheck f -> new NewJob(JobKind.FACT_CHECK, chatId, text.id(),
                    f.material().messageId(), body, replyToken, text.quoteToken(), null);
            case Resolution.Imagine i -> new NewJob(JobKind.IMAGINE, chatId, text.id(),
                    null, i.prompt(), replyToken, text.quoteToken(), null);
            case Resolution.Notice n -> new NewJob(JobKind.NOTICE, chatId, text.id(),
                    null, null, replyToken, text.quoteToken(), n.text());
        };
    }

    private void storeMedia(String chatId, String messageId, MaterialKind kind, long now) {
        MediaStatus status = kind == MaterialKind.IMAGE ? MediaStatus.PENDING : MediaStatus.NONE;
        materials.insert(new Material(chatId, messageId, kind, null, false, status, null, 0, now, expiry(now)));
        if (kind == MaterialKind.IMAGE) {
            publisher.publishEvent(new ImageReceivedEvent(chatId, messageId));
        }
    }

    private void carryWaitingResults(String chatId, String jobId, String replyToken, long now) {
        long notBefore = now - props.waitingTtl().toMillis();
        if (replyToken == null || !jobs.hasWaiting(chatId, notBefore)) {
            return;
        }
        int max = props.maxMessagesPerReply();
        if (jobId != null) {
            jobs.attachWaiting(chatId, jobId, max - 1, notBefore);
            return;
        }
        String deliveryId = jobs.insert(new NewJob(JobKind.DELIVERY, chatId, null, null, null,
                replyToken, null, null), now);
        jobs.attachWaiting(chatId, deliveryId, max, notBefore);
        publisher.publishEvent(new JobReadyEvent(deliveryId));
    }

    private String createJob(NewJob job, long now) {
        String id = jobs.insert(job, now);
        publisher.publishEvent(new JobReadyEvent(id));
        return id;
    }

    private long expiry(long now) {
        return now + props.materialTtl().toMillis();
    }
}
```

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest=WebhookIngestServiceTest`
Expected: `Tests run: 11, Failures: 0, Errors: 0`。

- [ ] **Step 5：Commit**

```bash
git add src
git commit -m "feat: ingest webhook events into materials and jobs in one transaction"
```

---

### Task 9：ReplySender（只用 reply 的送出狀態機）

**Files:**
- Create: `delivery/ReplySender.java`
- Delete: `config/LineConfig.java`（讓 SDK 的 `LineBotAutoConfiguration` 建 client，才會讀 `line.bot.api-end-point`、`line.bot.read-timeout`）
- Modify: `application.yaml`（`line.bot` 加 `connect-timeout: 5s`、`read-timeout: 15s`）
- Test: `delivery/ReplySenderTest.java`

**Interfaces:**
- Consumes：`JobRepository`、`MaterialRepository`、`MessagingApiClient`、`BotProperties`、`Clock`、`Hashes`
- Produces：`ReplySender.send(String jobId)`。可重複呼叫：只處理 `status ∈ {DONE, FAILED}` 且 `send_state = NOT_SENT` 的 job。成功後把 reply response 的 `sentMessages` 記成 `BOT_REPLY` 素材。

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/delivery/ReplySenderTest.java`：

```java
package com.elvischang.ailinebot.delivery;

import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ReplySenderTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    @Autowired
    ReplySender sender;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    private String finished(JobKind kind, String quoteToken, String result, String replyToken) {
        long now = clock.millis();
        String id = jobs.insert(new NewJob(kind, "Cg1", "100", "99", "q", replyToken, quoteToken, null), now);
        jobs.claim(id, now, 60_000);
        jobs.complete(id, result, now);
        return id;
    }

    private SendState state(String id) {
        return jobs.find(id).map(Job::sendState).orElseThrow();
    }

    private static void stubRejected(int priority, boolean onlyWithQuote) {
        var mapping = post(urlEqualTo(REPLY)).atPriority(priority);
        if (onlyWithQuote) {
            mapping = mapping.withRequestBody(containing("quoteToken"));
        }
        LINE.stubFor(mapping.willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"message\":\"Invalid\"}")));
    }

    @Test
    void repliesWithinDeadlineQuotingTheQuestionAndRecordsTheBotReply() {
        stubReplyOk("900");
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.REPLIED);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-1")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo("結果")))
                .withRequestBody(matchingJsonPath("$.messages[0].quoteToken", equalTo("qt-100"))));
        assertThat(materials.find("Cg1", "900")).get().extracting(Material::kind).isEqualTo(MaterialKind.BOT_REPLY);
    }

    @Test
    void waitsForTheNextMessageWhenTheDeadlinePassed() {
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");
        clock.advance(Duration.ofSeconds(46));

        sender.send(id);

        LINE.verify(0, postRequestedFor(urlEqualTo(REPLY)));
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.sendState()).isEqualTo(SendState.WAITING_NEXT);
        assertThat(job.waitingSince()).isEqualTo(clock.millis());
    }

    @Test
    void retriesOnceWithoutQuoteTokenWhenRejected() {
        stubRejected(1, true);
        stubReplyOk("901");
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.REPLIED);
        LINE.verify(2, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void waitsForTheNextMessageWhenRejectedTwice() {
        stubRejected(1, false);
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.WAITING_NEXT);
        LINE.verify(2, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void marksUnknownAndDoesNotRetryWhenTheResultIsUnclear() {
        LINE.stubFor(post(urlEqualTo(REPLY)).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.UNKNOWN);
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void marksUnknownOnServerError() {
        LINE.stubFor(post(urlEqualTo(REPLY)).willReturn(aResponse().withStatus(500)));
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.UNKNOWN);
    }

    @Test
    void deliveryJobCarriesWaitingResultsWithAPrefix() {
        stubReplyOk("902");
        String a = finished(JobKind.FACT_CHECK, "qt-a", "A", "rt-a");
        String b = finished(JobKind.NOTICE, "qt-b", "B", "rt-b");
        jobs.markWaiting(List.of(a, b), clock.millis());
        String delivery = finished(JobKind.DELIVERY, null, null, "rt-d");
        jobs.attachWaiting("Cg1", delivery, 5, 0);

        sender.send(delivery);

        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-d")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.DELAYED_PREFIX + "A")))
                .withRequestBody(matchingJsonPath("$.messages[0].quoteToken", equalTo("qt-a")))
                .withRequestBody(matchingJsonPath("$.messages[1].text", equalTo(BotReplies.DELAYED_PREFIX + "B"))));
        assertThat(List.of(state(a), state(b), state(delivery))).containsOnly(SendState.REPLIED);
    }

    @Test
    void lateDeliveryPutsCarriedResultsBackToWaiting() {
        String a = finished(JobKind.FACT_CHECK, "qt-a", "A", "rt-a");
        jobs.markWaiting(List.of(a), clock.millis());
        String delivery = finished(JobKind.DELIVERY, null, null, "rt-d");
        jobs.attachWaiting("Cg1", delivery, 5, 0);
        clock.advance(Duration.ofSeconds(46));

        sender.send(delivery);

        assertThat(state(a)).isEqualTo(SendState.WAITING_NEXT);
        assertThat(state(delivery)).isEqualTo(SendState.NOTHING_TO_SEND);
    }

    @Test
    void sendsAnImageForImagine() {
        stubReplyOk("903");
        String id = finished(JobKind.IMAGINE, "qt-100", "https://img.example/x.png", "rt-1");

        sender.send(id);

        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].type", equalTo("image")))
                .withRequestBody(matchingJsonPath("$.messages[0].originalContentUrl", equalTo("https://img.example/x.png"))));
    }

    @Test
    void sendingTwiceDoesNothingTheSecondTime() {
        stubReplyOk("904");
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);
        sender.send(id);

        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
    }
}
```

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest=ReplySenderTest`
Expected: 編譯失敗，`ReplySender` 不存在。

- [ ] **Step 3：實作**

刪除 `src/main/java/com/elvischang/ailinebot/config/LineConfig.java`。

`application.yaml` 的 `line.bot` 底下加：

```yaml
    connect-timeout: 5s
    read-timeout: 15s
```

`delivery/ReplySender.java`：

```java
package com.elvischang.ailinebot.delivery;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.JobStatus;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.SendState;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.ReplyMessageResponse;
import com.linecorp.bot.messaging.model.SentMessage;
import com.linecorp.bot.messaging.model.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ReplySender {

    private static final Logger log = LoggerFactory.getLogger(ReplySender.class);
    private static final Set<JobStatus> FINISHED = EnumSet.of(JobStatus.DONE, JobStatus.FAILED);

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final MessagingApiClient client;
    private final BotProperties props;
    private final Clock clock;

    public ReplySender(JobRepository jobs, MaterialRepository materials, MessagingApiClient client,
                       BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    public void send(String jobId) {
        Job carrier = jobs.find(jobId).orElse(null);
        if (carrier == null || !FINISHED.contains(carrier.status()) || carrier.sendState() != SendState.NOT_SENT) {
            return;
        }
        List<Job> deliverables = deliverables(carrier);
        List<String> allIds = allIds(carrier, deliverables);
        if (deliverables.isEmpty()) {
            jobs.setSendState(allIds, SendState.NOTHING_TO_SEND);
            return;
        }
        long now = clock.millis();
        if (carrier.replyToken() == null || now >= carrier.receivedAt() + props.replyDeadline().toMillis()) {
            toWaiting(carrier, deliverables, now);
            log.info("delivery jobId={} groupHash={} results={} outcome=WAITING_NEXT",
                    carrier.id(), Hashes.shortHash(carrier.groupId()), deliverables.size());
            return;
        }
        List<Message> quoted = messages(carrier, deliverables, true);
        List<Message> unquoted = messages(carrier, deliverables, false);
        jobs.setSendState(allIds, SendState.SENDING_REPLY);
        ReplyOutcome outcome = reply(carrier, quoted);
        if (outcome.status() == ReplyStatus.REJECTED && hasQuote(deliverables)) {
            outcome = reply(carrier, unquoted);
        }
        switch (outcome.status()) {
            case SENT -> {
                jobs.setSendState(allIds, SendState.REPLIED);
                recordBotReplies(carrier.groupId(), outcome.sentMessages(), now);
            }
            case REJECTED -> toWaiting(carrier, deliverables, now);
            case UNKNOWN -> jobs.setSendState(allIds, SendState.UNKNOWN);
        }
        log.info("delivery jobId={} kind={} groupHash={} results={} waitMs={} outcome={}",
                carrier.id(), carrier.kind(), Hashes.shortHash(carrier.groupId()), deliverables.size(),
                now - carrier.receivedAt(), outcome.status());
    }

    private List<Job> deliverables(Job carrier) {
        List<Job> result = new ArrayList<>();
        if (carrier.kind() != JobKind.DELIVERY && carrier.resultText() != null) {
            result.add(carrier);
        }
        result.addAll(jobs.carried(carrier.id()));
        return List.copyOf(result);
    }

    private static List<String> allIds(Job carrier, List<Job> deliverables) {
        List<String> ids = new ArrayList<>();
        ids.add(carrier.id());
        deliverables.stream().map(Job::id).filter(id -> !id.equals(carrier.id())).forEach(ids::add);
        return List.copyOf(ids);
    }

    private void toWaiting(Job carrier, List<Job> deliverables, long now) {
        jobs.markWaiting(deliverables.stream().map(Job::id).toList(), now);
        if (carrier.kind() == JobKind.DELIVERY) {
            jobs.setSendState(List.of(carrier.id()), SendState.NOTHING_TO_SEND);
        }
    }

    private static boolean hasQuote(List<Job> deliverables) {
        return deliverables.stream().anyMatch(j -> j.kind() != JobKind.IMAGINE && j.quoteToken() != null);
    }

    private static List<Message> messages(Job carrier, List<Job> deliverables, boolean withQuote) {
        return deliverables.stream()
                .map(job -> toMessage(job, !job.id().equals(carrier.id()), withQuote))
                .toList();
    }

    private ReplyOutcome reply(Job carrier, List<Message> messages) {
        try {
            Result<ReplyMessageResponse> result = client
                    .replyMessage(new ReplyMessageRequest(carrier.replyToken(), messages, false))
                    .get(props.replyTimeout().toMillis(), TimeUnit.MILLISECONDS);
            ReplyMessageResponse body = result.body();
            List<SentMessage> sent = body == null || body.sentMessages() == null ? List.of() : body.sentMessages();
            return new ReplyOutcome(ReplyStatus.SENT, sent);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AbstractLineClientException ex && ex.getCode() >= 400 && ex.getCode() < 500) {
                log.warn("reply rejected jobId={} code={}", carrier.id(), ex.getCode());
                return new ReplyOutcome(ReplyStatus.REJECTED, List.of());
            }
            log.warn("reply result unknown jobId={} cause={}", carrier.id(), e.getCause().getClass().getSimpleName());
            return new ReplyOutcome(ReplyStatus.UNKNOWN, List.of());
        } catch (TimeoutException e) {
            log.warn("reply result unknown jobId={} cause=timeout", carrier.id());
            return new ReplyOutcome(ReplyStatus.UNKNOWN, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReplyOutcome(ReplyStatus.UNKNOWN, List.of());
        }
    }

    private static Message toMessage(Job job, boolean delayed, boolean withQuote) {
        if (job.kind() == JobKind.IMAGINE && job.status() == JobStatus.DONE) {
            URI uri = URI.create(job.resultText());
            return new ImageMessage(uri, uri);
        }
        String text = delayed ? BotReplies.DELAYED_PREFIX + job.resultText() : job.resultText();
        return new TextMessage(null, null, text, null, withQuote ? job.quoteToken() : null);
    }

    private void recordBotReplies(String groupId, List<SentMessage> sent, long now) {
        long expiresAt = now + props.materialTtl().toMillis();
        for (SentMessage message : sent) {
            materials.insert(new Material(groupId, message.id(), MaterialKind.BOT_REPLY, null, false,
                    MediaStatus.NONE, null, 0, now, expiresAt));
        }
    }

    private enum ReplyStatus { SENT, REJECTED, UNKNOWN }

    private record ReplyOutcome(ReplyStatus status, List<SentMessage> sentMessages) {
    }
}
```

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest=ReplySenderTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`。如果 `marksUnknownAndDoesNotRetryWhenTheResultIsUnclear` 失敗，先確認 test profile 的 `line.bot.read-timeout: 2s` 有生效（LINE autoconfig 的 client 會讀它）。

- [ ] **Step 5：跑一次全部測試，確認刪掉 `LineConfig` 沒有弄壞其他東西**

Run: `$MVN test`
Expected: 全部通過。

- [ ] **Step 6：Commit**

```bash
git add -A src
git commit -m "feat: deliver results by reply only, carrying late results on the next message"
```

---

### Task 10：JobRunner、非同步派發、接上 controller

**Files:**
- Create: `factcheck/FactCheckService.java`、`factcheck/PlaceholderFactCheckService.java`、`config/AsyncConfig.java`、`job/JobRunner.java`、`job/JobEventListener.java`
- Create（test）：`support/RecordingFactCheckService.java`
- Modify: `controller/LineBotController.java`（改呼叫 `WebhookIngestService`）、`support/TestBeans.java`、`support/IntegrationTestSupport.java`、`controller/LineBotControllerSignatureTest.java`、`webhook/WebhookIngestServiceTest.java`
- Test: `job/JobFlowTest.java`

**Interfaces:**
- Consumes：`WebhookIngestService`、`JobRepository`、`MaterialRepository`、`ReplySender`、`ImageGenerationService`（既有）
- Produces：
  - `interface FactCheckService { String verify(Material material, String userQuestion); }`：Plan 2 會換成真正的實作，回傳要送出的完整文字。
  - `JobRunner.run(String jobId)`：claim 成功就計算結果並存起來，最後一律呼叫 `ReplySender.send`。計算丟 exception 時：嘗試次數未到上限就放回 PENDING（交給補救掃描），到上限就標 FAILED 並以 `BotReplies.JOB_FAILED` 當作結果送出。
  - `JobEventListener`：`@Async("jobExecutor") @TransactionalEventListener(fallbackExecution = true)` 處理 `JobReadyEvent`。

- [ ] **Step 1：測試用的查證替身**

`src/test/java/com/elvischang/ailinebot/support/RecordingFactCheckService.java`：

```java
package com.elvischang.ailinebot.support;

import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.store.Material;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class RecordingFactCheckService implements FactCheckService {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Material, String> behavior = RecordingFactCheckService::defaultResult;

    private static String defaultResult(Material material) {
        return "TEST RESULT " + material.messageId();
    }

    @Override
    public String verify(Material material, String userQuestion) {
        calls.add(material.messageId());
        return behavior.apply(material);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public void behave(Function<Material, String> newBehavior) {
        this.behavior = newBehavior;
    }

    public void reset() {
        calls.clear();
        behavior = RecordingFactCheckService::defaultResult;
    }
}
```

`support/TestBeans.java` 加一個 bean：

```java
    @Bean
    @Primary
    public RecordingFactCheckService recordingFactCheckService() {
        return new RecordingFactCheckService();
    }
```

`support/IntegrationTestSupport.java` 加欄位 `@Autowired protected RecordingFactCheckService factChecks;`，並在 `resetSharedState()` 最後加 `factChecks.reset();`。

- [ ] **Step 2：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/job/JobFlowTest.java`：

```java
package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.service.ImageGenerationService;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.JobStatus;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class JobFlowTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    @Autowired
    JobRepository jobs;

    @Autowired
    JobRunner runner;

    @MockBean
    ImageGenerationService imageGenerationService;

    private Job jobFor(String triggerMessageId) {
        String id = jdbc.sql("SELECT id FROM job WHERE trigger_message_id = :m").param("m", triggerMessageId)
                .query(String.class).single();
        return jobs.find(id).orElseThrow();
    }

    private void awaitSendState(String triggerMessageId, SendState expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobFor(triggerMessageId).sendState()).isEqualTo(expected));
    }

    @Test
    void checksTheQuotedMaterialAndReplies() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).containsExactly("99");
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-e2")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo("TEST RESULT 99"))));
    }

    @Test
    void ambiguousMentionGetsANoticeWithoutCheckingAnything() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "98", "第一則"), groupText("e2", "Cg1", "99", "第二則")));
        postWebhook(body(groupMention("e3", "Cg1", "100", "真的嗎", null)));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).isEmpty();
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.TOO_MANY_CANDIDATES))));
    }

    @Test
    void slowResultRidesTheNextPlainMessage() throws Exception {
        stubReplyOk("900");
        factChecks.behave(m -> {
            clock.advance(Duration.ofSeconds(60));
            return "SLOW " + m.messageId();
        });
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        awaitSendState("100", SendState.WAITING_NEXT);
        LINE.verify(0, postRequestedFor(urlEqualTo(REPLY)));

        postWebhook(body(groupText("e3", "Cg1", "101", "早安")));

        awaitSendState("100", SendState.REPLIED);
        assertThat(factChecks.calls()).containsExactly("99");
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-e3")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.DELAYED_PREFIX + "SLOW 99")))
                .withRequestBody(matchingJsonPath("$.messages[0].quoteToken", equalTo("qt-100"))));
    }

    @Test
    void nextMentionRepliesWithItsOwnAnswerAndTheWaitingOne() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "97", "第一則"), groupText("e2", "Cg1", "99", "第二則")));
        factChecks.behave(m -> {
            clock.advance(Duration.ofSeconds(60));
            return "SLOW " + m.messageId();
        });
        postWebhook(body(groupMention("e3", "Cg1", "102", "這個呢", "97")));
        awaitSendState("102", SendState.WAITING_NEXT);

        factChecks.reset();
        postWebhook(body(groupMention("e4", "Cg1", "103", "真的嗎", "99")));

        awaitSendState("103", SendState.REPLIED);
        assertThat(jobFor("102").sendState()).isEqualTo(SendState.REPLIED);
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-e4")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo("TEST RESULT 99")))
                .withRequestBody(matchingJsonPath("$.messages[1].text", equalTo(BotReplies.DELAYED_PREFIX + "SLOW 97"))));
    }

    @Test
    void failingCheckIsRetriedThenSendsAFailureNotice() throws Exception {
        stubReplyOk("900");
        factChecks.behave(m -> {
            throw new IllegalStateException("model down");
        });
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Job job = jobFor("100");
            assertThat(job.status()).isEqualTo(JobStatus.PENDING);
            assertThat(job.attempts()).isEqualTo(1);
        });

        runner.run(jobFor("100").id());

        Job job = jobFor("100");
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.sendState()).isEqualTo(SendState.REPLIED);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.JOB_FAILED))));
    }

    @Test
    void imagineRepliesWithTheGeneratedImage() throws Exception {
        stubReplyOk("900");
        when(imageGenerationService.generateImage(anyString())).thenReturn("https://img.example/cat.png");
        postWebhook(body(groupMention("e1", "Cg1", "100", "/imagine 一隻貓", null)));

        awaitSendState("100", SendState.REPLIED);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].type", equalTo("image"))));
    }
}
```

`nextMentionRepliesWithItsOwnAnswerAndTheWaitingOne` 的流程：先讓 102 的結果卡在 WAITING_NEXT，中間不送任何非 @ 訊息，再送一則會即時完成的 @（103），驗證只有一次 reply，而且同時帶出 103 自己的答案和 102 的答案。

- [ ] **Step 3：確認失敗**

Run: `$MVN test -Dtest=JobFlowTest`
Expected: 編譯失敗，`JobRunner`、`FactCheckService` 不存在。

- [ ] **Step 4：實作**

`factcheck/FactCheckService.java`：

```java
package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.store.Material;

public interface FactCheckService {

    String verify(Material material, String userQuestion);
}
```

`factcheck/PlaceholderFactCheckService.java`：

```java
package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Material;
import org.springframework.stereotype.Service;

@Service
public class PlaceholderFactCheckService implements FactCheckService {

    @Override
    public String verify(Material material, String userQuestion) {
        return BotReplies.FACT_CHECK_NOT_READY;
    }
}
```

`config/AsyncConfig.java`：

```java
package com.elvischang.ailinebot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "jobExecutor")
    public ThreadPoolTaskExecutor jobExecutor() {
        return executor("job-", 2, 50);
    }

    @Bean(name = "mediaExecutor")
    public ThreadPoolTaskExecutor mediaExecutor() {
        return executor("media-", 1, 100);
    }

    private static ThreadPoolTaskExecutor executor(String prefix, int threads, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("executor {} queue full; recovery sweep will pick the task up", prefix));
        return executor;
    }
}
```

`job/JobRunner.java`：

```java
package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.delivery.ReplySender;
import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.service.ImageGenerationService;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final String HIGH_QUALITY_KEYWORD = "高畫質";

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final FactCheckService factCheckService;
    private final ImageGenerationService imageGenerationService;
    private final ReplySender replySender;
    private final BotProperties props;
    private final Clock clock;

    public JobRunner(JobRepository jobs, MaterialRepository materials, FactCheckService factCheckService,
                     ImageGenerationService imageGenerationService, ReplySender replySender,
                     BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.factCheckService = factCheckService;
        this.imageGenerationService = imageGenerationService;
        this.replySender = replySender;
        this.props = props;
        this.clock = clock;
    }

    public void run(String jobId) {
        try {
            if (jobs.claim(jobId, clock.millis(), props.jobLease().toMillis())) {
                Job job = jobs.find(jobId).orElseThrow();
                if (!compute(job)) {
                    return;
                }
            }
            replySender.send(jobId);
        } catch (RuntimeException e) {
            log.error("job crashed jobId={} cause={}", jobId, e.getClass().getSimpleName(), e);
        }
    }

    private boolean compute(Job job) {
        long started = clock.millis();
        try {
            String result = switch (job.kind()) {
                case NOTICE -> job.resultText();
                case DELIVERY -> null;
                case FACT_CHECK -> materials.find(job.groupId(), job.materialMessageId())
                        .map(material -> factCheckService.verify(material, job.userQuestion()))
                        .orElse(BotReplies.QUOTE_NOT_FOUND);
                case IMAGINE -> imagine(job.userQuestion());
            };
            jobs.complete(job.id(), result, clock.millis());
            log.info("job done jobId={} kind={} groupHash={} attempt={} runMs={}",
                    job.id(), job.kind(), Hashes.shortHash(job.groupId()), job.attempts(), clock.millis() - started);
            return true;
        } catch (RuntimeException e) {
            String code = e.getClass().getSimpleName();
            if (job.attempts() >= props.jobMaxAttempts()) {
                jobs.fail(job.id(), BotReplies.JOB_FAILED, code, clock.millis());
                log.warn("job failed jobId={} kind={} attempts={} cause={}", job.id(), job.kind(), job.attempts(), code);
                return true;
            }
            jobs.release(job.id(), code);
            log.warn("job will retry jobId={} kind={} attempts={} cause={}", job.id(), job.kind(), job.attempts(), code);
            return false;
        }
    }

    private String imagine(String prompt) {
        String url = prompt.contains(HIGH_QUALITY_KEYWORD)
                ? imageGenerationService.generateImageWithGptImage1(prompt)
                : imageGenerationService.generateImage(prompt);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("image generation returned no URL");
        }
        return url;
    }
}
```

`job/JobEventListener.java`：

```java
package com.elvischang.ailinebot.job;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class JobEventListener {

    private final JobRunner runner;

    public JobEventListener(JobRunner runner) {
        this.runner = runner;
    }

    @Async("jobExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onJobReady(JobReadyEvent event) {
        runner.run(event.jobId());
    }
}
```

`controller/LineBotController.java`：把 `LineMessageService` 換成 `WebhookIngestService`。建構子參數改成 `(LineSignatureVerifier signatureVerifier, WebhookIngestService ingestService)`，`for (Event event : request.events()) { ... }` 那整段換成一行：

```java
        ingestService.ingest(request.events());
```

移除不再用到的 import（`LineMessageService`、`AudioMessageContent`、`Event`、`MessageEvent`、`TextMessageContent`）。ingest 丟出的 exception 不要接，讓它變成 500，這樣有開 redelivery 時 LINE 會重送。

`controller/LineBotControllerSignatureTest.java`：`@MockBean LineMessageService lineMessageService` 換成 `@MockBean WebhookIngestService ingestService`；`verify(lineMessageService).handleTextMessage(any())` 換成 `verify(ingestService).ingest(any())`；`acceptsLineConsoleVerifyRequestWithNoEvents` 改成 `verify(ingestService).ingest(List.of())`（import `java.util.List`）；其他 `verifyNoInteractions(lineMessageService)` 換成 `verifyNoInteractions(ingestService)`。

`webhook/WebhookIngestServiceTest.java`：加 `@MockBean JobEventListener jobEventListener;`（import `com.elvischang.ailinebot.job.JobEventListener`、`org.springframework.boot.test.mock.mockito.MockBean`），讓 ingest 測試只看 DB，不被背景工作改動。

- [ ] **Step 5：確認通過**

Run: `$MVN test -Dtest='JobFlowTest,LineBotControllerSignatureTest,WebhookIngestServiceTest'`
Expected: 全部通過。

- [ ] **Step 6：全部測試**

Run: `$MVN test`
Expected: 全部通過。

- [ ] **Step 7：Commit**

```bash
git add -A src
git commit -m "feat: run jobs asynchronously after commit and route webhooks through ingest"
```

---

### Task 11：圖片下載（MediaStore）

**Files:**
- Create: `media/MediaTooLargeException.java`、`media/MediaStore.java`
- Modify: `job/JobEventListener.java`（加圖片下載的 handler）
- Test: `media/MediaStoreTest.java`

**Interfaces:**
- Consumes：`MaterialRepository`、`MessagingApiBlobClient`、`BotProperties`、`Hashes`
- Produces：`MediaStore.download(String groupId, String messageId)`（只處理 `PENDING`；成功 `STORED`＋sha256；超過大小 `TOO_LARGE`；其他錯誤記一次失敗，到上限 `FAILED`）、`MediaStore.storedFile(String groupId, String messageId): Optional<Path>`、`MediaStore.delete(String groupId, String messageId)`、`MediaStore.pathFor(String groupId, String messageId): Path`（`messageId` 不符合 `^[0-9]{1,32}$` 丟 `IllegalArgumentException`）。Plan 2 用 `storedFile` 讀圖片送 vision。

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/media/MediaStoreTest.java`：

```java
package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.time.Duration;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupImage;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MediaStoreTest extends IntegrationTestSupport {

    @Autowired
    MediaStore mediaStore;

    @Autowired
    MaterialRepository materials;

    private void pendingImage(String id) {
        long now = clock.millis();
        materials.insert(new Material("Cg1", id, MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, now, now + 1_000_000));
    }

    private static void stubContent(String id, int status, byte[] body) {
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/" + id + "/content"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "image/jpeg").withBody(body)));
    }

    private MediaStatus status(String id) {
        return materials.find("Cg1", id).map(Material::mediaStatus).orElseThrow();
    }

    @Test
    void storesTheImageAndItsHash() throws Exception {
        stubContent("200", 200, "abc".getBytes());
        pendingImage("200");

        mediaStore.download("Cg1", "200");

        Material material = materials.find("Cg1", "200").orElseThrow();
        assertThat(material.mediaStatus()).isEqualTo(MediaStatus.STORED);
        assertThat(material.mediaSha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Files.readString(mediaStore.storedFile("Cg1", "200").orElseThrow())).isEqualTo("abc");
    }

    @Test
    void rejectsImagesOverTheSizeLimit() {
        stubContent("201", 200, new byte[2048]);
        pendingImage("201");

        mediaStore.download("Cg1", "201");

        assertThat(status("201")).isEqualTo(MediaStatus.TOO_LARGE);
        assertThat(mediaStore.storedFile("Cg1", "201")).isEmpty();
    }

    @Test
    void retriesNotFoundAndEmptyContentThenGivesUp() {
        stubContent("202", 404, new byte[0]);
        stubContent("203", 200, new byte[0]);
        pendingImage("202");
        pendingImage("203");

        mediaStore.download("Cg1", "202");
        mediaStore.download("Cg1", "203");
        assertThat(status("202")).isEqualTo(MediaStatus.PENDING);
        assertThat(status("203")).isEqualTo(MediaStatus.PENDING);

        mediaStore.download("Cg1", "202");
        mediaStore.download("Cg1", "203");
        assertThat(status("202")).isEqualTo(MediaStatus.FAILED);
        assertThat(status("203")).isEqualTo(MediaStatus.FAILED);
    }

    @Test
    void refusesMessageIdsThatAreNotNumeric() {
        assertThatThrownBy(() -> mediaStore.pathFor("Cg1", "../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesStoredFiles() {
        stubContent("204", 200, "abc".getBytes());
        pendingImage("204");
        mediaStore.download("Cg1", "204");

        mediaStore.delete("Cg1", "204");

        assertThat(mediaStore.storedFile("Cg1", "204")).isEmpty();
    }

    @Test
    void imageWebhookIsDownloadedInTheBackground() throws Exception {
        stubContent("205", 200, "abc".getBytes());

        postWebhook(body(groupImage("e1", "Cg1", "205")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status("205")).isEqualTo(MediaStatus.STORED));
    }
}
```

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest=MediaStoreTest`
Expected: 編譯失敗。

- [ ] **Step 3：實作**

`media/MediaTooLargeException.java`：

```java
package com.elvischang.ailinebot.media;

import java.io.IOException;

public class MediaTooLargeException extends IOException {

    public MediaTooLargeException(long limit) {
        super("media exceeds " + limit + " bytes");
    }
}
```

`media/MediaStore.java`：

```java
package com.elvischang.ailinebot.media;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.linecorp.bot.client.base.BlobContent;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class MediaStore {

    private static final Logger log = LoggerFactory.getLogger(MediaStore.class);
    private static final Pattern MESSAGE_ID = Pattern.compile("^[0-9]{1,32}$");
    private static final int BUFFER_SIZE = 8192;

    private final MaterialRepository materials;
    private final MessagingApiBlobClient blobClient;
    private final BotProperties props;

    public MediaStore(MaterialRepository materials, MessagingApiBlobClient blobClient, BotProperties props) {
        this.materials = materials;
        this.blobClient = blobClient;
        this.props = props;
    }

    public void download(String groupId, String messageId) {
        Optional<Material> material = materials.find(groupId, messageId);
        if (material.isEmpty() || material.get().mediaStatus() != MediaStatus.PENDING) {
            return;
        }
        Path target = pathFor(groupId, messageId);
        try {
            BlobContent content = blobClient.getMessageContent(messageId)
                    .get(props.mediaDownloadTimeout().toMillis(), TimeUnit.MILLISECONDS).body();
            String sha256 = writeLimited(content, target);
            materials.markMedia(groupId, messageId, MediaStatus.STORED, sha256);
        } catch (MediaTooLargeException e) {
            materials.markMedia(groupId, messageId, MediaStatus.TOO_LARGE, null);
            log.info("media too large groupHash={} messageId={}", Hashes.shortHash(groupId), messageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            int attempts = materials.recordMediaFailure(groupId, messageId, props.mediaMaxAttempts());
            log.warn("media download failed groupHash={} messageId={} attempts={} cause={}",
                    Hashes.shortHash(groupId), messageId, attempts, e.getClass().getSimpleName());
        }
    }

    public Optional<Path> storedFile(String groupId, String messageId) {
        Path path = pathFor(groupId, messageId);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    public void delete(String groupId, String messageId) {
        try {
            Files.deleteIfExists(pathFor(groupId, messageId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path pathFor(String groupId, String messageId) {
        if (messageId == null || !MESSAGE_ID.matcher(messageId).matches()) {
            throw new IllegalArgumentException("invalid LINE message id");
        }
        return props.mediaDir().resolve(Hashes.shortHash(groupId)).resolve(messageId);
    }

    private String writeLimited(BlobContent content, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "download-", ".part");
        try (InputStream in = content.byteStream();
             DigestOutputStream out = new DigestOutputStream(Files.newOutputStream(temp), sha256())) {
            long total = copyLimited(in, out);
            if (total == 0) {
                throw new IOException("empty media content");
            }
            out.flush();
            String hash = HexFormat.of().formatHex(out.getMessageDigest().digest());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return hash;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private long copyLimited(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > props.mediaMaxBytes()) {
                throw new MediaTooLargeException(props.mediaMaxBytes());
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
```

`job/JobEventListener.java` 加入 `MediaStore` 依賴與 handler：

```java
package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.media.MediaStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class JobEventListener {

    private final JobRunner runner;
    private final MediaStore mediaStore;

    public JobEventListener(JobRunner runner, MediaStore mediaStore) {
        this.runner = runner;
        this.mediaStore = mediaStore;
    }

    @Async("jobExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onJobReady(JobReadyEvent event) {
        runner.run(event.jobId());
    }

    @Async("mediaExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void onImageReceived(ImageReceivedEvent event) {
        mediaStore.download(event.groupId(), event.messageId());
    }
}
```

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest=MediaStoreTest`
Expected: `Tests run: 6, Failures: 0, Errors: 0`。

- [ ] **Step 5：Commit**

```bash
git add src
git commit -m "feat: download group images with size limit, safe paths, and retries"
```

---

### Task 12：補救掃描與每日清理

**Files:**
- Create: `config/SchedulingConfig.java`、`job/JobRecoveryScheduler.java`、`job/CleanupScheduler.java`
- Test: `job/JobRecoverySchedulerTest.java`、`job/CleanupSchedulerTest.java`

**Interfaces:**
- Consumes：`JobRepository`、`MaterialRepository`、`InboundEventRepository`、`MediaStore`、`ApplicationEventPublisher`、`BotProperties`、`Clock`
- Produces：`JobRecoveryScheduler.onStartup()`（`SENDING_REPLY` → `UNKNOWN`，再跑一次 `sweep()`）、`JobRecoveryScheduler.sweep()`、`CleanupScheduler.cleanup()`

- [ ] **Step 1：寫失敗的測試**

`src/test/java/com/elvischang/ailinebot/job/JobRecoverySchedulerTest.java`：

```java
package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.JobStatus;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobRecoverySchedulerTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    @Autowired
    JobRecoveryScheduler recovery;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    private String factCheckJob() {
        long now = clock.millis();
        materials.insert(new Material("Cg1", "99", MaterialKind.TEXT, "x", false, MediaStatus.NONE, null, 0, now, now + 1_000_000));
        return jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "100", "99", "q", "rt-1", "qt-100", null), now);
    }

    private Job job(String id) {
        return jobs.find(id).orElseThrow();
    }

    @Test
    void rerunsJobsWhoseLeaseExpired() {
        stubReplyOk("900");
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 1_000);
        clock.advance(Duration.ofSeconds(2));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job(id).sendState()).isEqualTo(SendState.REPLIED));
        assertThat(factChecks.calls()).containsExactly("99");
    }

    @Test
    void givesUpAfterMaxAttemptsAndSendsTheFailureNotice() {
        stubReplyOk("900");
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 1_000);
        jobs.release(id, "TIMEOUT");
        jobs.claim(id, clock.millis(), 1_000);
        clock.advance(Duration.ofSeconds(2));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job(id).sendState()).isEqualTo(SendState.REPLIED));
        assertThat(job(id).status()).isEqualTo(JobStatus.FAILED);
        assertThat(factChecks.calls()).isEmpty();
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.JOB_FAILED))));
    }

    @Test
    void sendsFinishedResultsThatWereNeverSent() {
        stubReplyOk("900");
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 60_000);
        jobs.complete(id, "結果", clock.millis());
        clock.advance(Duration.ofSeconds(31));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job(id).sendState()).isEqualTo(SendState.REPLIED));
    }

    @Test
    void startupTreatsInFlightRepliesAsUnknownAndDoesNotResend() {
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 60_000);
        jobs.complete(id, "結果", clock.millis());
        jobs.setSendState(List.of(id), SendState.SENDING_REPLY);

        recovery.onStartup();

        assertThat(job(id).sendState()).isEqualTo(SendState.UNKNOWN);
        LINE.verify(0, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void retriesPendingImageDownloads() {
        long now = clock.millis();
        materials.insert(new Material("Cg1", "300", MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, now, now + 1_000_000));
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/300/content")).willReturn(aResponse().withStatus(200).withBody("abc")));
        clock.advance(Duration.ofSeconds(61));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(materials.find("Cg1", "300").orElseThrow().mediaStatus()).isEqualTo(MediaStatus.STORED));
    }
}
```

`job/CleanupSchedulerTest.java`：

```java
package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.store.InboundEventRepository;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CleanupSchedulerTest extends IntegrationTestSupport {

    @Autowired
    CleanupScheduler cleanup;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    @Autowired
    InboundEventRepository inboundEvents;

    @Autowired
    MediaStore mediaStore;

    @Test
    void removesExpiredMaterialsFilesWaitingResultsAndOldRows() throws Exception {
        long start = clock.millis();
        materials.insert(new Material("Cg1", "200", MaterialKind.IMAGE, null, false, MediaStatus.STORED, "h", 0, start, start + 1000));
        var file = mediaStore.pathFor("Cg1", "200");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "abc");
        inboundEvents.insertIfAbsent("e1", start, false);

        String waiting = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "1", "1", "q", "rt", "qt", null), start);
        jobs.claim(waiting, start, 1000);
        jobs.complete(waiting, "結果", start);
        jobs.markWaiting(List.of(waiting), start);

        clock.advance(Duration.ofDays(8));
        cleanup.cleanup();

        assertThat(materials.find("Cg1", "200")).isEmpty();
        assertThat(Files.exists(file)).isFalse();
        assertThat(jobs.find(waiting).orElseThrow().sendState()).isEqualTo(SendState.EXPIRED);
        assertThat(jobs.find(waiting).orElseThrow().resultText()).isNull();
        assertThat(inboundEvents.insertIfAbsent("e1", clock.millis(), false)).isTrue();

        clock.advance(Duration.ofDays(91));
        cleanup.cleanup();
        assertThat(jobs.find(waiting)).isEmpty();
    }
}
```

- [ ] **Step 2：確認失敗**

Run: `$MVN test -Dtest='JobRecoverySchedulerTest,CleanupSchedulerTest'`
Expected: 編譯失敗。

- [ ] **Step 3：實作**

`config/SchedulingConfig.java`：

```java
package com.elvischang.ailinebot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bot.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
```

`job/JobRecoveryScheduler.java`：

```java
package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class JobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobRecoveryScheduler.class);
    private static final String LEASE_EXPIRED = "LEASE_EXPIRED";

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final ApplicationEventPublisher publisher;
    private final BotProperties props;
    private final Clock clock;

    public JobRecoveryScheduler(JobRepository jobs, MaterialRepository materials, ApplicationEventPublisher publisher,
                                BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int unknown = jobs.resetSendingToUnknown();
        if (unknown > 0) {
            log.warn("startup marked {} in-flight replies as UNKNOWN", unknown);
        }
        sweep();
    }

    @Scheduled(fixedDelayString = "${bot.recovery-interval-ms:60000}")
    public void sweep() {
        long now = clock.millis();
        long staleBefore = now - props.staleAfter().toMillis();
        for (Job job : jobs.expiredLeases(now)) {
            if (job.attempts() >= props.jobMaxAttempts()) {
                jobs.fail(job.id(), BotReplies.JOB_FAILED, LEASE_EXPIRED, now);
            } else {
                jobs.release(job.id(), LEASE_EXPIRED);
            }
            publisher.publishEvent(new JobReadyEvent(job.id()));
        }
        jobs.stalePending(staleBefore).forEach(id -> publisher.publishEvent(new JobReadyEvent(id)));
        jobs.unsentFinished(staleBefore).forEach(id -> publisher.publishEvent(new JobReadyEvent(id)));
        long mediaStaleBefore = now - props.staleAfter().toMillis() * 2;
        for (Material material : materials.pendingMedia(mediaStaleBefore)) {
            publisher.publishEvent(new ImageReceivedEvent(material.groupId(), material.messageId()));
        }
    }
}
```

`job/CleanupScheduler.java`：

```java
package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.store.InboundEventRepository;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final MaterialRepository materials;
    private final JobRepository jobs;
    private final InboundEventRepository inboundEvents;
    private final MediaStore mediaStore;
    private final BotProperties props;
    private final Clock clock;

    public CleanupScheduler(MaterialRepository materials, JobRepository jobs, InboundEventRepository inboundEvents,
                            MediaStore mediaStore, BotProperties props, Clock clock) {
        this.materials = materials;
        this.jobs = jobs;
        this.inboundEvents = inboundEvents;
        this.mediaStore = mediaStore;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${bot.cleanup-cron:0 30 3 * * *}", zone = "Asia/Taipei")
    public void cleanup() {
        long now = clock.millis();
        for (Material material : materials.expired(now)) {
            try {
                mediaStore.delete(material.groupId(), material.messageId());
            } catch (IllegalArgumentException e) {
                log.debug("material {} has no media file", material.messageId());
            }
        }
        int deletedMaterials = materials.deleteExpired(now);
        int expiredWaiting = jobs.expireWaiting(now - props.waitingTtl().toMillis());
        int clearedJobs = jobs.clearContent(now - props.materialTtl().toMillis());
        int deletedJobs = jobs.deleteOlderThan(now - props.jobRetention().toMillis());
        int deletedEvents = inboundEvents.deleteOlderThan(now - props.materialTtl().toMillis());
        log.info("cleanup materials={} expiredWaiting={} clearedJobs={} deletedJobs={} deletedEvents={}",
                deletedMaterials, expiredWaiting, clearedJobs, deletedJobs, deletedEvents);
    }
}
```

`CleanupScheduler` 刪檔時，文字素材的 `messageId` 也是數字，`pathFor` 不會丟例外，`deleteIfExists` 找不到檔案就略過。`IllegalArgumentException` 的 catch 是給 `BOT_REPLY` 這類 id 格式不保證的列。

- [ ] **Step 4：確認通過**

Run: `$MVN test -Dtest='JobRecoverySchedulerTest,CleanupSchedulerTest'`
Expected: `Tests run: 6, Failures: 0, Errors: 0`。

- [ ] **Step 5：Commit**

```bash
git add src
git commit -m "feat: recover stuck jobs on a schedule and clean up expired data daily"
```

---

### Task 13：移除舊路徑、整理設定、驗收測試

**Files:**
- Delete: `service/LineMessageService.java`、`service/OllamaService.java`、`service/OpenAIService.java`、`helper/DefaultTextCommand.java`、`helper/ImagineCommand.java`、`helper/LineCommandFactory.java`、`helper/LineCommandStrategy.java`
- Modify: `src/main/resources/application.yaml`
- Test: `AcceptanceTest.java`

**Interfaces:**
- Consumes：前面所有 task。
- Produces：沒有新介面。完成後 `src/main` 不再有 Ollama、翻譯 prompt、同步回覆的程式。

- [ ] **Step 1：寫驗收測試**

`src/test/java/com/elvischang/ailinebot/AcceptanceTest.java`：

```java
package com.elvischang.ailinebot;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupAudio;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.elvischang.ailinebot.support.Webhooks.userText;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AcceptanceTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    private SendState sendStateOf(String triggerMessageId) {
        return jdbc.sql("SELECT send_state FROM job WHERE trigger_message_id = :m").param("m", triggerMessageId)
                .query(String.class).optional().map(SendState::valueOf).orElse(null);
    }

    @Test
    void plainGroupMessagesAndOneOnOneChatsNeverCallTheModelOrLine() throws Exception {
        postWebhook(body(groupText("e1", "Cg1", "100", "轉傳的訊息"), groupAudio("e2", "Cg1", "101"),
                userText("e3", "U1", "102", "私訊")));

        Thread.sleep(300);
        assertThat(factChecks.calls()).isEmpty();
        LINE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void quotingAMessageFromAnotherGroupIsNotFound() throws Exception {
        stubReplyOk("900");
        postWebhook(body(groupText("e1", "Cg1", "99", "A 群的訊息")));
        postWebhook(body(groupMention("e2", "Cg2", "100", "這是真的嗎", "99")));

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        assertThat(factChecks.calls()).isEmpty();
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.QUOTE_NOT_FOUND))));
    }

    @Test
    void redeliveredWebhookIsAnsweredOnce() throws Exception {
        stubReplyOk("900");
        String webhook = body(groupText("e1", "Cg1", "99", "轉傳的訊息"), groupMention("e2", "Cg1", "100", "這是真的嗎", "99"));
        postWebhook(webhook);
        postWebhook(webhook);

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        Thread.sleep(300);
        assertThat(factChecks.calls()).containsExactly("99");
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void twoMessagesArrivingTogetherDeliverAWaitingResultOnce() throws Exception {
        stubReplyOk("900");
        factChecks.behave(m -> {
            clock.advance(Duration.ofSeconds(60));
            return "SLOW";
        });
        postWebhook(body(groupText("e1", "Cg1", "99", "轉傳的訊息")));
        postWebhook(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99")));
        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.WAITING_NEXT);

        var pool = Executors.newFixedThreadPool(2);
        List<Callable<Void>> posts = List.of(
                () -> { postWebhook(body(groupText("e3", "Cg1", "101", "早安"))); return null; },
                () -> { postWebhook(body(groupText("e4", "Cg1", "102", "午安"))); return null; });
        for (var future : pool.invokeAll(posts)) {
            future.get();
        }
        pool.shutdown();

        await().atMost(Duration.ofSeconds(5)).until(() -> sendStateOf("100") == SendState.REPLIED);
        Thread.sleep(300);
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)).withRequestBody(containing("SLOW")));
    }
}
```

- [ ] **Step 2：跑驗收測試**

Run: `$MVN test -Dtest=AcceptanceTest`
Expected: 全部通過（行為在前面的 task 已經完成，這一步是把需求文件的驗收清單集中驗一次）。若有失敗，回到對應 task 修正，不要改測試期望。

- [ ] **Step 3：刪除舊程式與設定**

刪檔：

```bash
git rm src/main/java/com/elvischang/ailinebot/service/LineMessageService.java \
       src/main/java/com/elvischang/ailinebot/service/OllamaService.java \
       src/main/java/com/elvischang/ailinebot/service/OpenAIService.java \
       src/main/java/com/elvischang/ailinebot/helper/DefaultTextCommand.java \
       src/main/java/com/elvischang/ailinebot/helper/ImagineCommand.java \
       src/main/java/com/elvischang/ailinebot/helper/LineCommandFactory.java \
       src/main/java/com/elvischang/ailinebot/helper/LineCommandStrategy.java
```

`application.yaml`：刪掉整段 `openai:`（翻譯 system prompt）、整段 `ollama:`、以及 `line.bot.handler`（`path: /callback`）。

- [ ] **Step 4：確認沒有殘留**

Run: `grep -rn -E "Ollama|ollama|system-prompt|LineCommand|handler:|pushMessage|multicast|broadcast" src/main || echo "clean"`
Expected: 印出 `clean`。

- [ ] **Step 5：全部測試**

Run: `$MVN clean test`
Expected: 全部通過，BUILD SUCCESS。記下總測試數，交付報告要用。

- [ ] **Step 6：Commit**

```bash
git add -A
git commit -m "refactor: remove translator, Ollama, and synchronous reply paths"
```

---

## 完成後的交付檢查

- [ ] `$MVN clean test` 全綠，貼出 `Tests run:` 那一行。
- [ ] `git log --oneline main..HEAD` 每個 task 一個 commit。
- [ ] 回報 Plan 1 沒做的事：真正的查證（Plan 2）、Docker／Caddy／CI/CD（Plan 3）、LINE 測試 channel 的實測項目（spec §18）。
