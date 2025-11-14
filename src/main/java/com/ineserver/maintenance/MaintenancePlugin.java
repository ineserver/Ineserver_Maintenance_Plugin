package com.ineserver.maintenance;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.luckperms.api.LuckPerms;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "ineserver_maintenance_plugin",
        name = "Ineserver Maintenance Plugin",
        version = "1.0.0",
        description = "Automatic maintenance management with Google Calendar integration",
        authors = {"Ineserver"},
        dependencies = {@Dependency(id = "luckperms", optional = true)}
)
public class MaintenancePlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    
    private ConfigManager configManager;
    private MaintenanceManager maintenanceManager;
    private GoogleCalendarService googleCalendarService;
    private DiscordNotifier discordNotifier;
    private MaintenanceStateManager stateManager;

    @Inject
    public MaintenancePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Initializing Maintenance Plugin...");

        try {
            // 設定ファイルの読み込み
            configManager = new ConfigManager(dataDirectory, logger);
            configManager.loadConfig();

            // Discord通知機能の初期化
            discordNotifier = new DiscordNotifier(configManager, logger);
            
            // メンテナンス状態管理の初期化
            stateManager = new MaintenanceStateManager(dataDirectory, logger);

            // メンテナンス管理機能の初期化
            maintenanceManager = new MaintenanceManager(server, configManager, discordNotifier, logger, stateManager);

            // LuckPerms連携の初期化（必須）
            try {
                // VelocityのサービスマネージャーからLuckPerms APIを取得
                server.getPluginManager().getPlugin("luckperms").ifPresentOrElse(
                    pluginContainer -> {
                        try {
                            // LuckPerms APIプロバイダーをリフレクションで取得
                            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
                            java.lang.reflect.Method getMethod = providerClass.getMethod("get");
                            LuckPerms api = (LuckPerms) getMethod.invoke(null);
                            
                            if (api != null) {
                                maintenanceManager.setLuckPerms(api);
                                logger.info("LuckPerms integration enabled - Admin group members will be allowed during maintenance");
                            } else {
                                logger.error("LuckPerms API returned null");
                                logger.error("WARNING: LuckPerms is required for this plugin to function properly!");
                            }
                        } catch (ClassNotFoundException e) {
                            logger.error("LuckPerms API classes not found - make sure LuckPerms is installed", e);
                            logger.error("WARNING: LuckPerms is required for this plugin to function properly!");
                        } catch (IllegalStateException e) {
                            logger.error("LuckPerms API is not loaded yet", e);
                            logger.error("WARNING: LuckPerms is required for this plugin to function properly!");
                        } catch (Exception e) {
                            logger.error("Error getting LuckPerms API", e);
                            logger.error("WARNING: LuckPerms is required for this plugin to function properly!");
                        }
                    },
                    () -> {
                        logger.error("========================================");
                        logger.error("WARNING: LuckPerms plugin is NOT installed!");
                        logger.error("This plugin requires LuckPerms to function properly.");
                        logger.error("Please install LuckPerms before using this plugin.");
                        logger.error("Available plugins: " + server.getPluginManager().getPlugins().stream()
                            .map(p -> p.getDescription().getId())
                            .collect(java.util.stream.Collectors.joining(", ")));
                        logger.error("========================================");
                    }
                );
            } catch (Exception e) {
                logger.error("Failed to initialize LuckPerms integration", e);
                logger.error("WARNING: LuckPerms is required for this plugin to function properly!");
            }

            // Google Calendar連携の初期化
            googleCalendarService = new GoogleCalendarService(configManager, maintenanceManager, logger);
            googleCalendarService.initialize();

            // コマンドの登録
            MaintenanceCommand maintenanceCommand = new MaintenanceCommand(maintenanceManager);
            server.getCommandManager().register(maintenanceCommand.createCommand());

            // イベントリスナーの登録
            server.getEventManager().register(this, new PlayerConnectionListener(maintenanceManager));
            server.getEventManager().register(this, new ServerPingListener(maintenanceManager));

            logger.info("Maintenance Plugin has been enabled successfully!");
        } catch (Exception e) {
            logger.error("Failed to initialize Maintenance Plugin", e);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down Maintenance Plugin...");

        if (googleCalendarService != null) {
            googleCalendarService.shutdown();
        }

        if (maintenanceManager != null) {
            maintenanceManager.shutdown();
        }

        logger.info("Maintenance Plugin has been disabled.");
    }
}
