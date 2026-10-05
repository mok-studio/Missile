package com.missile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;

/**
 * RWR（雷达告警接收机）管理器。
 *
 * <p><b>开机条件（三条全部满足，用户 2026-10-05 拍板）</b>：
 * <ol>
 *   <li>拥有权限节点 {@code missile.use}；</li>
 *   <li>自己用 {@code /msl on} 开了导弹模式（{@link MissilePlugin#isPlayerEnabled}）；</li>
 *   <li>背包里含有指南针 {@link Material#COMPASS}（{@code minecraft:compass}）。</li>
 * </ol>
 * 语义后果：**受害者自己 {@code /msl off} 之后就收不到任何告警**（用户已确认接受）。
 * 个人开关每 tick 判定（{@code /msl off} 立即静默），权限与背包每
 * {@value #COMPASS_CHECK_INTERVAL} tick 复查一次（背包扫描不便宜）。
 *
 * <p>告警来源每 tick <b>轮询</b>现有状态，不往导弹 / 导引头里塞回调：
 * <ul>
 *   <li><b>敌跟踪</b>：某玩家已开启导引头、型号为半主动 / 主动，且当前锁定目标是自己；</li>
 *   <li><b>敌导弹</b>：某发在飞的半主动 / 主动导弹，其锁定目标是自己。</li>
 * </ul>
 *
 * <p>方位以本机朝向为 12 点钟方向，按 45° 扇区映射到 8 个方位
 * （12 / 1:30 / 3 / 4:30 / 6 / 7:30 / 9 / 10:30），文案取自语言文件 {@code rwr.dir-*}。
 *
 * <p>告警音间隔 / 音量 / 音调与告警距离全部来自 {@code config.yml} 的 {@code rwr:} 板块
 * （需求：敌跟踪 0.2s = 4 tick，敌导弹 0.1s = 2 tick，见 {@link Settings#rwrTrackInterval()}）。
 */
final class RwrManager {

    /** 权限节点之一：与 {@code /msl <型号>} 同一个（MissileCommand 里的 PERMISSION_USE）。 */
    private static final String PERMISSION_USE = "missile.use";
    /** 权限节点之二：管理权限（用户补充：默认拥有所有权限，无需再授予 missile.use）。 */
    private static final String PERMISSION_ADMIN = "missile.admin";
    /** 权限与指南针检查周期（tick），降低背包扫描开销。 */
    private static final int COMPASS_CHECK_INTERVAL = 5;
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
        if (!Settings.rwrEnabled() && !Settings.mawsEnabled()) {
            // 两个总开关都关：清干净残留的告警条（clearAll 在空表上是零开销）
            this.states.clear();
            this.display.clearAll();
            return;
        }
        boolean refreshCompass = ++this.compassTicks >= COMPASS_CHECK_INTERVAL;
        if (refreshCompass) {
            this.compassTicks = 0;
        }
        List<Emitter> trackers = Settings.rwrEnabled() ? this.collectTrackers() : List.of();
        List<Emitter> incoming = Settings.rwrEnabled() ? this.collectMissiles() : List.of();
        // MAWS 看的是**所有**在飞的导弹（不限型号、不要求以本机为目标）
        List<Emitter> allMissiles = Settings.mawsEnabled() ? this.collectAllMissiles() : List.of();

        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            State state = this.states.computeIfAbsent(player.getUniqueId(), key -> new State());
            if (refreshCompass) {
                state.onboard = onboard(player);
            }
            // 个人开关每 tick 判定：/msl off 必须立刻静默（决策 #3）
            if (!state.onboard || !switchedOn(player)) {
                state.reset();
                this.display.clear(player);
                continue;
            }

            // ---- RWR 槽位：敌导弹优先于敌跟踪 ----
            if (Settings.rwrEnabled()) {
                Threat missile = this.scan(player, incoming, Settings.rwrAlertRange(), true);
                Threat track = this.scan(player, trackers, Settings.rwrAlertRange(), true);
                if (missile.present()) {
                    this.alarm(player, state, missile, true);
                } else if (track.present()) {
                    this.alarm(player, state, track, false);
                } else {
                    state.toneTicks = 0;
                    state.highTone = false;
                    this.display.clear(player, RwrDisplay.Slot.RWR);
                }
            } else {
                this.display.clear(player, RwrDisplay.Slot.RWR);
            }

            // ---- MAWS 槽位：与 RWR 并存，互不顶替 ----
            if (Settings.mawsEnabled()) {
                Threat approach = this.scanMaws(player, allMissiles);
                if (approach.present()) {
                    state.mawsSector = approach.sector;
                    this.maws(player, state, approach);
                } else {
                    state.mawsSector = -1;
                    state.mawsToneTicks = 0;
                    this.display.clear(player, RwrDisplay.Slot.MAWS);
                }
            } else {
                state.mawsSector = -1;
                this.display.clear(player, RwrDisplay.Slot.MAWS);
            }
        }
    }

    /**
     * 开机条件里"较贵"的两项：权限 + 背包含指南针。
     * 每 {@value #COMPASS_CHECK_INTERVAL} tick 复查一次并缓存（背包扫描不便宜）。
     *
     * <p>权限判定：{@code missile.use} **或** {@code missile.admin} 之一即可
     * （用户 2026-10-05 补充：missile.admin 默认拥有所有权限，不需要再单独授予 missile.use）。
     */
    static boolean onboard(Player player) {
        return (player.hasPermission(PERMISSION_USE) || player.hasPermission(PERMISSION_ADMIN))
                && player.getInventory().contains(Material.COMPASS);
    }

    /**
     * 第三项开机条件：必须自己用 {@code /msl on} 开了导弹模式。
     *
     * <p>它只是一次静态表查询，所以**每 tick** 都判 —— 受害者 {@code /msl off} 后应当立即静默
     * （决策 #3：自己关掉就收不到任何告警）。
     */
    static boolean switchedOn(Player player) {
        return MissilePlugin.isPlayerEnabled(player.getUniqueId());
    }

    /** 退出 / 死亡 / 重生时清理该玩家状态。 */
    void forget(Player player) {
        this.states.remove(player.getUniqueId());
        this.display.clear(player);
    }

    /**
     * 供占位符 {@code %msl_maws%} 用：该玩家当前 MAWS 最近威胁的方位箭头；无威胁时返回空串。
     *
     * <p>直接读 {@link #tick()} 每 tick 算好的缓存，**不重复扫描导弹**——
     * TAB 之类的插件可能每 tick 为每个玩家问一次，重复扫描会很贵。
     */
    String mawsArrow(Player player) {
        if (!Settings.mawsEnabled()) {
            return "";
        }
        State state = this.states.get(player.getUniqueId());
        if (state == null || state.mawsSector < 0) {
            return "";
        }
        return Lang.get("rwr.dir-" + state.mawsSector);
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
            emitters.add(new Emitter(state.targetId(), shooter));
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
            emitters.add(new Emitter(missile.targetId(), missile.projectile()));
        }
        return emitters;
    }

    /**
     * MAWS 用：**所有**在飞的导弹，不限型号、也不要求以本机为目标。
     *
     * <p>与 {@link #collectMissiles()}（RWR 只认半主动 / 雷达制导且锁定自己的弹）的区别就在这：
     * 逼近告警关心的是"有东西正朝我这边飞"，红外弹与驾束弹同样危险。
     * **是否真的告警**由 {@link #scanMaws} 再按"距离 + 接近率 + 是否自己射的"筛一遍。
     */
    private List<Emitter> collectAllMissiles() {
        List<Emitter> emitters = new ArrayList<>();
        for (Missile missile : this.plugin.missiles().all()) {
            if (missile.projectile() == null || !missile.projectile().isValid()) {
                continue;
            }
            emitters.add(new Emitter(null, missile.projectile()));
        }
        return emitters;
    }

    /**
     * MAWS 的判定：距离内 + **接近率大于阈值** + 不是自己射的。
     *
     * <p>候选来自两处：① 插件自己的导弹（{@link #collectAllMissiles()}）；② 观察者附近的
     * **箭 / 光灵箭 / 三叉戟**（{@link AbstractArrow}，每 tick 按观察者位置扫一次）。
     *
     * <p>用"接近率"而不是"在范围内"来触发，是为了不把**刚发射出去、正在远离自己**的弹当成威胁
     * （用户 2026-10-05 报的问题：发射者刚打完就自曝）。
     */
    private Threat scanMaws(Player viewer, List<Emitter> missiles) {
        double range = Settings.mawsRange();
        double threshold = Settings.mawsClosingSpeed();
        boolean includeOwn = Settings.mawsIncludeOwn();
        Location viewerLocation = viewer.getLocation();
        Vector viewerVelocity = viewer.getVelocity();
        Threat threat = new Threat();

        for (Emitter emitter : missiles) {
            Entity entity = emitter.entity;
            if (entity == null || !entity.isValid() || entity.isDead()) {
                continue;
            }
            if (!includeOwn && isOwnProjectile(viewer, entity)) {
                continue;                      // 自己打的弹不报自己
            }
            this.addApproaching(threat, viewer, viewerLocation, viewerVelocity,
                    entity.getLocation(), entity.getVelocity(), range, threshold);
        }

        for (Entity entity : viewer.getWorld()
                .getNearbyEntities(viewerLocation, range, range, range)) {
            if (!(entity instanceof AbstractArrow arrow) || !arrow.isValid() || arrow.isDead()) {
                continue;                      // 只认箭类（含光灵箭 / 三叉戟）
            }
            if (!includeOwn && isOwnProjectile(viewer, arrow)) {
                continue;
            }
            this.addApproaching(threat, viewer, viewerLocation, viewerVelocity,
                    arrow.getLocation(), arrow.getVelocity(), range, threshold);
        }
        return threat;
    }

    /** 距离与接近率都达标时计入威胁（扇区按候选当前位置算，与 RWR 同一套方位）。 */
    private void addApproaching(Threat threat, Player viewer, Location viewerLocation,
                                Vector viewerVelocity, Location location, Vector velocity,
                                double range, double threshold) {
        if (location.getWorld() == null || !location.getWorld().equals(viewer.getWorld())) {
            return;
        }
        if (location.distanceSquared(viewerLocation) > range * range) {
            return;
        }
        if (closingSpeed(viewerLocation, viewerVelocity, location, velocity) <= threshold) {
            return;                            // 不是在逼近（或逼近得太慢）：不告警
        }
        threat.add(sectorOf(viewer, location), location.distance(viewerLocation));
    }

    /**
     * 接近率（格/秒）：候选**相对观察者**的速度，在"候选 → 观察者"方向上的投影。
     * 正值 = 正在靠近，负值 = 正在远离。
     *
     * <p>用相对速度而不是绝对速度：你骑着鞘翅迎面冲向一枚静止的箭，接近率同样是正的。
     * 内部只做向量运算、不碰 Bukkit 实体，因此可以脱离服务端验证（见 {@code dist/MawsCheck.java}）。
     *
     * @param viewerVelocity 观察者自身速度（可为 {@code null}，视作静止）
     * @param entityVelocity 候选实体速度（可为 {@code null}，视作静止）
     */
    static double closingSpeed(Location viewer, Vector viewerVelocity, Location entity, Vector entityVelocity) {
        Vector toViewer = viewer.toVector().subtract(entity.toVector());
        double distance = toViewer.length();
        if (distance < 1.0E-4D) {
            return 0.0D;                       // 已经重合：算不出方向，按 0 处理
        }
        Vector relative = (entityVelocity == null ? new Vector() : entityVelocity.clone())
                .subtract(viewerVelocity == null ? new Vector() : viewerVelocity);
        // 点积得到"每秒（tick 速度 ×20）朝我靠近多少格"
        return relative.dot(toViewer.multiply(1.0D / distance)) * 20.0D;
    }

    /** 该实体是否是 viewer 自己射出的投射物（箭 / 三叉戟 / 插件自己的导弹都算）。 */
    static boolean isOwnProjectile(Player viewer, Entity entity) {
        return entity instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter
                && shooter.getUniqueId().equals(viewer.getUniqueId());
    }

    /**
     * 统计相对 viewer 的最近威胁扇区与威胁数量。
     *
     * @param range        距离上限（格）
     * @param directedOnly {@code true} = 只算"以自己为目标"的威胁（RWR）；
     *                     {@code false} = 距离内的都算（MAWS）
     */
    private Threat scan(Player viewer, List<Emitter> emitters, double range, boolean directedOnly) {
        Threat threat = new Threat();
        for (Emitter emitter : emitters) {
            if (directedOnly && (emitter.targetId == null
                    || !emitter.targetId.equals(viewer.getUniqueId()))) {
                continue;
            }
            Entity entity = emitter.entity;
            if (entity == null || !entity.isValid() || entity.isDead()) {
                continue;
            }
            Location location = entity.getLocation();
            if (location.getWorld() == null || !location.getWorld().equals(viewer.getWorld())) {
                continue;
            }
            double distance = location.distance(viewer.getLocation());
            if (distance > range) {
                continue;
            }
            threat.add(sectorOf(viewer, location), distance);
        }
        return threat;
    }

    /** 触发（或维持）一级告警：更新 BossBar 文案并按周期播放告警音。 */
    private void alarm(Player player, State state, Threat threat, boolean missile) {
        state.toneTicks++;
        // 间隔来自 msl_config.yml：敌跟踪 0.2s = 4 tick，敌导弹 0.1s = 2 tick（需求值即默认值）
        int interval = missile ? Settings.rwrMissileInterval() : Settings.rwrTrackInterval();
        if (state.toneTicks >= interval) {
            state.toneTicks = 0;
            state.highTone = !state.highTone;
            this.playTone(player, missile, state.highTone);
        }
        String key;
        BarColor color;
        if (missile) {
            key = threat.count > 1 ? "rwr.bossbar-missile-multi" : "rwr.bossbar-missile";
            color = BarColor.RED;
        } else {
            key = threat.count > 1 ? "rwr.bossbar-track-multi" : "rwr.bossbar-track";
            color = BarColor.YELLOW;
        }
        this.display.show(player, RwrDisplay.Slot.RWR, Lang.get(key,
                "dir", Lang.get("rwr.dir-" + threat.sector), "count", threat.count), color);
    }

    /**
     * MAWS：红色 BossBar，文本取自语言文件的 {@code maws.bossbar}
     * （默认 {@code &c⚠ MAWS: %msl_maws%}），其中 {@code %msl_maws%} 替换为方向箭头。
     *
     * <p>与 RWR 用**不同槽位**，所以两条 BossBar 可以同时存在（需求 3.7）。
     * 告警音默认关闭（{@code maws.sound.enabled}）：否则会和 RWR 的敌导弹音叠在一起。
     */
    private void maws(Player player, State state, Threat threat) {
        if (Settings.mawsSoundEnabled() && ++state.mawsToneTicks >= Settings.mawsSoundInterval()) {
            state.mawsToneTicks = 0;
            this.playTone(player, true, false);      // 复用敌导弹音色
        }
        // %msl_maws% 是需求指定的占位符（同时也是 PlaceholderAPI 的占位符名）
        String text = Lang.get("maws.bossbar")
                .replace("%msl_maws%", Lang.get("rwr.dir-" + threat.sector));
        this.display.show(player, RwrDisplay.Slot.MAWS, text, BarColor.RED);
    }

    /** 敌跟踪：高音 / 低音交替；敌导弹：急促高音。 */
    private void playTone(Player player, boolean missile, boolean high) {
        if (!Settings.rwrSoundEnabled()) {
            return;
        }
        Location location = player.getLocation();
        if (missile) {
            player.playSound(location, Sound.BLOCK_NOTE_BLOCK_BIT,
                    Settings.rwrMissileVolume(), Settings.rwrMissilePitch());
            return;
        }
        player.playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, Settings.rwrTrackVolume(),
                high ? Settings.rwrTrackHighPitch() : Settings.rwrTrackLowPitch());
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

    /**
     * 告警来源：一个实体（射手 / 弹体 / 箭），以及它锁定的目标（MAWS 用不到，为 {@code null}）。
     *
     * <p>存实体而不是存快照位置：这样"接近率"能直接读它当前的速度，也能在扫描时复查有效性。
     */
    private static final class Emitter {

        private final UUID targetId;
        private final Entity entity;

        private Emitter(UUID targetId, Entity entity) {
            this.targetId = targetId;
            this.entity = entity;
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

    /** 单机 RWR / MAWS 状态。 */
    private static final class State {

        /** 是否满足开机条件（有权限 + 背包含指南针）；个人开关每 tick 另判。 */
        private boolean onboard;
        /** RWR 告警音计时（敌跟踪 / 敌导弹共用）。 */
        private int toneTicks;
        private boolean highTone;
        /** MAWS 告警音计时（默认不发声，见 maws.sound.enabled）。 */
        private int mawsToneTicks;
        /** MAWS 最近威胁所在扇区（-1 = 无威胁）；供 {@code %msl_maws%} 读，避免重复扫描。 */
        private int mawsSector = -1;

        private void reset() {
            this.toneTicks = 0;
            this.highTone = false;
            this.mawsToneTicks = 0;
            this.mawsSector = -1;
        }
    }
}
