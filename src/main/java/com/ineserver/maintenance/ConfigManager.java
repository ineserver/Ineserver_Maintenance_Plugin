package com.ineserver.maintenance;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final Path dataDirectory;
    private final Logger logger;
    private Map<String, Object> config;

    public ConfigManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public void loadConfig() throws IOException {
        // データディレクトリの作成
        if (!Files.exists(dataDirectory)) {
            Files.createDirectories(dataDirectory);
        }

        File configFile = dataDirectory.resolve("config.yml").toFile();

        // 設定ファイルが存在しない場合は作成
        if (!configFile.exists()) {
            createDefaultConfig(configFile);
        }

        // 設定ファイルの読み込み
        Yaml yaml = new Yaml();
        try (InputStream inputStream = new FileInputStream(configFile)) {
            config = yaml.load(inputStream);
        }

        logger.info("Configuration loaded successfully.");
    }

    private void createDefaultConfig(File configFile) throws IOException {
        StringBuilder config = new StringBuilder();
        
        config.append("# Google Calendar連携設定\n");
        config.append("google-calendar:\n");
        config.append("  # Google Calendar連携の有効/無効\n");
        config.append("  enabled: false\n");
        config.append("  # Google Calendar APIキー（必須）\n");
        config.append("  api-key: YOUR_API_KEY_HERE\n");
        config.append("  # カレンダーID\n");
        config.append("  # 例: \"abcd1234@group.calendar.google.com\"\n");
        config.append("  calendar-id: primary\n");
        config.append("  # カレンダーチェック間隔（分）\n");
        config.append("  check-interval-minutes: 1\n");
        config.append("\n");
        
        config.append("# Discord通知設定\n");
        config.append("discord:\n");
        config.append("  # DiscordのWebhook URL\n");
        config.append("  webhook-url: https://discord.com/api/webhooks/YOUR_WEBHOOK_ID/YOUR_WEBHOOK_TOKEN\n");
        config.append("  # Discord通知の有効/無効\n");
        config.append("  enabled: true\n");
        config.append("\n");
        
        config.append("# メンテナンス設定\n");
        config.append("maintenance:\n");
        config.append("  # メンテナンス中のキックメッセージ\n");
        config.append("  kick-message: |-\n");
        config.append("    §c§lサーバーメンテナンス中です\n");
        config.append("    §e終了までお待ちください\n");
        config.append("\n");
        
        config.append("# 通知設定\n");
        config.append("notifications:\n");
        config.append("  # ログイン時のメンテナンス通知を有効化\n");
        config.append("  login-notification: true\n");
        config.append("  # メンテナンス前の通知タイミング（分単位）\n");
        config.append("  # プレイヤーに指定した時間前に通知が送信されます\n");
        config.append("  notification-times-minutes:\n");
        config.append("    - 360  # 6時間前\n");
        config.append("    - 300  # 5時間前\n");
        config.append("    - 240  # 4時間前\n");
        config.append("    - 180  # 3時間前\n");
        config.append("    - 120  # 2時間前\n");
        config.append("    - 60   # 1時間前\n");
        config.append("    - 30   # 30分前\n");
        config.append("    - 20   # 20分前\n");
        config.append("    - 10   # 10分前\n");
        config.append("    - 5    # 5分前\n");
        config.append("    - 3    # 3分前\n");
        config.append("    - 1    # 1分前\n");
        config.append("  # メンテナンス30秒前の通知を有効化\n");
        config.append("  30-seconds-before: true\n");
        
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(config.toString());
        }

        logger.info("Default configuration file created.");
    }

    public boolean isGoogleCalendarEnabled() {
        return getBoolean("google-calendar.enabled", false);
    }

    public String getGoogleCalendarApiKey() {
        return getString("google-calendar.api-key", "");
    }

    public String getGoogleCalendarId() {
        return getString("google-calendar.calendar-id", "primary");
    }

    public int getGoogleCalendarCheckInterval() {
        return getInt("google-calendar.check-interval-minutes", 30);
    }

    public String getDiscordWebhookUrl() {
        return getString("discord.webhook-url", "");
    }

    public boolean isDiscordEnabled() {
        return getBoolean("discord.enabled", true);
    }

    public String getKickMessage() {
        return getString("maintenance.kick-message", "§cサーバーメンテナンス中です。");
    }

    public boolean isLoginNotificationEnabled() {
        return getBoolean("notifications.login-notification", true);
    }

    @SuppressWarnings("unchecked")
    public List<Integer> getNotificationTimes() {
        Object value = getConfigValue("notifications.notification-times-minutes");
        if (value instanceof List) {
            return (List<Integer>) value;
        }
        return new ArrayList<>();
    }

    public boolean is30SecondsNotificationEnabled() {
        return getBoolean("notifications.30-seconds-before", true);
    }

    private Object getConfigValue(String path) {
        String[] keys = path.split("\\.");
        Object current = config;

        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                return null;
            }
        }

        return current;
    }

    private String getString(String path, String defaultValue) {
        Object value = getConfigValue(path);
        return value != null ? value.toString() : defaultValue;
    }

    private int getInt(String path, int defaultValue) {
        Object value = getConfigValue(path);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private boolean getBoolean(String path, boolean defaultValue) {
        Object value = getConfigValue(path);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }
}
