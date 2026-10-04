package com.missile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
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
    static final double LOCK_RANGE = 128.0D;
    /** 准星锥角半角（度），准星附近多人时取最近者。 */
    static final double LOCK_CONE = 10.0D;
    /** 导引头提示刷新周期。 */
    private static final int REFRESH_INTERVAL_TICKS = 2;
    /** 出膛点相对眼睛的前移距离。 */
    private static final double MUZZLE_OFFSET = 1.2D;

    private final MissilePlugin plugin;
    private final Map<UUID, SeekerState> states = new HashMap<>();
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
        if (++this.refreshTicks < REFRESH_INTERVAL_TICKS) {
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
            state.updateLock(TargetSelector.select(player, LOCK_RANGE, LOCK_CONE, state.targetKind()));
            player.sendActionBar(state.actionBar());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // 该玩家的个人开关关闭：直接放行，其 TNT 恢复原版逻辑（其他玩家不受影响）
        if (!MissilePlugin.isPlayerEnabled(event.getPlayer().getUniqueId())) {
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
        state.updateLock(TargetSelector.select(player, LOCK_RANGE, LOCK_CONE, state.targetKind()));
        player.sendActionBar(state.actionBar());
    }

    private void launch(Player player, SeekerState state) {
        MissileType type = state.type();
        MissileType.TargetKind kind = state.targetKind();
        // 驾束（semiLOS）不需要锁定目标，直接跟随准星；其余型号按锁定类型选目标
        LivingEntity target = type.beamRiding()
                ? null
                : TargetSelector.select(player, LOCK_RANGE, LOCK_CONE, kind);
        if (target == null && !type.autonomous() && !type.beamRiding()) {
            state.updateLock(null);
            player.sendActionBar(Lang.get("seeker.need-illumination",
                    "missile", type.displayName(), "kind", kindLabel(kind)));
            return;
        }
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Location origin = eye.clone().add(direction.clone().multiply(MUZZLE_OFFSET));
        this.plugin.missiles().launch(player, type, target, origin, direction, kind);
        this.consumeTnt(player);
        state.disarm();
        String detail = type.beamRiding()
                ? Lang.get("seeker.launched-beam")
                : target == null
                        ? Lang.get("seeker.launched-none")
                        : Lang.get("seeker.launched-target", "target", describe(target));
        player.sendActionBar(Lang.get("seeker.launched",
                "missile", type.displayName(), "kind", kindLabel(kind), "detail", detail));
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

    private static boolean holdsTnt(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.TNT;
    }

    /** 锁定目标类型名，取自语言文件。 */
    static String kindLabel(MissileType.TargetKind kind) {
        return Lang.get(kind == MissileType.TargetKind.ENTITY ? "kind.entity" : "kind.player");
    }

    /** 目标显示名：玩家用名字，生物用实体类型名。 */
    static String describe(LivingEntity target) {
        return target instanceof Player player ? player.getName() : target.getType().name();
    }

    /** 玩家导引头状态：型号 + 开关 + 当前锁定。 */
    public static final class SeekerState {

        private MissileType missileType = MissileType.INFRARED;
        private MissileType.TargetKind targetKind = MissileType.TargetKind.PLAYER;
        private boolean armed;
        private UUID targetId;
        private String targetName;

        public MissileType type() {
            return this.missileType;
        }

        public void type(MissileType type) {
            this.missileType = type;
        }

        public MissileType.TargetKind targetKind() {
            return this.targetKind;
        }

        public void targetKind(MissileType.TargetKind targetKind) {
            this.targetKind = targetKind == null ? MissileType.TargetKind.PLAYER : targetKind;
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

        void updateLock(LivingEntity target) {
            if (target == null) {
                this.targetId = null;
                this.targetName = null;
                return;
            }
            this.targetId = target.getUniqueId();
            this.targetName = describe(target);
        }

        String actionBar() {
            if (this.missileType.beamRiding()) {
                return Lang.get("seeker.status-beam", "missile", this.missileType.displayName());
            }
            String lock = this.targetName == null
                    ? Lang.get("seeker.lock-search")
                    : Lang.get("seeker.lock-found", "target", this.targetName);
            return Lang.get("seeker.status", "missile", this.missileType.displayName(),
                    "kind", kindLabel(this.targetKind), "lock", lock);
        }
    }
}
