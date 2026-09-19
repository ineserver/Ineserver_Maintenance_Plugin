package com.ineserver.maintenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DiscordNotifier {

    /** 変更前後のメンテナンス予定 */
    public record EventUpdate(MaintenanceEvent before, MaintenanceEvent after) {
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final String CALENDAR_URL = "https://calendar.google.com/calendar/u/0?cid=dnFobnRpa2FsOXU1OWE1Ym1hOWphdmNjcWdAZ3JvdXAuY2FsZW5kYXIuZ29vZ2xlLmNvbQ";
    private static final String FOOTER_TEXT = "Ineserver Maintenance Plugin";

    private static final int COLOR_SCHEDULED = 0xE67E22; // オレンジ
    private static final int COLOR_UPDATED = 0xF1C40F; // 黄色
    private static final int COLOR_CANCELLED = 0x95A5A6; // 灰色
    private static final int COLOR_STARTED = 0xE74C3C; // 赤色
    private static final int COLOR_ENDED = 0x2ECC71; // 緑色
    private static final int COLOR_SCHEDULE_LIST = 0x5865F2; // 青色

    // Discordの制限: 1メッセージにつき埋め込み10個まで・合計6000文字まで、説明文は4096文字まで
    private static final int MAX_EMBEDS_PER_MESSAGE = 10;
    private static final int MAX_CHARS_PER_MESSAGE = 5800;
    private static final int MAX_DESCRIPTION_LENGTH = 4000;
    // 予定の詳細は長くなりやすいため、この文字数で省略する
    private static final int MAX_EVENT_DESCRIPTION_LENGTH = 800;
    private static final int MAX_SCHEDULE_LIST_ITEMS = 10;

    private static final DateTimeFormatter DATE_WITH_YEAR = DateTimeFormatter.ofPattern("yyyy/MM/dd(E)", Locale.JAPANESE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd(E)", Locale.JAPANESE);
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern HTML_TAG = Pattern.compile("<[a-zA-Z/!][^>]*>");
    private static final Pattern HTML_LINK = Pattern.compile("<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private final ConfigManager configManager;
    private final Logger logger;
    private final OkHttpClient httpClient;

    public DiscordNotifier(ConfigManager configManager, Logger logger) {
        this.configManager = configManager;
        this.logger = logger;
        this.httpClient = new OkHttpClient();
    }

    /**
     * カレンダーの同期で見つかった予定の追加・変更・中止を1つのメッセージにまとめて通知する。
     * 最後に、変更を反映した今後のメンテナンス予定の一覧を添える。
     *
     * @param schedule 変更を反映した後の予定(開始時刻順)
     */
    public void sendScheduleChanges(List<MaintenanceEvent> added, List<EventUpdate> updated,
            List<MaintenanceEvent> cancelled, List<MaintenanceEvent> schedule) {
        List<Embed> embeds = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        Set<String> updatedIds = new HashSet<>();

        for (MaintenanceEvent event : added) {
            embeds.add(buildScheduledEmbed(event));
            addedIds.add(event.getId());
        }
        for (EventUpdate update : updated) {
            Embed embed = buildUpdatedEmbed(update.before(), update.after());
            // 表示上の違いがない変更(説明文のHTMLの書式だけが変わった等)は通知しない
            if (embed != null) {
                embeds.add(embed);
                updatedIds.add(update.after().getId());
            }
        }
        for (MaintenanceEvent event : cancelled) {
            embeds.add(buildCancelledEmbed(event));
        }

        if (embeds.isEmpty()) {
            return;
        }

        // 予定が今回通知したもの1件だけの場合、一覧は内容が重複するため省略する
        boolean onlyNotifiedEvent = schedule.size() == 1
                && (addedIds.contains(schedule.get(0).getId()) || updatedIds.contains(schedule.get(0).getId()));
        if (!onlyNotifiedEvent) {
            embeds.add(buildScheduleListEmbed(schedule, addedIds, updatedIds));
        }

        embeds.get(embeds.size() - 1)
                .line("")
                .line("📅 [Googleカレンダーで予定を確認](" + CALENDAR_URL + ")");

        send(embeds);
    }

    public void sendMaintenanceStarted(MaintenanceEvent event) {
        Embed embed = new Embed("🚧 メンテナンスを開始しました", COLOR_STARTED)
                .line(bold(event.getTitle()))
                .line("")
                .line("🗓️ " + formatRange(event, true) + "（" + formatDuration(event) + "）")
                .line("⏳ " + relative(event.getEndTime()) + "に終了予定")
                .line("")
                .line("⚠️ メンテナンス中はサーバーにログインできません")
                .line("※ 作業の状況により、終了時刻が前後する場合があります");

        send(List.of(embed));
    }

    /**
     * @param event     終了したメンテナンス(不明な場合は null)
     * @param nextEvent 次のメンテナンス(ない場合は null)。既に開始時刻を過ぎていれば、続けて開始するものとして扱う
     */
    public void sendMaintenanceEnded(MaintenanceEvent event, MaintenanceEvent nextEvent) {
        Embed embed = new Embed("✅ メンテナンスが終了しました", COLOR_ENDED);
        if (event != null) {
            embed.line(bold(event.getTitle())).line("");
        }

        if (nextEvent != null && !nextEvent.getStartTime().isAfter(Instant.now())) {
            embed.line("続けて、次のメンテナンス「" + escapeMarkdown(nextEvent.getTitle()) + "」を開始します。");
        } else {
            embed.line("サーバーにログインできるようになりました。")
                    .line("ご協力ありがとうございました！");
            if (nextEvent != null) {
                embed.line("")
                        .line("📋 **次回のメンテナンス**")
                        .line(formatRange(nextEvent, true) + "（" + relative(nextEvent.getStartTime()) + "）")
                        .line("└ " + escapeMarkdown(nextEvent.getTitle()));
            }
        }

        send(List.of(embed));
    }

    private Embed buildScheduledEmbed(MaintenanceEvent event) {
        Embed embed = new Embed("🔧 メンテナンスが予定されました", COLOR_SCHEDULED)
                .line(bold(event.getTitle()))
                .line("")
                .line("🗓️ " + bold(formatRange(event, true)) + "（" + formatDuration(event) + "）")
                .line("⏳ " + relative(event.getStartTime()) + "に開始");

        String description = formatEventDescription(event.getDescription());
        if (!description.isEmpty()) {
            embed.line("")
                    .line("📝 **詳細**")
                    .line(quote(description));
        }

        return embed.line("")
                .line("⚠️ メンテナンス中はサーバーにログインできません");
    }

    /**
     * 変更された項目だけを変更前・変更後で表示する。
     *
     * @return 表示上の違いがない場合は null
     */
    private Embed buildUpdatedEmbed(MaintenanceEvent before, MaintenanceEvent after) {
        boolean timeChanged = !before.getStartTime().equals(after.getStartTime())
                || !before.getEndTime().equals(after.getEndTime());
        boolean titleChanged = !before.getTitle().equals(after.getTitle());
        String beforeDescription = formatEventDescription(before.getDescription());
        String afterDescription = formatEventDescription(after.getDescription());
        boolean descriptionChanged = !beforeDescription.equals(afterDescription);

        List<String> changedItems = new ArrayList<>();
        if (timeChanged) {
            changedItems.add("日時");
        }
        if (titleChanged) {
            changedItems.add("タイトル");
        }
        if (descriptionChanged) {
            changedItems.add("詳細");
        }
        if (changedItems.isEmpty()) {
            return null;
        }

        Embed embed = new Embed("🔄 メンテナンス予定が変更されました", COLOR_UPDATED)
                .line(bold(after.getTitle()))
                .line("変更点：**" + String.join("・", changedItems) + "**")
                .line("");

        if (timeChanged) {
            embed.line("🗓️ **日時**（変更あり）")
                    .line("~~" + formatRange(before, true) + "~~")
                    .line("→ " + bold(formatRange(after, true)) + "（" + formatDuration(after) + "）");
            describeTimeShift(before, after).forEach(embed::line);
        } else {
            embed.line("🗓️ **日時**")
                    .line(formatRange(after, true) + "（" + formatDuration(after) + "）");
        }
        embed.line("⏳ " + relative(after.getStartTime()) + "に開始");

        if (titleChanged) {
            embed.line("")
                    .line("📌 **タイトル**（変更あり）")
                    .line("~~" + escapeMarkdown(before.getTitle()) + "~~")
                    .line("→ " + bold(after.getTitle()));
        }

        if (descriptionChanged) {
            embed.line("")
                    .line("📝 **詳細**（変更あり）")
                    .line("変更前：")
                    .line(quote(beforeDescription.isEmpty() ? "（なし）" : beforeDescription))
                    .line("変更後：")
                    .line(quote(afterDescription.isEmpty() ? "（なし）" : afterDescription));
        }

        return embed;
    }

    /**
     * 開始・終了時刻がどれだけ動いたかを説明する。
     */
    private List<String> describeTimeShift(MaintenanceEvent before, MaintenanceEvent after) {
        Duration startShift = Duration.between(before.getStartTime(), after.getStartTime());
        Duration endShift = Duration.between(before.getEndTime(), after.getEndTime());

        List<String> lines = new ArrayList<>();
        if (startShift.equals(endShift)) {
            // 所要時間はそのままで、日程全体が移動した
            lines.add(startShift.isNegative()
                    ? "⏪ 日程が **" + formatDuration(startShift) + "** 前倒しになりました"
                    : "⏩ 日程が **" + formatDuration(startShift) + "** 後ろ倒しになりました");
            return lines;
        }

        if (!startShift.isZero()) {
            lines.add(startShift.isNegative()
                    ? "⏪ 開始が **" + formatDuration(startShift) + "** 早まりました"
                    : "⏩ 開始が **" + formatDuration(startShift) + "** 遅くなりました");
        }
        if (!endShift.isZero()) {
            lines.add(endShift.isNegative()
                    ? "⏪ 終了が **" + formatDuration(endShift) + "** 早まりました"
                    : "⏩ 終了が **" + formatDuration(endShift) + "** 遅くなりました");
        }
        return lines;
    }

    private Embed buildCancelledEmbed(MaintenanceEvent event) {
        return new Embed("❌ メンテナンスが中止されました", COLOR_CANCELLED)
                .line(bold(event.getTitle()))
                .line("以下の日程で予定されていたメンテナンスは中止になりました。")
                .line("")
                .line("🗓️ ~~" + formatRange(event, true) + "~~");
    }

    private Embed buildScheduleListEmbed(List<MaintenanceEvent> schedule, Set<String> addedIds,
            Set<String> updatedIds) {
        Embed embed = new Embed("📋 今後のメンテナンス予定", COLOR_SCHEDULE_LIST);

        if (schedule.isEmpty()) {
            return embed.line("現在、予定されているメンテナンスはありません。");
        }

        Instant now = Instant.now();
        int shown = Math.min(schedule.size(), MAX_SCHEDULE_LIST_ITEMS);
        for (MaintenanceEvent event : schedule.subList(0, shown)) {
            StringBuilder head = new StringBuilder(bold(formatRange(event, false)));
            if (!event.getStartTime().isAfter(now)) {
                head.append(" `実施中`");
            } else {
                head.append("（").append(relative(event.getStartTime())).append("）");
                if (addedIds.contains(event.getId())) {
                    head.append(" `新規`");
                } else if (updatedIds.contains(event.getId())) {
                    head.append(" `変更`");
                }
            }

            embed.line(head.toString())
                    .line("└ " + escapeMarkdown(event.getTitle()));
        }

        if (schedule.size() > shown) {
            embed.line("ほか " + (schedule.size() - shown) + " 件");
        }

        return embed;
    }

    private void send(List<Embed> embeds) {
        if (!configManager.isDiscordEnabled()) {
            return;
        }

        // Discordの制限に収まるよう、必要に応じて複数のメッセージに分けて送信する
        List<Embed> message = new ArrayList<>();
        int messageLength = 0;
        for (Embed embed : embeds) {
            int length = embed.length();
            if (!message.isEmpty()
                    && (message.size() >= MAX_EMBEDS_PER_MESSAGE || messageLength + length > MAX_CHARS_PER_MESSAGE)) {
                post(message);
                message = new ArrayList<>();
                messageLength = 0;
            }
            message.add(embed);
            messageLength += length;
        }
        if (!message.isEmpty()) {
            post(message);
        }
    }

    private void post(List<Embed> embeds) {
        String webhookUrl = configManager.getDiscordWebhookUrl();

        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logger.warn("Discord webhook URL is not configured");
            return;
        }

        String titles = embeds.stream().map(embed -> embed.title).collect(Collectors.joining(", "));

        try {
            JsonArray embedArray = new JsonArray();
            for (int i = 0; i < embeds.size(); i++) {
                // フッターと送信時刻は、メッセージの最後の埋め込みにだけ付ける
                embedArray.add(embeds.get(i).toJson(i == embeds.size() - 1));
            }

            JsonObject payload = new JsonObject();
            payload.add("embeds", embedArray);

            // リクエストの送信
            RequestBody body = RequestBody.create(payload.toString(), JSON);
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    logger.info("Discord notification sent successfully: " + titles);
                } else {
                    ResponseBody responseBody = response.body();
                    logger.error("Failed to send Discord notification (" + titles + "). Status: " + response.code()
                            + (responseBody != null ? ", Response: " + responseBody.string() : ""));
                }
            }

        } catch (IOException e) {
            logger.error("Error sending Discord notification", e);
        } catch (RuntimeException e) {
            // Webhook URLの形式不正など。通知の失敗でメンテナンス処理が中断されないようにする
            logger.error("Unexpected error sending Discord notification", e);
        }
    }

    /**
     * 予定の日時を「2026/09/20(日) 21:00 〜 23:00」の形式で表す。
     * 日をまたぐ場合は終了側にも日付を付ける。
     *
     * @param withYear false の場合は「9/20(日) 21:00 〜 23:00」のように年を省略する
     */
    static String formatRange(MaintenanceEvent event, boolean withYear) {
        ZonedDateTime start = event.getStartTime().atZone(ZONE);
        ZonedDateTime end = event.getEndTime().atZone(ZONE);

        LocalDate endDate = end.toLocalDate();
        String endTime = TIME.format(end);
        // 0:00ちょうどに終わる予定は前日の24:00として表示する(例: 21:00 〜 24:00、終日の予定は 00:00 〜 24:00)
        if (end.toLocalTime().equals(LocalTime.MIDNIGHT) && endDate.isAfter(start.toLocalDate())) {
            endDate = endDate.minusDays(1);
            endTime = "24:00";
        }

        String startText = (withYear ? DATE_WITH_YEAR : DATE_SHORT).format(start) + " " + TIME.format(start);
        if (endDate.equals(start.toLocalDate())) {
            return startText + " 〜 " + endTime;
        }

        DateTimeFormatter endDateFormat;
        if (!withYear) {
            endDateFormat = DATE_SHORT;
        } else if (endDate.getYear() == start.getYear()) {
            endDateFormat = DATE;
        } else {
            endDateFormat = DATE_WITH_YEAR;
        }
        return startText + " 〜 " + endDateFormat.format(endDate) + " " + endTime;
    }

    private static String formatDuration(MaintenanceEvent event) {
        return formatDuration(Duration.between(event.getStartTime(), event.getEndTime()));
    }

    /**
     * 期間を「1日2時間」「1時間30分」のように表す(負の値は絶対値で表す)。
     */
    static String formatDuration(Duration duration) {
        long totalMinutes = Math.abs(duration.toMinutes());
        long days = totalMinutes / (24 * 60);
        long hours = totalMinutes / 60 % 24;
        long minutes = totalMinutes % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("日");
        }
        if (hours > 0) {
            sb.append(hours).append("時間");
        }
        if (minutes > 0 || sb.length() == 0) {
            sb.append(minutes).append("分");
        }
        return sb.toString();
    }

    /**
     * Discordのタイムスタンプ記法。閲覧者の環境で「3日後」「2時間前」のように表示され、時間の経過に合わせて更新される。
     */
    private static String relative(Instant instant) {
        return "<t:" + instant.getEpochSecond() + ":R>";
    }

    private static String bold(String text) {
        return "**" + escapeMarkdown(text) + "**";
    }

    private static String escapeMarkdown(String text) {
        return text.replaceAll("([\\\\*_~`|])", "\\\\$1");
    }

    private static String quote(String text) {
        return Arrays.stream(text.split("\n", -1))
                .map(line -> "> " + line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Googleカレンダーの予定の説明をDiscord向けの文字列にする。
     * カレンダーの画面で書式を付けた説明はHTMLで返ってくるため、改行・太字・リンクを残してタグを取り除く。
     */
    static String formatEventDescription(String description) {
        String text = description.replace("\r\n", "\n");
        if (HTML_TAG.matcher(text).find()) {
            text = htmlToMarkdown(text);
        }
        text = text.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
        return truncate(text, MAX_EVENT_DESCRIPTION_LENGTH);
    }

    private static String htmlToMarkdown(String html) {
        Matcher linkMatcher = HTML_LINK.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (linkMatcher.find()) {
            String url = linkMatcher.group(1);
            String label = HTML_TAG.matcher(linkMatcher.group(2)).replaceAll("").strip();
            String link = label.isEmpty() || label.equals(url) ? url : "[" + label + "](" + url + ")";
            linkMatcher.appendReplacement(sb, Matcher.quoteReplacement(link));
        }
        linkMatcher.appendTail(sb);

        String text = sb.toString()
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])>", "\n")
                .replaceAll("(?i)<li[^>]*>", "・")
                .replaceAll("(?i)</?(b|strong)>", "**")
                .replaceAll("(?i)</?u>", "__");
        text = HTML_TAG.matcher(text).replaceAll("");
        return unescapeHtml(text);
    }

    private static String unescapeHtml(String text) {
        Matcher matcher = HTML_NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            try {
                int codePoint = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
                replacement = new String(Character.toChars(codePoint));
            } catch (IllegalArgumentException e) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString()
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = maxLength - 1;
        // サロゲートペア(絵文字など)の途中で切らない
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /**
     * Discordの埋め込み1つ分。説明文を1行ずつ組み立てる。
     */
    private static final class Embed {
        private final String title;
        private final int color;
        private final StringBuilder description = new StringBuilder();

        Embed(String title, int color) {
            this.title = title;
            this.color = color;
        }

        Embed line(String text) {
            if (description.length() > 0) {
                description.append('\n');
            }
            description.append(text);
            return this;
        }

        private String description() {
            return truncate(description.toString(), MAX_DESCRIPTION_LENGTH);
        }

        int length() {
            return title.length() + description().length() + FOOTER_TEXT.length();
        }

        JsonObject toJson(boolean withFooter) {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", title);
            embed.addProperty("description", description());
            embed.addProperty("color", color);

            if (withFooter) {
                embed.addProperty("timestamp", Instant.now().toString());
                JsonObject footer = new JsonObject();
                footer.addProperty("text", FOOTER_TEXT);
                embed.add("footer", footer);
            }
            return embed;
        }
    }
}
