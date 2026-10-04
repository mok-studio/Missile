package com.missile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/**
 * RWR 显示层：每个玩家一条 BossBar。
 *
 * <p>选择 BossBar 而不是动作栏，是为了不与导引头的动作栏状态提示互相覆盖——
 * 同一玩家完全可能既开着导引头（动作栏），又被别人锁定（RWR 告警）。
 * 颜色：敌导弹 = 红色，敌跟踪 = 黄色；文案全部来自语言文件的 {@code rwr.*}。
 */
final class RwrDisplay {

    private final Map<UUID, BossBar> bars = new HashMap<>();

    /** 显示或更新告警条。 */
    void show(Player player, String text, boolean missile) {
        BarColor color = missile ? BarColor.RED : BarColor.YELLOW;
        BossBar bar = this.bars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar(text, color, BarStyle.SOLID);
            bar.setProgress(1.0D);
            bar.setVisible(true);
            bar.addPlayer(player);
            this.bars.put(player.getUniqueId(), bar);
            return;
        }
        bar.setTitle(text);
        bar.setColor(color);
        bar.addPlayer(player);
    }

    /** 移除某玩家的告警条（威胁消失 / 退出 / 死亡）。 */
    void clear(Player player) {
        BossBar bar = this.bars.remove(player.getUniqueId());
        if (bar == null) {
            return;
        }
        bar.setVisible(false);
        bar.removePlayer(player);
    }

    /** 关服清理。 */
    void clearAll() {
        for (BossBar bar : this.bars.values()) {
            bar.setVisible(false);
            bar.removeAll();
        }
        this.bars.clear();
    }
}
