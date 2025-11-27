package com.ineserver.maintenance;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.slf4j.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class MaintenanceManager {

    private final ProxyServer server;
    private final ConfigManager configManager;
    private final DiscordNotifier discordNotifier;
    private final Logger logger;
    private final MaintenanceStateManager stateManager;
    private LuckPerms luckPerms;

    private final List<MaintenanceEvent> scheduledMaintenances = Collections.synchronizedList(new ArrayList<>());
    private MaintenanceEvent currentMaintenance;
    private boolean maintenanceMode = false;
    private final Map<String, Boolean> discordNotificationSentMap = new ConcurrentHashMap<>();
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<Integer, ScheduledFuture<?>>> scheduledNotifications = new ConcurrentHashMap<>();
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
            Map<String, MaintenanceEvent> fetchedEventsMap = new HashMap<>();

            for (MaintenanceEvent event : fetchedEvents) {
                fetchedEventIds.add(event.getId());
                fetchedEventsMap.put(event.getId(), event);
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
                    // 過去のイベントは無視
                    if (existingEvent.getEndTime().isBefore(Instant.now())) {
                        continue;
                    }

                    logger.info("Maintenance cancelled (removed from calendar): " + existingEvent.getTitle());

                    // Discord通知 - キャンセル
                    discordNotifier.sendMaintenanceCancelled(existingEvent);

                    // 通知のキャンセル
                    cancelEventNotifications(existingId);

                    // リストから削除
                    iterator.remove();
                    processedEventIds.remove(existingId);
                    discordNotificationSentMap.remove(existingId);

                    // もし現在進行中のメンテナンスだった場合、メンテナンスモードを終了するか検討
                    // (安全のため、自動では終了せず、管理者に任せるか、あるいは終了させるか。ここでは終了させない)
                }
            }

            // 2. 新規・更新イベントの処理
            boolean stateChanged = false;

            for (MaintenanceEvent fetchedEvent : fetchedEvents) {
                String eventId = fetchedEvent.getId();

                // 既存イベントの検索
                MaintenanceEvent existingEvent = scheduledMaintenances.stream()
                        .filter(e -> e.getId().equals(eventId))
                        .findFirst()
                        .orElse(null);

                if (existingEvent == null) {
                    // 新規イベント
                    scheduleMaintenanceEvent(fetchedEvent);
                    stateChanged = true;
                } else {
                    // 更新チェック
                    if (!existingEvent.equals(fetchedEvent)) {
                        logger.info(
                                "Maintenance updated: " + existingEvent.getTitle() + " -> " + fetchedEvent.getTitle());

                        // Discord通知 - 更新
                        discordNotifier.sendMaintenanceUpdated(existingEvent, fetchedEvent);

                        // 古いイベントを削除して新しいイベントを追加
                        scheduledMaintenances.remove(existingEvent);
                        scheduledMaintenances.add(fetchedEvent);

                        // 通知スケジュールの再設定
                        cancelEventNotifications(eventId);
                        scheduleNotifications(fetchedEvent);
                        scheduleMaintenanceStart(fetchedEvent);

                        stateChanged = true;
                    }
                }
            }

            if (stateChanged) {
                // 開始時刻でソート
                scheduledMaintenances.sort(Comparator.comparing(MaintenanceEvent::getStartTime));
                // 状態保存
                saveMaintenanceState();
            }
        }
    }

    public boolean scheduleMaintenanceEvent(MaintenanceEvent event) {
        String eventId = event.getId();

        Instant now = Instant.now();
        Instant startTime = event.getStartTime();
        Instant endTime = event.getEndTime();

        // 開始時刻が過去の場合は何もしない(記録もしない)
        if (startTime.isBefore(now)) {
            // ただし、現在進行中の場合は更新が必要かもしれない
            // 終了時刻が未来であれば処理続行
            if (endTime.isBefore(now)) {
                return false;
            }
        }

        // 既に処理済みのイベントかチェック
        if (processedEventIds.contains(eventId)) {
            // 既存のイベントと同じかチェック
            synchronized (scheduledMaintenances) {
                for (MaintenanceEvent e : scheduledMaintenances) {
                    if (e.getId().equals(eventId)) {
                        if (e.equals(event)) {
                            return false; // 変更なし
                        }
                        break;
                    }
                }
            }
            // 既存のイベントを更新
            scheduledMaintenances.removeIf(e -> e.getId().equals(eventId));
        }

        processedEventIds.add(eventId);
        scheduledMaintenances.add(event);

        // 開始時刻でソート
        scheduledMaintenances.sort(Comparator.comparing(MaintenanceEvent::getStartTime));

        // Discord通知 - メンテナンス決定(未通知の場合のみ)
        Boolean notificationSent = discordNotificationSentMap.get(eventId);
        if (notificationSent == null || !notificationSent) {
            discordNotifier.sendMaintenanceScheduled(event);
            discordNotificationSentMap.put(eventId, true);
        }

        // メンテナンス状態を保存
        saveMaintenanceState();

        // 通知スケジュールの設定
        scheduleNotifications(event);

        // メンテナンス開始のスケジュール
        scheduleMaintenanceStart(event);

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
        long now = System.currentTimeMillis();
        long startTime = event.getStartTime().toEpochMilli();
        long delay = startTime - now;

        if (delay > 0) {
            String eventId = event.getId();
            scheduler.schedule(() -> {
                // 最も早いイベントを現在のメンテナンスとして設定
                if (!maintenanceMode) {
                    currentMaintenance = event;
                    startMaintenance();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else if (delay > -60000) { // 開始時刻から1分以内の場合は即座に開始
            if (!maintenanceMode) {
                currentMaintenance = event;
                startMaintenance();
            }
        }
    }

    private void sendMaintenanceNotification(MaintenanceEvent event, int minutes) {
        String timeStr = minutes >= 60 ? (minutes / 60) + "時間" : minutes + "分";
        sendMaintenanceNotification(event, timeStr);
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

    public void endMaintenance() {
        if (!maintenanceMode) {
            return;
        }

        maintenanceMode = false;

        logger.info("Maintenance mode deactivated");

        // Discord通知 - メンテナンス終了
        if (currentMaintenance != null) {
            discordNotifier.sendMaintenanceEnded(currentMaintenance);

            // 終了したイベントのみを削除
            String eventId = currentMaintenance.getId();
            scheduledMaintenances.removeIf(e -> e.getId().equals(eventId));
            processedEventIds.remove(eventId);
            discordNotificationSentMap.remove(eventId);

            // 終了したイベントの通知のみをキャンセル
            cancelEventNotifications(eventId);

            currentMaintenance = null;
        }

        // 次のメンテナンスがあるかチェック
        MaintenanceEvent nextEvent = getNextMaintenanceEvent();
        if (nextEvent == null && scheduledMaintenances.isEmpty()) {
            // 次のメンテナンスがない場合のみ、全スケジュールをキャンセルして状態をクリア
            cancelAllScheduledNotifications();
            stateManager.clearState();
        } else {
            // 次のメンテナンスがある場合は状態を保存
            saveMaintenanceState();
        }
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

        long now = System.currentTimeMillis();
        MaintenanceEvent nextEvent = getNextMaintenanceEvent();

        if (nextEvent != null) {
            long maintenanceTime = nextEvent.getStartTime().toEpochMilli();
            long timeUntil = maintenanceTime - now;

            if (timeUntil > 0) {
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeUntil);
                long hours = TimeUnit.MILLISECONDS.toHours(timeUntil);

                String timeStr;
                if (hours > 0) {
                    timeStr = hours + "時間" + (minutes % 60) + "分";
                } else {
                    timeStr = minutes + "分";
                }

                String message = "§e§l[メンテナンスのお知らせ]\n" +
                        "§f" + timeStr + "後にメンテナンスが予定されています。\n" +
                        "§7タイトル: §f" + nextEvent.getTitle() + "\n" +
                        "§7開始時刻: §f" + formatDateTime(nextEvent.getStartTime());

                Component component = LegacyComponentSerializer.legacySection().deserialize(message);
                player.sendMessage(component);
            }
        }
    }

    private void cancelEventNotifications(String eventId) {
        Map<Integer, ScheduledFuture<?>> eventNotifications = scheduledNotifications.remove(eventId);
        if (eventNotifications != null) {
            for (ScheduledFuture<?> future : eventNotifications.values()) {
                future.cancel(false);
            }
        }
    }

    private void cancelAllScheduledNotifications() {
        for (Map<Integer, ScheduledFuture<?>> eventNotifications : scheduledNotifications.values()) {
            for (ScheduledFuture<?> future : eventNotifications.values()) {
                future.cancel(false);
            }
        }
        scheduledNotifications.clear();
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

    private String formatDateTime(Instant instant) {
        ZonedDateTime dateTime = instant.atZone(ZoneId.of("Asia/Tokyo"));
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
                long timeUntil = startTime - now;
                long minutes = TimeUnit.MILLISECONDS.toMinutes(timeUntil);
                long hours = TimeUnit.MILLISECONDS.toHours(timeUntil);
                long days = TimeUnit.MILLISECONDS.toDays(timeUntil);

                if (days > 0) {
                    timeInfo = days + "日" + (hours % 24) + "時間" + (minutes % 60) + "分後";
                } else if (hours > 0) {
                    timeInfo = hours + "時間" + (minutes % 60) + "分後";
                } else {
                    timeInfo = minutes + "分後";
                }
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

        if (events.isEmpty()) {
            return;
        }

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

        // 手順2: メンテナンスモードの復元判定
        Instant now = Instant.now();

        // ★修正点: JSONファイルで maintenanceMode: true だった場合のみ、再開判定を行う
        if (state.isMaintenanceMode()) {
            for (MaintenanceEvent event : scheduledMaintenances) {
                Instant startTime = event.getStartTime();

                // 開始時刻を過ぎていて、かつ現在進行中のイベントを探す
                if (startTime.isBefore(now)) {
                    currentMaintenance = event;
                    maintenanceMode = true;
                    startMaintenance(false); // 通知なしで再開
                    break; // 1つ見つけたら終了
                }
            }
        }

        // 手順3: 未来のイベントのスケジュール登録
        // (メンテナンス中でない、またはメンテナンス中でも未来の予定はスケジュールする)
        for (MaintenanceEvent event : scheduledMaintenances) {
            if (event.getStartTime().isAfter(now)) {
                scheduleNotifications(event);
                scheduleMaintenanceStart(event);
            }
        }

        // ソート
        scheduledMaintenances.sort(Comparator.comparing(MaintenanceEvent::getStartTime));
    }

    private void saveMaintenanceState() {
        MaintenanceStateManager.MaintenanceState state = new MaintenanceStateManager.MaintenanceState(
                maintenanceMode,
                new ArrayList<>(scheduledMaintenances),
                new HashMap<>(discordNotificationSentMap));
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
