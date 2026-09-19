package com.ineserver.maintenance;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

public class MaintenanceManager {

    // メンテナンス中でも接続を許可する権限
    public static final String BYPASS_PERMISSION = "maintenance.bypass";

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private final ProxyServer server;
    private final ConfigManager configManager;
    private final DiscordNotifier discordNotifier;
    private final Logger logger;
    private final MaintenanceStateManager stateManager;
    private LuckPerms luckPerms;

    private final List<MaintenanceEvent> scheduledMaintenances = Collections.synchronizedList(new ArrayList<>());
    private volatile MaintenanceEvent currentMaintenance;
    private volatile boolean maintenanceMode = false;
    private final Map<String, Boolean> discordNotificationSentMap = new ConcurrentHashMap<>();
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();
    // 終了済みイベント（イベントID -> 開始時刻）。予定終了前に終了したイベントがカレンダーから再取得されても再登録しない
    private final Map<String, Instant> completedEvents = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ScheduledFuture<?>>> scheduledNotifications = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledStartTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public MaintenanceManager(ProxyServer server, ConfigManager configManager,
            DiscordNotifier discordNotifier, Logger logger,
            MaintenanceStateManager stateManager) {
        this.server = server;
        this.configManager = configManager;
        this.discordNotifier = discordNotifier;
        this.logger = logger;
        this.stateManager = stateManager;

        // 保存されたメンテナンス状態を復元
        restoreMaintenanceState();
    }

    public void syncGoogleCalendarEvents(List<MaintenanceEvent> fetchedEvents) {
        synchronized (scheduledMaintenances) {
            Set<String> fetchedEventIds = new HashSet<>();

            for (MaintenanceEvent event : fetchedEvents) {
                fetchedEventIds.add(event.getId());
            }

            boolean stateChanged = false;
            // 今回の同期で見つかった変化(Discordへはまとめて通知する)
            List<MaintenanceEvent> newEvents = new ArrayList<>();
            List<DiscordNotifier.EventUpdate> updatedEvents = new ArrayList<>();
            List<MaintenanceEvent> cancelledEvents = new ArrayList<>();

            // 0. 予定終了時刻を過ぎたまま一度も開始されなかったイベントを削除
            if (removeExpiredEvents()) {
                stateChanged = true;
            }

            // 1. 削除されたイベントの検出と処理
            // スケジュール済みだが、今回取得したリストに含まれていないイベントを探す
            Iterator<MaintenanceEvent> iterator = scheduledMaintenances.iterator();
            while (iterator.hasNext()) {
                MaintenanceEvent existingEvent = iterator.next();
                String existingId = existingEvent.getId();

                // 既に終了したイベントは対象外（これらは自動的に削除されないため）
                // ただし、まだ開始していない、または進行中のイベントがカレンダーから消えた場合はキャンセル扱い
                if (!fetchedEventIds.contains(existingId)) {
                    // 予定終了後も延長中のメンテナンスは /maintenance end で終了するまで保持する
                    if (existingEvent.getEndTime().isBefore(Instant.now())) {
                        continue;
                    }

                    logger.info("Maintenance cancelled (removed from calendar): " + existingEvent.getTitle());
                    cancelledEvents.add(existingEvent);

                    // 通知・開始スケジュールのキャンセル
                    cancelEventSchedules(existingId);

                    // リストから削除
                    iterator.remove();
                    processedEventIds.remove(existingId);
                    discordNotificationSentMap.remove(existingId);
                    stateChanged = true;

                    // もし現在進行中のメンテナンスだった場合、メンテナンスモードを終了するか検討
                    // (安全のため、自動では終了せず、管理者に任せるか、あるいは終了させるか。ここでは終了させない)
                }
            }

            // 2. 新規・更新イベントの処理
            // カレンダーから取得されなくなった(予定終了時刻を過ぎた・削除された)終了済みイベントの記録を削除
            if (completedEvents.keySet().removeIf(id -> !fetchedEventIds.contains(id))) {
                stateChanged = true;
            }

            for (MaintenanceEvent fetchedEvent : fetchedEvents) {
                String eventId = fetchedEvent.getId();

                // 既に終了したメンテナンスは再登録しない
                Instant completedStartTime = completedEvents.get(eventId);
                if (completedStartTime != null) {
                    if (completedStartTime.equals(fetchedEvent.getStartTime())) {
                        continue;
                    }
                    // 開始時刻が変更された場合は、別日程のメンテナンスとして扱う
                    completedEvents.remove(eventId);
                    stateChanged = true;
                }

                // 既存イベントの検索
                MaintenanceEvent existingEvent = scheduledMaintenances.stream()
                        .filter(e -> e.getId().equals(eventId))
                        .findFirst()
                        .orElse(null);

                if (existingEvent == null) {
                    // 新規イベント
                    if (registerMaintenanceEvent(fetchedEvent)) {
                        newEvents.add(fetchedEvent);
                    }
                    stateChanged = true;
                } else {
                    // 更新チェック
                    if (!existingEvent.equals(fetchedEvent)) {
                        logger.info(
                                "Maintenance updated: " + existingEvent.getTitle() + " -> " + fetchedEvent.getTitle());
                        updatedEvents.add(new DiscordNotifier.EventUpdate(existingEvent, fetchedEvent));

                        // 古いイベントを削除して新しいイベントを追加
                        scheduledMaintenances.remove(existingEvent);
                        scheduledMaintenances.add(fetchedEvent);

                        // 延期前の通知・開始タスクが残らないようにする（再設定はDiscord通知の後に行う）
                        cancelEventSchedules(eventId);

                        // 実施中のメンテナンスであれば、変更後の内容を終了時の通知などに反映する
                        MaintenanceEvent current = currentMaintenance;
                        if (maintenanceMode && current != null && current.getId().equals(eventId)) {
                            currentMaintenance = fetchedEvent;
                        }

                        stateChanged = true;
                    }
                }
            }

            if (stateChanged) {
                // 開始時刻でソート
                scheduledMaintenances.sort(Comparator.comparing(MaintenanceEvent::getStartTime));
            }

            // 3. Discord通知 - 追加・変更・中止を、変更後の予定一覧とあわせて1つのメッセージで送る
            // (新規イベントは未通知のものだけ)
            List<MaintenanceEvent> unnotifiedEvents = newEvents.stream()
                    .filter(e -> !discordNotificationSentMap.getOrDefault(e.getId(), false))
                    .toList();
            if (!unnotifiedEvents.isEmpty() || !updatedEvents.isEmpty() || !cancelledEvents.isEmpty()) {
                discordNotifier.sendScheduleChanges(unnotifiedEvents, updatedEvents, cancelledEvents,
                        new ArrayList<>(scheduledMaintenances));
            }
            for (MaintenanceEvent event : newEvents) {
                discordNotificationSentMap.put(event.getId(), true);
            }

            // 4. 通知・開始のスケジュール設定
            // (予定の通知より後に行い、開始時刻を過ぎていて即座に開始する場合も「開始」の通知が後に届くようにする)
            for (MaintenanceEvent event : newEvents) {
                scheduleNotifications(event);
                scheduleMaintenanceStart(event);
            }
            for (DiscordNotifier.EventUpdate update : updatedEvents) {
                scheduleNotifications(update.after());
                scheduleMaintenanceStart(update.after());
            }

            if (stateChanged) {
                // 状態保存
                saveMaintenanceState();
            }
        }
    }

    /**
     * 新しく取得したイベントを予定に登録する。
     * Discord通知と、通知・開始のスケジュール設定は呼び出し元で行う。
     *
     * @return 登録した場合は true
     */
    private boolean registerMaintenanceEvent(MaintenanceEvent event) {
        String eventId = event.getId();

        // 予定終了時刻を過ぎたイベントは登録しない
        if (event.getEndTime().isBefore(Instant.now())) {
            return false;
        }

        // 以前の予定が残っていれば置き換える
        if (processedEventIds.contains(eventId)) {
            scheduledMaintenances.removeIf(e -> e.getId().equals(eventId));
            cancelEventSchedules(eventId);
        }

        processedEventIds.add(eventId);
        scheduledMaintenances.add(event);
        return true;
    }

    private void scheduleNotifications(MaintenanceEvent event) {
        String eventId = event.getId();
        Map<Integer, ScheduledFuture<?>> eventNotifications = scheduledNotifications.computeIfAbsent(eventId,
                k -> new ConcurrentHashMap<>());

        long now = System.currentTimeMillis();
        long maintenanceTime = event.getStartTime().toEpochMilli();

        // 設定ファイルから通知時間を取得
        List<Integer> notificationTimes = configManager.getNotificationTimes();

        for (int minutes : notificationTimes) {
            long notificationTime = maintenanceTime - (minutes * 60 * 1000L);
            long delay = notificationTime - now;

            if (delay > 0) {
                ScheduledFuture<?> future = scheduler.schedule(
                        () -> sendMaintenanceNotification(event, minutes),
                        delay,
                        TimeUnit.MILLISECONDS);

                eventNotifications.put(minutes, future);
            }
        }

        // 30秒前の通知
        if (configManager.is30SecondsNotificationEnabled()) {
            long notificationTime30s = maintenanceTime - 30000;
            long delay30s = notificationTime30s - now;

            if (delay30s > 0) {
                ScheduledFuture<?> future = scheduler.schedule(
                        () -> sendMaintenanceNotification(event, "30秒"),
                        delay30s,
                        TimeUnit.MILLISECONDS);

                eventNotifications.put(0, future);
            }
        }
    }

    private void scheduleMaintenanceStart(MaintenanceEvent event) {
        String eventId = event.getId();
        long now = System.currentTimeMillis();
        long startTime = event.getStartTime().toEpochMilli();
        long delay = startTime - now;

        // 同じイベントの古い開始タスクが残っていればキャンセル
        ScheduledFuture<?> previous = scheduledStartTasks.remove(eventId);
        if (previous != null) {
            previous.cancel(false);
        }

        if (delay > 0) {
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                synchronized (scheduledMaintenances) {
                    // 延期・キャンセルされた古い日程のタスクであれば開始しない
                    if (!isScheduledAt(event)) {
                        return;
                    }

                    // 最も早いイベントを現在のメンテナンスとして設定
                    // (他のメンテナンスが実施中の場合は、そちらの終了時に開始される)
                    if (!maintenanceMode) {
                        currentMaintenance = event;
                        startMaintenance();
                    }
                }
            }, delay, TimeUnit.MILLISECONDS);

            scheduledStartTasks.put(eventId, future);
        } else if (event.getEndTime().isAfter(Instant.now())) {
            // 開始時刻を過ぎてから把握した場合(カレンダーの確認が開始後だった・開始時刻にプロキシが停止していた等)でも、
            // 予定終了前であれば即座に開始する
            if (!maintenanceMode) {
                currentMaintenance = event;
                startMaintenance();
            }
        }
    }

    private void sendMaintenanceNotification(MaintenanceEvent event, int minutes) {
        // 本来の通知時刻を基準にして、実行の遅れで表示がずれないようにする
        Instant notificationTime = event.getStartTime().minus(Duration.ofMinutes(minutes));
        sendMaintenanceNotification(event, formatTimeUntil(event.getStartTime(), notificationTime));
    }

    private void sendMaintenanceNotification(MaintenanceEvent event, String timeStr) {
        String message = "§e§l[メンテナンス通知]\n" +
                "§f" + timeStr + "後にメンテナンスが開始されます。\n" +
                "§7タイトル: §f" + event.getTitle() + "\n" +
                "§7開始時刻: §f" + formatDateTime(event.getStartTime());

        Component component = LegacyComponentSerializer.legacySection().deserialize(message);

        for (Player player : server.getAllPlayers()) {
            // maintenance.notice.off権限を持つユーザーには通知しない
            if (!player.hasPermission("maintenance.notice.off")) {
                player.sendMessage(component);
            }
        }
    }

    private void startMaintenance() {
        startMaintenance(true);
    }

    private void startMaintenance(boolean sendNotifications) {
        maintenanceMode = true;

        logger.info("Maintenance mode activated");

        // 許可されていないプレイヤーを全員キック
        String kickMessage = configManager.getKickMessage();
        Component kickComponent = LegacyComponentSerializer.legacySection().deserialize(kickMessage);

        for (Player player : server.getAllPlayers()) {
            if (!isPlayerAllowed(player.getUsername())) {
                player.disconnect(kickComponent);
            }
        }

        // Discord通知 - メンテナンス開始（通知が有効な場合のみ）
        if (sendNotifications && currentMaintenance != null) {
            discordNotifier.sendMaintenanceStarted(currentMaintenance);
        }

        // メンテナンス状態を保存
        saveMaintenanceState();
    }

    /**
     * 実施中のメンテナンスを終了する。
     *
     * @return 予定時間中の次のメンテナンスを続けて開始した場合はそのイベント、それ以外は null
     */
    public MaintenanceEvent endMaintenance() {
        synchronized (scheduledMaintenances) {
            if (!maintenanceMode) {
                return null;
            }

            maintenanceMode = false;

            logger.info("Maintenance mode deactivated");

            MaintenanceEvent endedEvent = currentMaintenance;
            if (endedEvent != null) {
                // 終了したイベントのみを削除
                // (予定終了前に終了した場合、カレンダーから再取得されても再登録されないよう記録しておく)
                String eventId = endedEvent.getId();
                completedEvents.put(eventId, endedEvent.getStartTime());
                scheduledMaintenances.removeIf(e -> e.getId().equals(eventId));
                processedEventIds.remove(eventId);
                discordNotificationSentMap.remove(eventId);

                // 終了したイベントの通知・開始スケジュールのみをキャンセル
                cancelEventSchedules(eventId);

                currentMaintenance = null;
            }

            // 実施中に予定終了時刻を過ぎた(一度も開始されなかった)メンテナンスを削除
            removeExpiredEvents();

            MaintenanceEvent inProgressEvent = findInProgressEvent();

            // Discord通知 - メンテナンス終了(続けて開始するメンテナンス、なければ次回の予定を添える)
            discordNotifier.sendMaintenanceEnded(endedEvent,
                    inProgressEvent != null ? inProgressEvent : getNextMaintenanceEvent());

            // 実施中に開始時刻を迎えていたメンテナンスがあれば、続けて開始する
            if (inProgressEvent != null) {
                logger.info("Starting next maintenance that is already in progress: " + inProgressEvent.getTitle());
                currentMaintenance = inProgressEvent;
                startMaintenance();
                return inProgressEvent;
            }

            if (scheduledMaintenances.isEmpty()) {
                // 次のメンテナンスがない場合は全スケジュールをキャンセル
                cancelAllScheduledNotifications();
            }

            // 終了済みイベントの記録を残すため、状態はクリアせず保存する
            saveMaintenanceState();
            return null;
        }
    }

    /**
     * 開始時刻を過ぎていて予定終了時刻前のイベントのうち、最も早いものを返す。
     */
    private MaintenanceEvent findInProgressEvent() {
        Instant now = Instant.now();
        synchronized (scheduledMaintenances) {
            return scheduledMaintenances.stream()
                    .filter(e -> !e.getStartTime().isAfter(now) && e.getEndTime().isAfter(now))
                    .min(Comparator.comparing(MaintenanceEvent::getStartTime))
                    .orElse(null);
        }
    }

    /**
     * 予定終了時刻を過ぎたイベントを削除する(実施中のメンテナンスは除く)。
     * 他のメンテナンスの実施中に予定時間が過ぎ、一度も開始されなかったイベントが残り続けないようにする。
     *
     * @return 削除したイベントがあれば true
     */
    private boolean removeExpiredEvents() {
        Instant now = Instant.now();
        MaintenanceEvent current = currentMaintenance;
        boolean removed = false;

        synchronized (scheduledMaintenances) {
            Iterator<MaintenanceEvent> iterator = scheduledMaintenances.iterator();
            while (iterator.hasNext()) {
                MaintenanceEvent event = iterator.next();
                String eventId = event.getId();

                if (event.getEndTime().isAfter(now)) {
                    continue;
                }
                if (maintenanceMode && current != null && current.getId().equals(eventId)) {
                    continue;
                }

                logger.info("Removing expired maintenance that was never started: " + event.getTitle());
                iterator.remove();
                processedEventIds.remove(eventId);
                discordNotificationSentMap.remove(eventId);
                cancelEventSchedules(eventId);
                removed = true;
            }
        }

        return removed;
    }

    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    public void setLuckPerms(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    public boolean isPlayerAllowed(String username) {
        Optional<Player> playerOpt = server.getPlayer(username);
        if (playerOpt.isPresent()) {
            return isPlayerAllowed(playerOpt.get());
        }

        // プレイヤーがオンラインでない場合はLuckPermsでチェックできないためfalse
        return false;
    }

    public boolean isPlayerAllowed(Player player) {
        String username = player.getUsername();

        // maintenance.bypass 権限を持つプレイヤーは許可(LuckPermsのグループ継承も反映される)
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return true;
        }

        // 以下は従来の判定: LuckPermsの admin グループに直接所属しているプレイヤーを許可
        // LuckPermsが必須
        if (luckPerms == null) {
            logger.error("LuckPerms is not available! Cannot check permissions for " + username);
            return false;
        }

        try {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user != null) {
                // プライマリグループまたは継承グループに "admin" が含まれているかチェック
                String primaryGroup = user.getPrimaryGroup();
                if (primaryGroup != null && primaryGroup.equalsIgnoreCase("admin")) {
                    return true;
                }

                // 親グループをチェック
                boolean isInAdminGroup = user.getNodes().stream()
                        .filter(node -> node.getKey().startsWith("group."))
                        .anyMatch(node -> node.getKey().equalsIgnoreCase("group.admin"));

                return isInAdminGroup;
            } else {
                logger.warn("LuckPerms user data not found for " + username + " (UUID: " + player.getUniqueId() + ")");
                return false;
            }
        } catch (Exception e) {
            logger.error("Error checking LuckPerms permission for " + username, e);
            return false;
        }
    }

    public Component getKickMessage() {
        return LegacyComponentSerializer.legacySection()
                .deserialize(configManager.getKickMessage());
    }

    public void sendLoginNotification(Player player) {
        if (scheduledMaintenances.isEmpty() || !configManager.isLoginNotificationEnabled()) {
            return;
        }

        // maintenance.notice.off権限を持つユーザーには通知しない
        if (player.hasPermission("maintenance.notice.off")) {
            return;
        }

        Instant now = Instant.now();
        MaintenanceEvent nextEvent = getNextMaintenanceEvent();

        if (nextEvent != null && nextEvent.getStartTime().isAfter(now)) {
            String timeStr = formatTimeUntil(nextEvent.getStartTime(), now);

            String message = "§e§l[メンテナンスのお知らせ]\n" +
                    "§f" + timeStr + "後にメンテナンスが予定されています。\n" +
                    "§7タイトル: §f" + nextEvent.getTitle() + "\n" +
                    "§7開始時刻: §f" + formatDateTime(nextEvent.getStartTime());

            Component component = LegacyComponentSerializer.legacySection().deserialize(message);
            player.sendMessage(component);
        }
    }

    private void cancelEventSchedules(String eventId) {
        Map<Integer, ScheduledFuture<?>> eventNotifications = scheduledNotifications.remove(eventId);
        if (eventNotifications != null) {
            for (ScheduledFuture<?> future : eventNotifications.values()) {
                future.cancel(false);
            }
        }

        ScheduledFuture<?> startTask = scheduledStartTasks.remove(eventId);
        if (startTask != null) {
            startTask.cancel(false);
        }
    }

    private void cancelAllScheduledNotifications() {
        for (Map<Integer, ScheduledFuture<?>> eventNotifications : scheduledNotifications.values()) {
            for (ScheduledFuture<?> future : eventNotifications.values()) {
                future.cancel(false);
            }
        }
        scheduledNotifications.clear();

        for (ScheduledFuture<?> startTask : scheduledStartTasks.values()) {
            startTask.cancel(false);
        }
        scheduledStartTasks.clear();
    }

    /**
     * 指定したイベントが、同じ開始時刻のまま現在もスケジュールされているかを確認する。
     * 延期やキャンセルで置き換えられた古いイベントに対しては false を返す。
     */
    private boolean isScheduledAt(MaintenanceEvent event) {
        synchronized (scheduledMaintenances) {
            return scheduledMaintenances.stream()
                    .anyMatch(e -> e.getId().equals(event.getId())
                            && e.getStartTime().equals(event.getStartTime()));
        }
    }

    public void shutdown() {
        cancelAllScheduledNotifications();
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

    /**
     * 開始までの時間を「3日」「5時間」「30分」のように表す。
     * 24時間以上先は日数、24時間以内は時間、1時間未満は分で表す。
     */
    static String formatTimeUntil(Instant startTime, Instant now) {
        long minutes = Math.round(Duration.between(now, startTime).getSeconds() / 60.0);
        if (minutes < 60) {
            return Math.max(minutes, 1) + "分";
        }
        if (minutes < 24 * 60) {
            return Math.round(minutes / 60.0) + "時間";
        }
        // 日付の差で数え、一緒に表示する開始日時(日本時間)と食い違わないようにする(例: 土曜23時から見た月曜1時は2日後)
        long days = ChronoUnit.DAYS.between(now.atZone(ZONE).toLocalDate(), startTime.atZone(ZONE).toLocalDate());
        return Math.max(days, 1) + "日";
    }

    private String formatDateTime(Instant instant) {
        ZonedDateTime dateTime = instant.atZone(ZONE);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) HH:mm", java.util.Locale.JAPANESE);
        return dateTime.format(formatter);
    }

    public MaintenanceEvent getCurrentMaintenance() {
        return currentMaintenance;
    }

    private MaintenanceEvent getNextMaintenanceEvent() {
        Instant now = Instant.now();
        return scheduledMaintenances.stream()
                .filter(event -> event.getStartTime().isAfter(now))
                .findFirst()
                .orElse(null);
    }

    public List<MaintenanceEvent> getAllScheduledMaintenances() {
        // 全てのスケジュールされたメンテナンスを返す
        // 注: 終了時刻が過ぎていても、endコマンドで明示的に終了するまで保持される
        return scheduledMaintenances.stream()
                .sorted(Comparator.comparing(MaintenanceEvent::getStartTime))
                .toList();
    }

    public String getNextScheduleInfo() {
        List<MaintenanceEvent> upcomingEvents = getAllScheduledMaintenances();

        if (upcomingEvents.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("§e§l[メンテナンススケジュール]\n");

        long now = System.currentTimeMillis();
        int count = 0;
        for (MaintenanceEvent event : upcomingEvents) {
            if (count >= 5)
                break; // 最大5件まで表示

            long startTime = event.getStartTime().toEpochMilli();
            long endTime = event.getEndTime().toEpochMilli();

            String status;
            String timeInfo;

            if (now < startTime) {
                // メンテナンス開始前
                timeInfo = formatTimeUntil(event.getStartTime(), Instant.ofEpochMilli(now)) + "後";
                status = "開始予定";
            } else if (now >= startTime && now < endTime) {
                // スケジュール上の実施期間中
                long timeRemaining = endTime - now;
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeRemaining);
                long hours = TimeUnit.MILLISECONDS.toHours(timeRemaining);

                if (hours > 0) {
                    timeInfo = "予定終了まであと" + hours + "時間" + (minutes % 60) + "分";
                } else {
                    timeInfo = "予定終了まであと" + minutes + "分";
                }
                status = "実施中";
            } else {
                // スケジュール上の終了時刻を過ぎている
                long timeSinceEnd = now - endTime;
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeSinceEnd);
                long hours = TimeUnit.MILLISECONDS.toHours(timeSinceEnd);

                if (hours > 0) {
                    timeInfo = "予定終了から" + hours + "時間" + (minutes % 60) + "分経過";
                } else {
                    timeInfo = "予定終了から" + minutes + "分経過";
                }
                status = "実施中(延長)";
            }

            if (count > 0) {
                sb.append("\n§7━━━━━━━━━━━━━━━━━━\n");
            }

            sb.append("§f").append(count + 1).append(". ").append(event.getTitle()).append("\n");
            sb.append("§7説明: §f").append(event.getDescription().isEmpty() ? "なし" : event.getDescription()).append("\n");
            sb.append("§7開始: §a").append(formatDateTime(event.getStartTime())).append("\n");
            sb.append("§7終了予定: §a").append(formatDateTime(event.getEndTime())).append("\n");
            sb.append("§7状態: §e").append(status).append(" §7(").append(timeInfo).append(")");

            count++;
        }

        return sb.toString();
    }

    public Set<String> getProcessedEventIds() {
        return new HashSet<>(processedEventIds);
    }

    private void restoreMaintenanceState() {
        MaintenanceStateManager.MaintenanceState state = stateManager.loadState();
        if (state == null) {
            return;
        }

        List<MaintenanceEvent> events = state.toEvents();
        Map<String, Boolean> notificationMap = state.getDiscordNotificationSentMap();

        // 終了済みイベントの記録を復元（予定イベントが空でも復元する）
        completedEvents.putAll(state.getCompletedEvents());

        // 手順1: まず全てのイベントをリストに復元する
        // (これを先にやらないと、保存時にデータが消えるバグが発生します)
        for (MaintenanceEvent event : events) {
            String eventId = event.getId();

            scheduledMaintenances.add(event);
            processedEventIds.add(eventId);

            // Discord通知状態を復元
            Boolean notificationSent = notificationMap.get(eventId);
            if (notificationSent != null && notificationSent) {
                discordNotificationSentMap.put(eventId, true);
            }
        }

        // 開始時刻でソート（手順2で最も早く開始したイベントを選ぶため先に行う）
        scheduledMaintenances.sort(Comparator.comparing(MaintenanceEvent::getStartTime));

        // 手順2: メンテナンスモードの復元判定
        Instant now = Instant.now();

        // JSONファイルで maintenanceMode: true だった場合は、メンテナンス中の状態で再開する
        if (state.isMaintenanceMode()) {
            // 開始時刻を過ぎたイベントのうち最も早いものを実施中のメンテナンスとする
            currentMaintenance = scheduledMaintenances.stream()
                    .filter(e -> !e.getStartTime().isAfter(now))
                    .findFirst()
                    .orElse(null);
            // 該当イベントがない場合(実施中にカレンダーから削除された等)もメンテナンスモードは維持する
            startMaintenance(false); // 通知なしで再開
        }

        // 停止中に予定終了時刻を過ぎたイベントを削除
        removeExpiredEvents();

        // 手順3: 通知・開始のスケジュール登録
        // (停止中に開始時刻を過ぎ、まだ予定終了前のイベントは即座に開始される)
        for (MaintenanceEvent event : new ArrayList<>(scheduledMaintenances)) {
            scheduleNotifications(event);
            scheduleMaintenanceStart(event);
        }

        // 復元時の削除・開始を反映して保存
        saveMaintenanceState();
    }

    private void saveMaintenanceState() {
        MaintenanceStateManager.MaintenanceState state = new MaintenanceStateManager.MaintenanceState(
                maintenanceMode,
                new ArrayList<>(scheduledMaintenances),
                new HashMap<>(discordNotificationSentMap),
                new HashMap<>(completedEvents));
        stateManager.saveState(state);
    }

    private void kickUnauthorizedPlayers() {
        String kickMessage = configManager.getKickMessage();
        Component kickComponent = LegacyComponentSerializer.legacySection().deserialize(kickMessage);

        for (Player player : server.getAllPlayers()) {
            if (!isPlayerAllowed(player.getUsername())) {
                player.disconnect(kickComponent);
            }
        }
    }
}
