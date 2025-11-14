package com.ineserver.maintenance;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class MaintenanceCommand {

    private final MaintenanceManager maintenanceManager;

    public MaintenanceCommand(MaintenanceManager maintenanceManager) {
        this.maintenanceManager = maintenanceManager;
    }

    public BrigadierCommand createCommand() {
        LiteralArgumentBuilder<CommandSource> node = LiteralArgumentBuilder
                .<CommandSource>literal("maintenance")
                .requires(source -> source.hasPermission("maintenance.admin"))
                .executes(context -> {
                    CommandSource source = context.getSource();
                    source.sendMessage(Component.text("使用方法:", NamedTextColor.YELLOW));
                    source.sendMessage(Component.text("/maintenance end - メンテナンスを終了", NamedTextColor.GRAY));
                    source.sendMessage(Component.text("/maintenance status - メンテナンス状態を確認", NamedTextColor.GRAY));
                    source.sendMessage(Component.text("/maintenance schedule - 次回メンテナンス予定を確認", NamedTextColor.GRAY));
                    return Command.SINGLE_SUCCESS;
                })
                .then(LiteralArgumentBuilder.<CommandSource>literal("end")
                        .requires(source -> source.hasPermission("maintenance.admin"))
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            
                            if (!maintenanceManager.isMaintenanceMode()) {
                                source.sendMessage(Component.text("現在メンテナンス中ではありません。", NamedTextColor.RED));
                                return 0;
                            }
                            
                            maintenanceManager.endMaintenance();
                            source.sendMessage(Component.text("メンテナンスを終了しました。", NamedTextColor.GREEN));
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(LiteralArgumentBuilder.<CommandSource>literal("status")
                        .requires(source -> source.hasPermission("maintenance.admin"))
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            
                            if (maintenanceManager.isMaintenanceMode()) {
                                source.sendMessage(Component.text("メンテナンス状態: ", NamedTextColor.YELLOW)
                                        .append(Component.text("実施中", NamedTextColor.RED)));
                            } else {
                                source.sendMessage(Component.text("メンテナンス状態: ", NamedTextColor.YELLOW)
                                        .append(Component.text("通常稼働中", NamedTextColor.GREEN)));
                            }
                            
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(LiteralArgumentBuilder.<CommandSource>literal("schedule")
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            
                            String scheduleInfo = maintenanceManager.getNextScheduleInfo();
                            if (scheduleInfo != null) {
                                // Legacy色コードをAdventure Componentに変換
                                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer serializer = 
                                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
                                source.sendMessage(serializer.deserialize(scheduleInfo));
                            } else {
                                source.sendMessage(Component.text("現在、予定されているメンテナンスはありません。", NamedTextColor.GRAY));
                            }
                            
                            return Command.SINGLE_SUCCESS;
                        })
                );

        return new BrigadierCommand(node);
    }
}
