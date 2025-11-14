package com.ineserver.maintenance;

import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DiscordNotifier {

    private final ConfigManager configManager;
    private final Logger logger;
    private final OkHttpClient httpClient;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public DiscordNotifier(ConfigManager configManager, Logger logger) {
        this.configManager = configManager;
        this.logger = logger;
        this.httpClient = new OkHttpClient();
    }

    public void sendMaintenanceScheduled(MaintenanceEvent event) {
        if (!configManager.isDiscordEnabled()) {
            return;
        }

        String title = "🔧 メンテナンスが予定されました";
        String description = String.format(
            "**開始時刻:** %s\n" +
            "**終了予定:** %s%s\n\n" +
            "⚠️ **メンテナンス実施中はサーバーにログインが出来ません**",
            formatDateTime(event.getStartTime()),
            formatDateTime(event.getEndTime()),
            event.getDescription().isEmpty() ? "" : "\n\n**詳細:** " + event.getDescription()
        );

        sendEmbed(title, description, 0xFFA500); // オレンジ色
    }

    public void sendMaintenanceStarted(MaintenanceEvent event) {
        if (!configManager.isDiscordEnabled()) {
            return;
        }

        String title = "🚧 メンテナンスを開始しました";
        String description = "現在メンテナンス中です。\n" +
                           "終了までしばらくお待ちください。\n\n" +
                           "⚠️ **メンテナンス実施中はサーバーにログインが出来ません**";

        sendEmbed(title, description, 0xFF0000); // 赤色
    }

    public void sendMaintenanceEnded(MaintenanceEvent event) {
        if (!configManager.isDiscordEnabled()) {
            return;
        }

        String title = "✅ メンテナンスが終了しました";
        String description = "メンテナンスが完了しました。\n" +
                           "ご協力ありがとうございました！";

        sendEmbed(title, description, 0x00FF00); // 緑色
    }

    private void sendEmbed(String title, String description, int color) {
        String webhookUrl = configManager.getDiscordWebhookUrl();
        
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logger.warn("Discord webhook URL is not configured");
            return;
        }

        try {
            // Embedオブジェクトの作成
            JsonObject embed = new JsonObject();
            embed.addProperty("title", title);
            embed.addProperty("description", description);
            embed.addProperty("color", color);
            embed.addProperty("timestamp", Instant.now().toString());

            // フッターの追加
            JsonObject footer = new JsonObject();
            footer.addProperty("text", "Ineserver Maintenance Plugin");
            embed.add("footer", footer);

            // 配列にEmbedを追加
            JsonObject payload = new JsonObject();
            com.google.gson.JsonArray embeds = new com.google.gson.JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            // リクエストの送信
            RequestBody body = RequestBody.create(payload.toString(), JSON);
            Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    logger.info("Discord notification sent successfully: " + title);
                } else {
                    logger.error("Failed to send Discord notification. Status: " + response.code());
                }
            }

        } catch (IOException e) {
            logger.error("Error sending Discord notification", e);
        }
    }

    private String formatDateTime(Instant instant) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")
                .withZone(ZoneId.of("Asia/Tokyo"));
        return formatter.format(instant);
    }
}
