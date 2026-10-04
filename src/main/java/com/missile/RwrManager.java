package com.missile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * RWR（雷达告警接收机）管理器。
 *
 * <p>激活条件：玩家背包内有指南针（{@link Material#COMPASS}）时 RWR 开机。
 *
 * <p>告警来源每 tick <b>轮询</b>现有状态，不往导弹 / 导引头里塞回调：
 * <ul>
 *   <li><b>敌跟踪</b>：某玩家已开启导引头、型号为半主动 / 主动，且当前锁定目标是自己；</li>
 *   <li><b>敌导弹</b>：某发在飞的半主动 / 主动导弹，其锁定目标是自己。</li>
 * </ul>
 *
 * <p>方位以本机朝向为 12 点钟方向，按 45° 扇区映射到 8 个方位
 * （12 / 1:30 / 3 / 4:30 / 6 / 7:30 / 9 / 10:30），文案取自语言文件 {@code rwr.dir-*}。
 */
final class RwrManager {

    /** 指南针检查周期（tick），降低背包扫描开销。 */
    private static final int COMPASS_CHECK_INTERVAL = 5;
    /** 敌跟踪告警音周期（tick）：高音 / 低音交替。 */
    private static final int TRACK_TONE_INTERVAL = 20;
    /** 敌导弹告警音周期（tick）：急促。 */
    private static final int MISSILE_TONE_INTERVAL = 5;
    private static final float TRACK_VOLUME = 0.7F;
    private static final float MISSILE_VOLUME = 1.0F;
    private static final float TRACK_HIGH_PITCH = 1.8F;
    private static final float TRACK_LOW_PITCH = 0.7F;
    private static final float MISSILE_PITCH = 1.6F;
    /** 告警距离上限（格）。 */
    private static final double ALERT_RANGE = 256.0D;
    /** 每 45° 一个扇区。 */
    private static final double SECTOR_DEGREES = 45.0D;

    private final MissilePlugin plugin;
    private final RwrDisplay display = new RwrDisplay();
    private final Map<UUID, State> states = new HashMap<>();
    private int compassTicks;

    RwrManager(MissilePlugin plugin) {
        this.plugin = plugin;
    }

    /** 每 tick 由主类调用。 */
    void tick() {
        boolean refreshCompass = ++this.compassTicks >= COMPASS_CHECK_INTERVAL;
        if (refreshCompass) {
            this.compassTicks = 0;
        }
        List<Emitter> trackers = this.collectTrackers();
        List<Emitter> incoming = this.collectMissiles();

        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            State state = this.states.computeIfAbsent(player.getUniqueId(), key -> new State());
            if (refreshCompass) {
                state.active = player.getInventory().contains(Material.COMPASS);
            }
            if (!state.active) {
                state.reset();
                this.display.clear(player);
                continue;
            }
            Threat missile = this.scan(player, incoming);
            Threat track = this.scan(player, trackers);
            if (missile.present()) {
                this.alarm(player, state, missile, true);
            } else if (track.present()) {
                this.alarm(player, state, track, false);
            } else {
                state.reset();
                this.display.clear(player);
            }
        }
    }

    /** 退出 / 死亡 / 重生时清理该玩家状态。 */
    void forget(Player player) {
        this.states.remove(player.getUniqueId());
        this.display.clear(player);
    }

    /** 关服清理。 */
    void shutdown() {
        this.display.clearAll();
        this.states.clear();
    }

    /** 收集正在照射 / 锁定本机的射手。 */
    private List<Emitter> collectTrackers() {
        List<Emitter> emitters = new ArrayList<>();
        for (Player shooter : this.plugin.getServer().getOnlinePlayers()) {
            SeekerListener.SeekerState state = this.plugin.seekers().stateIfPresent(shooter);
            if (state == null || !state.armed() || state.targetId() == null) {
                continue;
            }
            MissileType type = state.type();
            if (type != MissileType.SEMI_ACTIVE && !type.radarHoming()) {
                continue;   // 只有半主动 / 雷达制导会辐射，红外与驾束不触发 RWR
            }
            emitters.add(new Emitter(state.targetId(), shooter.getLocation()));
        }
        return emitters;
    }

    /** 收集在飞的半主动 / 主动导弹。 */
    private List<Emitter> collectMissiles() {
        List<Emitter> emitters = new ArrayList<>();
        for (Missile missile : this.plugin.missiles().all()) {
            MissileType type = missile.type();
            if (type != MissileType.SEMI_ACTIVE && !type.radarHoming()) {
                continue;
            }
            if (missile.targetId() == null || missile.projectile() == null
                    || !missile.projectile().isValid()) {
                continue;
            }
            emitters.add(new Emitter(missile.targetId(), missile.projectile().getLocation()));
        }
        return emitters;
    }

    /** 统计指向 viewer 的最近威胁扇区与威胁数量。 */
    private Threat scan(Player viewer, List<Emitter> emitters) {
        Threat threat = new Threat();
        for (Emitter emitter : emitters) {
            if (!emitter.targetId.equals(viewer.getUniqueId())) {
                continue;
            }
            Location location = emitter.location;
            if (location.getWorld() == null || !location.getWorld().equals(viewer.getWorld())) {
                continue;
            }
            double distance = location.distance(viewer.getLocation());
            if (distance > ALERT_RANGE) {
                continue;
            }
            threat.add(sectorOf(viewer, location), distance);
        }
        return threat;
    }

    /** 触发（或维持）一级告警：更新 BossBar 文案并按周期播放告警音。 */
    private void alarm(Player player, State state, Threat threat, boolean missile) {
        state.toneTicks++;
        int interval = missile ? MISSILE_TONE_INTERVAL : TRACK_TONE_INTERVAL;
        if (state.toneTicks >= interval) {
            state.toneTicks = 0;
            state.highTone = !state.highTone;
            this.playTone(player, missile, state.highTone);
        }
        String key;
        if (missile) {
            key = threat.count > 1 ? "rwr.bossbar-missile-multi" : "rwr.bossbar-missile";
        } else {
            key = threat.count > 1 ? "rwr.bossbar-track-multi" : "rwr.bossbar-track";
        }
        this.display.show(player, Lang.get(key,
                "dir", Lang.get("rwr.dir-" + threat.sector), "count", threat.count), missile);
    }

    /** 敌跟踪：高音 / 低音交替；敌导弹：急促高音。 */
    private void playTone(Player player, boolean missile, boolean high) {
        Location location = player.getLocation();
        if (missile) {
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_BIT, MISSILE_VOLUME, MISSILE_PITCH);
            return;
        }
        player.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, TRACK_VOLUME,
                high ? TRACK_HIGH_PITCH : TRACK_LOW_PITCH);
    }

    /**
     * 以 viewer 朝向为 12 点钟，把威胁位置映射到 8 个扇区。
     *
     * @return 0=12点钟，1=1:30，2=3点钟，3=4:30，4=6点钟，5=7:30，6=9点钟，7=10:30
     */
    static int sectorOf(Player viewer, Location threat) {
        Location origin = viewer.getLocation();
        Vector to = threat.toVector().subtract(origin.toVector());
        double threatYaw = Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        double relative = (threatYaw - origin.getYaw()) % 360.0D;
        if (relative < 0.0D) {
            relative += 360.0D;
        }
        return (int) Math.floorMod(Math.round(relative / SECTOR_DEGREES), 8L);
    }

    /** 告警来源：射手或导弹的位置，以及它锁定的目标。 */
    private static final class Emitter {

        private final UUID targetId;
        private final Location location;

        private Emitter(UUID targetId, Location location) {
            this.targetId = targetId;
            this.location = location;
        }
    }

    /** 一次扫描结果：最近威胁所在扇区与威胁总数。 */
    private static final class Threat {

        private int sector;
        private int count;
        private double nearest = Double.MAX_VALUE;

        private void add(int sector, double distance) {
            this.count++;
            if (distance < this.nearest) {
                this.nearest = distance;
                this.sector = sector;
            }
        }

        private boolean present() {
            return this.count > 0;
        }
    }

    /** 单机 RWR 状态。 */
    private static final class State {

        private boolean active;
        private int toneTicks;
        private boolean highTone;

        private void reset() {
            this.toneTicks = 0;
            this.highTone = false;
        }
    }
}
