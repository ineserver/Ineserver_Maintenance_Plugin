package com.ineserver.maintenance;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ServerPingListener {

    private final MaintenanceManager maintenanceManager;

    public ServerPingListener(MaintenanceManager maintenanceManager) {
        this.maintenanceManager = maintenanceManager;
    }

    @Subscribe(priority = -100)
    public void onServerPing(ProxyPingEvent event) {
        if (!maintenanceManager.isMaintenanceMode()) {
            return;
        }

        ServerPing originalPing = event.getPing();
        ServerPing.Builder builder = originalPing.asBuilder();

        // MOTDを変更
        Component motd = Component.text("§c§l現在メンテナンス中です", NamedTextColor.RED)
                .append(Component.text("\n§7しばらくお待ちください", NamedTextColor.GRAY));
        builder.description(motd);

        // バージョン表記を変更
        ServerPing.Version version = new ServerPing.Version(-1, "メンテナンス");
        builder.version(version);

        // プレイヤー数を0/0に設定
        ServerPing.SamplePlayer[] samplePlayers = new ServerPing.SamplePlayer[0];
        builder.samplePlayers(samplePlayers);
        builder.onlinePlayers(0);
        builder.maximumPlayers(0);

        event.setPing(builder.build());
    }
}
