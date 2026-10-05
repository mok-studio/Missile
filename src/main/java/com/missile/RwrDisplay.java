package com.missile;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/**
 * 告警显示层：每个玩家、每个槽位一条 BossBar。
 *
 * <p>选择 BossBar 而不是动作栏，是为了不与导引头的动作栏状态提示互相覆盖——
 * 同一玩家完全可能既开着导引头（动作栏），又被别人锁定（RWR 告警）。
 *
 * <p>槽位（{@link Slot}）设计成可并列：RWR 与 MAWS 必须**同时**显示（需求 3.7），
 * 所以同一条 BossBar 不能两用，否则后到的告警会把前一条顶掉。
 * 颜色由调用方决定（RWR：敌导弹红 / 敌跟踪黄；MAWS：红色）；文案全部来自语言文件。
 */
final class RwrDisplay {

    /** 告警槽位：一个槽位 = 一条常驻 BossBar。 */
    enum Slot {

        /** RWR：被照射（敌跟踪）/ 有导弹在飞向自己。 */
        RWR,
        /** MAWS：导弹逼近告警（40 格内）。 */
        MAWS
    }

    private final Map<UUID, Map<Slot, BossBar>> bars = new HashMap<>();

    /** 显示或更新某个槽位的告警条。 */
    void show(Player player, Slot slot, String text, BarColor color) {
        Map<Slot, BossBar> playerBars = this.bars.computeIfAbsent(player.getUniqueId(), key -> new EnumMap<>(Slot.class));
        BossBar bar = playerBars.get(slot);
        if (bar == null) {
            bar = Bukkit.createBossBar(text, color, BarStyle.SOLID);
            bar.setProgress(1.0D);
            bar.setVisible(true);
            bar.addPlayer(player);
            playerBars.put(slot, bar);
            return;
        }
        bar.setTitle(text);
        bar.setColor(color);
        bar.addPlayer(player);
    }

    /** 移除某玩家某个槽位的告警条（该槽位的威胁消失）。 */
    void clear(Player player, Slot slot) {
        Map<Slot, BossBar> playerBars = this.bars.get(player.getUniqueId());
        if (playerBars == null) {
            return;
        }
        BossBar bar = playerBars.remove(slot);
        if (bar != null) {
            bar.setVisible(false);
            bar.removePlayer(player);
        }
        if (playerBars.isEmpty()) {
            this.bars.remove(player.getUniqueId());
        }
    }

    /** 移除某玩家**所有**槽位的告警条（退出 / 死亡 / 关机）。 */
    void clear(Player player) {
        Map<Slot, BossBar> playerBars = this.bars.remove(player.getUniqueId());
        if (playerBars == null) {
            return;
        }
        for (BossBar bar : playerBars.values()) {
            bar.setVisible(false);
            bar.removePlayer(player);
        }
    }

    /** 关服清理。 */
    void clearAll() {
        for (Map<Slot, BossBar> playerBars : this.bars.values()) {
            for (BossBar bar : playerBars.values()) {
                bar.setVisible(false);
                bar.removeAll();
            }
        }
        this.bars.clear();
    }
}
