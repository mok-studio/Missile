package com.missile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 导引头交互与命中处理。
 *
 * <p>手持 TNT 右键：第一次开启导引头并锁定视线内玩家，第二次发射。
 * 型号只由 /missile 命令选择，右键不做型号循环、不判定副手。
 */
public final class SeekerListener implements Listener {

    /** 锁定距离。 */
    /** 准星锥角半角（度），准星附近多人时取最近者。 */
    /** 导引头提示刷新周期。 */
    /** 出膛点相对眼睛的前移距离。 */

    private final MissilePlugin plugin;
    private final Map<UUID, SeekerState> states = new HashMap<>();

    /** 玩家丢出的干扰物：掉落物实体 UUID → 丢弃时的 tick（§2.4）。 */
    private final Map<UUID, Integer> thrownDecoys = new HashMap<>();
    private int refreshTicks;

    SeekerListener(MissilePlugin plugin) {
        this.plugin = plugin;
    }

    /** 取（或创建）玩家导引头状态。 */
    public SeekerState state(Player player) {
        return this.states.computeIfAbsent(player.getUniqueId(), key -> new SeekerState());
    }

    /** 只读查询：未创建过状态的玩家返回 {@code null}（RWR 用，避免为所有在线玩家建状态）。 */
    public SeekerState stateIfPresent(Player player) {
        return this.states.get(player.getUniqueId());
    }

    public void clearAll() {
        this.states.clear();
    }

    /** 每 tick 由主类调用：刷新已开启导引头玩家的锁定与提示。 */
    void refresh() {
        if (this.states.isEmpty()) {
            return;
        }
        if (!MissilePlugin.isGlobalEnabled()) {
            // 全服开关关闭：清空所有导引头（玩家个人设置保留），避免残留锁定与提示
            for (SeekerState state : this.states.values()) {
                state.disarm();
            }
            return;
        }
        if (++this.refreshTicks < Settings.seekerRefreshTicks()) {
            return;
        }
        this.refreshTicks = 0;
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            SeekerState state = this.states.get(player.getUniqueId());
            if (state == null || !state.armed()) {
                continue;
            }
            if (!MissilePlugin.isPlayerEnabled(player.getUniqueId())) {
                // 该玩家关闭了自己的导弹开关：清空其导引头状态，避免残留锁定与提示
                state.disarm();
                continue;
            }
            if (!holdsTnt(player)) {
                state.disarm();
                player.sendActionBar(Lang.get("seeker.closed"));
                continue;
            }
            state.updateLock(state.selectLock(player));
            player.sendActionBar(state.actionBar(this.plugin, player));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // 全服开关关闭，或该玩家自己关闭了导弹：直接放行，TNT 走原版逻辑
        if (!MissilePlugin.isGlobalEnabled()
                || !MissilePlugin.isPlayerEnabled(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!holdsTnt(player) || !player.hasPermission("missile.use")) {
            return;
        }
        // 阻止 TNT 被放置 / 与方块交互：右键只服务于导引头
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        SeekerState state = this.state(player);
        if (state.armed()) {
            this.launch(player, state);
        } else {
            this.arm(player, state);
        }
    }

    private void arm(Player player, SeekerState state) {
        state.arm();
        state.updateLock(state.selectLock(player));
        player.sendActionBar(state.actionBar(this.plugin, player));
    }

    private void launch(Player player, SeekerState state) {
        MissileType type = state.type();
        MissileType.TargetKind kind = state.lockKind();
        // 驾束（semiLOS）不需要锁定目标，直接跟随准星；其余型号按"实际生效的锁定类型"选目标
        // （IR 的 entity 模式与 usefilter 都是两段式/白名单判定，统一收在 SeekerState#selectLock 里）
        Entity target = state.selectLock(player);
        if (target == null && !type.autonomous() && !type.beamRiding()) {
            state.updateLock(null);
            player.sendActionBar(Lang.get("seeker.need-illumination",
                    "missile", type.coloredName(), "kind", kindLabel(kind)));
            return;
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location origin = eye.clone().add(direction.clone().multiply(Settings.muzzleOffset()));
        // §1.2：SA 要把"发射那一刻的锁定配置"作为快照一次性传给导弹（弹上重新截获沿用同一份配置）；
        // 其它型号传 null，走原有的"类型 + 筛选白名单"判定。
        SaProfile saProfile = type == MissileType.SUPER_ACTIVE ? state.saProfile() : null;
        this.plugin.missiles().launch(player, type, target, origin, direction, kind, state.whitelistOnly(),
                saProfile);
        this.consumeTnt(player);
        state.disarm();
        // §2.6：发射提示统一为一条（`&6%msl% &a已发射`），不再按是否锁定 / 是否驾束分叉。
        // `%msl%` 由占位符渲染器替换（型号名自带颜色，见 §2.7）。
        player.sendActionBar(MissilePlaceholders.render(this.plugin, player, Lang.get("seeker.launched")));
    }

    private void consumeTnt(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.TNT) {
            return;
        }
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
        }
    }

    /**
     * 弹体命中：拦截小火球的默认点燃/爆炸，改由导弹战斗部统一引爆。
     * 尾焰与尾烟在 {@link Missile} 中每 tick 生成，这里补命中瞬间的火焰爆发。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileHit(ProjectileHitEvent event) {
        Missile missile = this.plugin.missiles().forProjectile(event.getEntity());
        if (missile == null) {
            return;
        }
        event.setCancelled(true);
        Location impact = event.getEntity().getLocation();
        World world = impact.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FLAME, impact, 20, 0.35D, 0.35D, 0.35D, 0.05D);
            world.spawnParticle(Particle.LARGE_SMOKE, impact, 12, 0.4D, 0.4D, 0.4D, 0.02D);
        }
        missile.detonate();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        SeekerState state = this.states.get(event.getEntity().getUniqueId());
        if (state != null) {
            state.disarm();
        }
    }

    /**
     * 记录"玩家丢出的干扰物"（§2.4：手持无效，**只有丢出**才可能干扰）。
     *
     * <p>用 {@link Bukkit#getCurrentTick()} 打时间戳而不是自带计数器：
     * 事件驱动的监听器没有 tick 钩子，时间戳让 TTL 判定与清理都能按需惰性完成。
     */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        int now = currentTick();
        this.thrownDecoys.put(event.getItemDrop().getUniqueId(), now);
        // 顺手清理过期条目，避免长期运行时这张表无限增长（条目本身很小）
        if (this.thrownDecoys.size() > 64) {
            this.thrownDecoys.entrySet().removeIf(entry -> now - entry.getValue() > Settings.decoyTtlTicks());
        }
    }

    /**
     * 该掉落的干扰物是否"玩家刚丢出且仍在有效期内"。
     *
     * <p>不满足的三种情况都会返回 {@code false}：不是玩家丢的（发射器 / 漏斗 / 自然生成、
     * 以及**插件重载前**就已经在地上的）、超过 {@code decoy.ttl-ticks}、已被查询过并清理。
     */
    public boolean isLiveDecoy(Item item) {
        Integer droppedAt = this.thrownDecoys.get(item.getUniqueId());
        if (droppedAt == null) {
            return false;
        }
        if (currentTick() - droppedAt > Settings.decoyTtlTicks()) {
            this.thrownDecoys.remove(item.getUniqueId());
            return false;
        }
        return true;
    }

    /**
     * 当前 tick。服务端 tick 是首选；取不到时（无服务端、或万一在异步上下文里被调用）
     * 回退到 0 —— TTL 判定退化为"只看注入的时间戳"，不会因为一次 tick 读取把制导链打断。
     */
    private static int currentTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable throwable) {
            return 0;
        }
    }

    private static boolean holdsTnt(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.TNT;
    }

    /** 锁定目标类型名，取自语言文件。 */
    static String kindLabel(MissileType.TargetKind kind) {
        if (kind == MissileType.TargetKind.ENTITY) {
            return Lang.get("kind.entity");
        }
        if (kind == MissileType.TargetKind.ANY) {
            return Lang.get("kind.any");
        }
        return Lang.get("kind.player");
    }

    /** 目标显示名：玩家用名字，生物用实体类型名。 */
    static String describe(Entity target) {
        return target instanceof Player player ? player.getName() : target.getType().name();
    }

    /** 玩家导引头状态：型号 + 开关 + 当前锁定。 */
    public static final class SeekerState {

        /** 红外弹的工作模式（{@code /msl ir ...}）。 */
        public enum IrMode {
            /** 默认：玩家优先。 */
            DEFAULT,
            /** 等同默认（显式写 player）。 */
            PLAYER,
            /** 玩家优先；脱离玩家锁定后转为"任意实体"继续搜索。 */
            ENTITY
        }

        private MissileType missileType = MissileType.INFRARED;
        private IrMode irMode = IrMode.DEFAULT;
        private boolean irUseFilter;

        /**
         * SA（超级主动弹）的工作模式 + **safilter**（§1.2 的 `filter` 名单）。
         *
         * <p>与会话状态一致：**不持久化**（决策 #29，与决策 #15 同口径）；
         * 与全局 {@link TargetFilter} 完全独立，互不影响。
         */
        private SaProfile.Mode saMode = SaProfile.Mode.ANY;
        private final Set<String> saEntityIds = new LinkedHashSet<>();
        private final Set<UUID> saPlayerIds = new LinkedHashSet<>();
        private final Set<String> saPlayerNames = new LinkedHashSet<>();
        /**
         * safilter 玩家类的"名字 → UUID"索引（小写名字）。
         *
         * <p>存在理由：`remove` 需要**同时**摘掉 UUID 与名字。只删名字的话，
         * 离线玩家重新上线时会因为 UUID 还在名单里而被放行（{@code SaProfile#allowsPlayer}
         * 是"命中任一即允许"）。safilter 是会话级数据，不存在老存档问题，所以直接在内存里维护即可。
         */
        private final Map<String, UUID> saPlayerIndex = new LinkedHashMap<>();

        private boolean armed;
        private UUID targetId;
        private String targetName;

        public MissileType type() {
            return this.missileType;
        }

        public void type(MissileType type) {
            this.missileType = type;
        }

        /**
         * 本次制导**实际生效**的锁定类型（供 /msl status 显示、以及发射时传给导弹做弹上重截获）。
         *
         * <ul>
         *   <li>{@code SUPER_ACTIVE} → {@link #saKind()}（由 {@link #saMode} 折算；旧命令
         *       {@code /msl super_active default|entity|player} 仍可用）</li>
         *   <li>{@code INFRARED} + {@code usefilter} → {@code ANY}：能锁谁完全由白名单决定</li>
         *   <li>{@code INFRARED} + {@code entity} 模式 → {@code ANY}：玩家优先，丢失后转任意实体，
         *       弹上重截获也允许两者</li>
         *   <li>其余（{@code SEMI_ACTIVE} / {@code ACTIVE} / {@code SEMI_LOS}）→ 沿用原行为：只锁玩家</li>
         * </ul>
         */
        public MissileType.TargetKind lockKind() {
            if (this.missileType == MissileType.SUPER_ACTIVE) {
                return saKind();
            }
            if (this.missileType == MissileType.INFRARED && (this.irUseFilter || this.irMode == IrMode.ENTITY)) {
                return MissileType.TargetKind.ANY;
            }
            return MissileType.TargetKind.PLAYER;
        }

        /** 是否把筛选白名单当作唯一候选集合（仅 {@code /msl ir ... usefilter}）。 */
        public boolean whitelistOnly() {
            return this.missileType == MissileType.INFRARED && this.irUseFilter;
        }

        /**
         * 导引头此刻该锁谁（开导引头、每 2 tick 刷新、发射、切换型号后立刻重算，四处共用同一套判定）。
         *
         * <p>IR 的 {@code entity} 模式是**两段式**：先按默认工作方式找玩家，找不到玩家才转为找任意生物
         * （对应"默认工作方式下搜索不到玩家脱离锁定之后，将锁定目标转换到任意实体"）。
         * {@code usefilter} 走白名单通道：候选集合由白名单决定，距离 / 锥角 / 视线可达规则不变。
         *
         * @return 无锁定时返回 {@code null}
         */
        Entity selectLock(Player shooter) {
            if (this.missileType.beamRiding()) {
                return null;                      // 驾束弹不锁目标，跟随准星
            }
            boolean whitelistOnly = this.whitelistOnly();
            double range = Settings.lockRange();
            double cone = Settings.lockCone();
            // 候选类别由 effectiveKind 折算：**筛选模式打开时以白名单启用的类别为准**
            // （否则"玩家被白名单挡、生物被类型挡"，交集为空、什么都锁不上——用户 2026-10-05 反馈）
            MissileType.TargetKind effective = TargetSelector.effectiveKind(shooter, this.lockKind(), whitelistOnly);
            if (effective == MissileType.TargetKind.ANY) {
                // 两类都允许 → 两段式：先玩家优先，找不到再退到任意目标
                // （同时覆盖 IR 的 entity 模式与"白名单两类都启用"）
                Entity player = TargetSelector.selectEntity(shooter, range, cone,
                        MissileType.TargetKind.PLAYER, whitelistOnly);
                if (player != null) {
                    return player;
                }
                return TargetSelector.selectEntity(shooter, range, cone,
                        MissileType.TargetKind.ANY, whitelistOnly);
            }
            return TargetSelector.selectEntity(shooter, range, cone, effective, whitelistOnly);
        }

        public IrMode irMode() {
            return this.irMode;
        }

        public void irMode(IrMode irMode) {
            this.irMode = irMode == null ? IrMode.DEFAULT : irMode;
        }

        /**
         * 红外弹是否只按 filter 白名单挑目标（{@code /msl ir <模式> usefilter}）。
         *
         * <p>它只改变**候选目标集合**（必须命中白名单），不改变锁定逻辑：
         * 距离 / 锥角 / 视线可达依旧生效（用户 2026-10-05 拍板）。
         */
        public boolean irUseFilter() {
            return this.irUseFilter;
        }

        public void irUseFilter(boolean value) {
            this.irUseFilter = value;
        }

        /**
         * 当前 SA 锁定配置的**不可变快照**（发射时传给导弹，弹上重新截获用它判定）。
         *
         * <p>每次现取一份新副本：导弹拿到后，玩家之后再改 safilter 也不会影响已发射的那发。
         */
        public SaProfile saProfile() {
            return new SaProfile(this.saMode, this.saEntityIds, this.saPlayerIds, this.saPlayerNames);
        }

        /** SA 当前工作模式。 */
        public SaProfile.Mode saMode() {
            return this.saMode;
        }

        /** 设置 SA 工作模式（{@code default|entity|player}）。 */
        public void saMode(SaProfile.Mode mode) {
            this.saMode = mode == null ? SaProfile.Mode.ANY : mode;
        }

        /** safilter 里的实体 ID（完整注册键，只读副本）。 */
        public Set<String> saEntityIds() {
            return Set.copyOf(this.saEntityIds);
        }

        /** safilter 里的玩家 UUID（只读副本）。 */
        public Set<UUID> saPlayerIds() {
            return Set.copyOf(this.saPlayerIds);
        }

        /** safilter 里的玩家名（小写，只读副本）。 */
        public Set<String> saPlayerNames() {
            return Set.copyOf(this.saPlayerNames);
        }

        /** 清空 safilter（保留当前模式）。 */
        public void clearSaFilter() {
            this.saEntityIds.clear();
            clearSaPlayers();
        }

        /** 只清空 safilter 的实体类条目（`/msl super_active entity clear`）。 */
        public void clearSaEntityIds() {
            this.saEntityIds.clear();
        }

        /** 只清空 safilter 的玩家类条目（`/msl super_active player clear`）。 */
        public void clearSaPlayers() {
            this.saPlayerIds.clear();
            this.saPlayerNames.clear();
            this.saPlayerIndex.clear();
        }

        /** 从 safilter 的实体类移除一个 ID；返回它原先是否在名单里。 */
        public boolean removeSaEntityId(String id) {
            return id != null && !id.isEmpty() && this.saEntityIds.remove(id);
        }

        /**
         * 从 safilter 的玩家类移除一个玩家；UUID 与名字一起摘。
         *
         * <p>名字走"名字 → UUID"索引（{@link #saPlayerIndex}），所以**离线玩家也能被移除**；
         * 索引里找不到时（例如手工塞进来的条目）退化为按在线玩家解析一次。
         *
         * @return 是否真的移除了什么
         */
        public boolean removeSaPlayer(String playerName, UUID playerId) {
            boolean removed = false;
            if (playerName != null && !playerName.isBlank()) {
                String lower = playerName.toLowerCase(Locale.ROOT);
                removed |= this.saPlayerNames.remove(lower);
                UUID indexed = this.saPlayerIndex.remove(lower);
                if (indexed != null) {
                    removed |= this.saPlayerIds.remove(indexed);
                }
            }
            if (playerId != null) {
                removed |= this.saPlayerIds.remove(playerId);
                // UUID 与名字是同一个玩家的两半：按 UUID 删除时，索引里对应的名字也要一起摘掉
                this.saPlayerIndex.entrySet().removeIf(entry -> {
                    if (!playerId.equals(entry.getValue())) {
                        return false;
                    }
                    this.saPlayerNames.remove(entry.getKey());
                    return true;
                });
            }
            return removed;
        }

        /** {@code /msl super_active default}：回到任意目标 + 清空 safilter（决策 #27）。 */
        public void saReset() {
            this.saMode = SaProfile.Mode.ANY;
            clearSaFilter();
        }

        /**
         * {@code /msl default}：把该玩家的**全部会话级导弹设置**恢复出厂值。
         *
         * <p>明确**不动**两样东西（条目要求）：玩家选的型号，以及 {@code /msl on|off} 开关。
         * 导引头一并关闭（"默认"= 没开着导引头）；全局 filter 白名单由命令层另行清空。
         */
        public void resetToDefaults() {
            this.irMode = IrMode.DEFAULT;
            this.irUseFilter = false;
            saReset();
            disarm();
        }

        /** 把实体 ID 写进 safilter（调用方负责先做规范化 / 别名展开）。 */
        public void addSaEntityId(String id) {
            if (id != null && !id.isEmpty()) {
                this.saEntityIds.add(id);
            }
        }

        /** 把玩家写进 safilter：UUID 为主、名字兜底（§1.2）。 */
        public void addSaPlayer(UUID playerId, String playerName) {
            if (playerId != null) {
                this.saPlayerIds.add(playerId);
            }
            if (playerName != null && !playerName.isBlank()) {
                String lower = playerName.toLowerCase(Locale.ROOT);
                this.saPlayerNames.add(lower);
                if (playerId != null) {
                    this.saPlayerIndex.put(lower, playerId);
                }
            }
        }

        /**
         * 兼容旧命令 {@code /msl super_active default|entity|player} 的锁定类型口径
         * （R9 会被 {@code saMode} 的新语法取代，但状态显示与旧用法仍可用）。
         *
         * <p>注意：设置模式会**清空 safilter** —— "entity/player 不带 ID"的语义就是"不限制"。
         */
        public void saKind(MissileType.TargetKind kind) {
            if (kind == MissileType.TargetKind.ENTITY) {
                saMode(SaProfile.Mode.ENTITY);
            } else if (kind == MissileType.TargetKind.PLAYER) {
                saMode(SaProfile.Mode.PLAYER);
            } else {
                saMode(SaProfile.Mode.ANY);
            }
            clearSaFilter();
        }

        /** {@link #saMode} 折算出的锁定类型（供状态显示与旧调用点使用）。 */
        public MissileType.TargetKind saKind() {
            return switch (this.saMode) {
                case ENTITY -> MissileType.TargetKind.ENTITY;
                case PLAYER -> MissileType.TargetKind.PLAYER;
                case ANY -> MissileType.TargetKind.ANY;
            };
        }

        public boolean armed() {
            return this.armed;
        }

        public UUID targetId() {
            return this.targetId;
        }

        public String targetName() {
            return this.targetName;
        }

        void arm() {
            this.armed = true;
        }

        void disarm() {
            this.armed = false;
            this.targetId = null;
            this.targetName = null;
        }

        void updateLock(Entity target) {
            if (target == null) {
                this.targetId = null;
                this.targetName = null;
                return;
            }
            this.targetId = target.getUniqueId();
            this.targetName = describe(target);
        }

        /**
         * 导引头状态栏（ActionBar）。
         *
         * <p>模板来自语言文件的 {@code seeker.status}，可被 {@code config.yml} 的
         * {@code messages:} 按同 key 覆盖；模板里的 {@code %msl%} / {@code %msl_target_kind%} /
         * {@code %msl_entity_name%} 等由 {@link MissilePlaceholders#render} 替换 ——
         * **与 PlaceholderAPI 共用同一套取值逻辑**，所以屏幕上看到的和 TAB 里显示的永远一致，
         * 而且没装 PlaceholderAPI 的服务器同样能正常显示（不依赖 PAPI）。
         *
         * <p>调用点都直接传 {@link #states} 里的实例，而 {@code render} 内部也是按 UUID 查同一个实例，
         * 因此不会出现两套状态。
         *
         * <p>**锁定时左右包裹**（§2.5）：已锁定目标时在文本两端各加一段 {@code launcher.lock-padding}
         * （默认 {@code " &f&k1"}，即"一个空格 + 白色乱码"）。未锁定（搜索中）与驾束弹**不加**。
         */
        String actionBar(MissilePlugin plugin, Player player) {
            if (this.missileType.beamRiding()) {
                // 驾束弹没有"锁定"概念，固定文案、不包裹
                return Lang.get("seeker.status-beam", "missile", this.missileType.coloredName());
            }
            String rendered = MissilePlaceholders.render(plugin, player, Lang.get("seeker.status"));
            return this.targetName == null ? rendered : padLocked(rendered);
        }
    }

    /**
     * 给"已锁定"的状态栏文本加左右包裹（§2.5）。
     *
     * <p><b>与主文本之间的空格</b>：条目要求**左右两侧都与主文本相隔一个空格** ——
     * 改造前是把配置原样前后各贴一份，配置自带的前导空格只让**右侧**有空格，
     * 左侧的乱码字符会直接顶着型号名（看起来挤在一起）。现在统一由这里补空格，
     * 配置里首尾的空白不再有意义（1.0.2 的 {@code " &f&k1"} 与新的 {@code "&f&k1"} 结果一致）。
     *
     * <p>只对包裹段本身做一次着色，免得把模板或玩家名里可能出现的 {@code &} 当成色码。
     * 配置写成空串时原样返回 —— 纯函数，脱离服务端也能验证。
     */
    static String padLocked(String rendered) {
        String padding = Lang.colorize(Settings.seekerLockPadding()).trim();
        return padding.isEmpty() ? rendered : padding + " " + rendered + " " + padding;
    }
}
