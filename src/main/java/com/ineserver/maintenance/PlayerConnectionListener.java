package com.ineserver.maintenance;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;

public class PlayerConnectionListener {

    private final MaintenanceManager maintenanceManager;

    public PlayerConnectionListener(MaintenanceManager maintenanceManager) {
        this.maintenanceManager = maintenanceManager;
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onLogin(LoginEvent event) {
        // メンテナンス中でない場合は何もしない
        if (!maintenanceManager.isMaintenanceMode()) {
            return;
        }

        // 許可されたユーザーでない場合はキック
        if (!maintenanceManager.isPlayerAllowed(event.getPlayer())) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    maintenanceManager.getKickMessage()
            ));
        }
    }

    @Subscribe
    public void onServerConnect(ServerPostConnectEvent event) {
        // ログイン時のメンテナンス通知
        maintenanceManager.sendLoginNotification(event.getPlayer());
    }
}
