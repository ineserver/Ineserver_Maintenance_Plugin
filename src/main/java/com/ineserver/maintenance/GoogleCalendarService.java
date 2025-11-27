package com.ineserver.maintenance;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import org.slf4j.Logger;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GoogleCalendarService {

    private static final String APPLICATION_NAME = "Ineserver Maintenance Plugin";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final ConfigManager configManager;
    private final MaintenanceManager maintenanceManager;
    private final Logger logger;

    private Calendar calendarService;
    private ScheduledExecutorService scheduler;
    private boolean initialized = false;

    public GoogleCalendarService(ConfigManager configManager, MaintenanceManager maintenanceManager,
            Logger logger) {
        this.configManager = configManager;
        this.maintenanceManager = maintenanceManager;
        this.logger = logger;
    }

    public void initialize() {
        if (!configManager.isGoogleCalendarEnabled()) {
            logger.info("Google Calendar integration is disabled in configuration.");
            return;
        }

        String apiKey = configManager.getGoogleCalendarApiKey();
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("YOUR_API_KEY_HERE")) {
            logger.error("Google Calendar API key is not configured!");
            logger.error("Please set 'google-calendar.api-key' in config.yml");
            logger.info("To get an API key:");
            logger.info("  1. Go to https://console.cloud.google.com/");
            logger.info("  2. Select your project (or create a new one)");
            logger.info("  3. Go to 'APIs & Services' -> 'Credentials'");
            logger.info("  4. Click 'Create Credentials' -> 'API Key'");
            logger.info("  5. Copy the API key and paste it in config.yml");
            return;
        }

        try {
            // HTTPトランスポートの構築
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            // Calendar APIサービスの構築（APIキー認証）
            calendarService = new Calendar.Builder(httpTransport, JSON_FACTORY, null)
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            initialized = true;
            logger.info("Google Calendar API initialized successfully with API key");

            // 定期的にカレンダーをチェック
            startScheduledCheck();

        } catch (GeneralSecurityException | IOException e) {
            logger.error("Failed to initialize Google Calendar API", e);
        }
    }

    private void startScheduledCheck() {
        scheduler = Executors.newScheduledThreadPool(1);

        int checkIntervalMinutes = configManager.getGoogleCalendarCheckInterval();

        // 初回チェック（起動後1分後）
        scheduler.schedule(this::checkCalendarEvents, 1, TimeUnit.MINUTES);

        // 定期チェック
        scheduler.scheduleAtFixedRate(this::checkCalendarEvents,
                checkIntervalMinutes, checkIntervalMinutes, TimeUnit.MINUTES);

        logger.info("Started scheduled calendar check (interval: " + checkIntervalMinutes + " minutes)");
    }

    private void checkCalendarEvents() {
        if (!initialized) {
            return;
        }

        try {
            String calendarId = configManager.getGoogleCalendarId();
            String apiKey = configManager.getGoogleCalendarApiKey();

            // 現在時刻から未来のイベントを取得
            DateTime now = new DateTime(System.currentTimeMillis());

            // 今後30日間のイベントを取得
            DateTime maxTime = new DateTime(System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000));

            Events events = calendarService.events().list(calendarId)
                    .setKey(apiKey) // APIキーを設定
                    .setTimeMin(now)
                    .setTimeMax(maxTime)
                    .setOrderBy("startTime")
                    .setSingleEvents(true)
                    .setMaxResults(10)
                    .execute();

            java.util.List<Event> items = events.getItems();

            if (items.isEmpty()) {
                logger.debug("No upcoming maintenance events found in calendar");
                // 空のリストで同期（これにより、既存のイベントが全てキャンセルされる可能性があるが、
                // 30日以内のイベントがないという意味なので、30日以内のものは消えるべき）
            }

            java.util.List<MaintenanceEvent> maintenanceEvents = new java.util.ArrayList<>();

            // 全てのイベントを処理
            for (Event event : items) {
                MaintenanceEvent maintenanceEvent = createMaintenanceEvent(event);
                if (maintenanceEvent != null) {
                    maintenanceEvents.add(maintenanceEvent);
                }
            }

            // 同期処理を実行
            maintenanceManager.syncGoogleCalendarEvents(maintenanceEvents);

        } catch (IOException e) {
            logger.error("Failed to fetch calendar events", e);
        }
    }

    private MaintenanceEvent createMaintenanceEvent(Event event) {
        try {
            String eventId = event.getId();
            String summary = event.getSummary();
            String description = event.getDescription() != null ? event.getDescription() : "";

            // 開始時刻の取得
            Instant startTime = getInstantFromEventDateTime(event.getStart());
            Instant endTime = getInstantFromEventDateTime(event.getEnd());

            if (startTime == null || endTime == null) {
                logger.warn("Event has invalid date/time: " + summary);
                return null;
            }

            Instant now = Instant.now();

            // 完全に過去のイベント（終了時刻も過去）はスキップ
            if (endTime.isBefore(now)) {
                return null;
            }

            // メンテナンスイベントを作成
            return new MaintenanceEvent(
                    eventId, summary, description, startTime, endTime);

        } catch (Exception e) {
            logger.error("Error processing calendar event", e);
            return null;
        }
    }

    private Instant getInstantFromEventDateTime(com.google.api.services.calendar.model.EventDateTime eventDateTime) {
        if (eventDateTime == null) {
            return null;
        }

        DateTime dateTime = eventDateTime.getDateTime();
        if (dateTime != null) {
            return Instant.ofEpochMilli(dateTime.getValue());
        }

        // 終日イベントの場合
        DateTime date = eventDateTime.getDate();
        if (date != null) {
            ZonedDateTime zonedDateTime = ZonedDateTime.of(
                    (int) date.getValue() / 10000,
                    ((int) date.getValue() / 100) % 100,
                    (int) date.getValue() % 100,
                    0, 0, 0, 0,
                    ZoneId.systemDefault());
            return zonedDateTime.toInstant();
        }

        return null;
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isInitialized() {
        return initialized;
    }
}
