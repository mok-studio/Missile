package com.missile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * RWR 事件侧：退出 / 死亡 / 重生时清理告警状态，避免 BossBar 残留或误报。
 *
 * <p>开机条件（{@code missile.use} + {@code /msl on} + 背包含指南针）与威胁判定都交给
 * 每 tick 的 {@link RwrManager}，这里只处理需要立即失效的事件。
 */
final class RwrListener implements Listener {

    private final RwrManager manager;

    RwrListener(RwrManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.manager.forget(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        this.manager.forget(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        this.manager.forget(event.getPlayer());
    }
}
