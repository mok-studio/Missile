package com.missile;

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
import org.bukkit.entity.SmallFireball;
import org.bukkit.util.Vector;

/**
 * 单发导弹实例。
 *
 * <p>实体方案：{@link SmallFireball} 作为弹体（物理运动 + 碰撞 + ProjectileHitEvent），
 * 不挂任何外形实体（末地烛 BlockDisplay 已按要求移除），只保留尾焰与尾烟粒子每 tick 在弹尾生成。
 *
 * <p>制导：红外 = 纯追踪 + 被**丢出的烈焰粉**干扰后改锁那件掉落物；半主动 = 由发射者准星持续照射；
 * 主动 = 比例导引（按实测目标速度解算提前量）+ 脱锁惯性记忆 + 自主重截获；
 * 半自动指令瞄准线由子类 SemiLOSMissile 重写制导钩子实现。
 *
 * <p>可重写钩子：selectTarget 选目标、steer 转向与速度、shouldDetonate 引爆条件、
 * checkDecoy 干扰判定。
 */
public class Missile {

    /**
     * 防作弊阈值：单 tick 位移超过这个平方值就认为目标"瞬移"（传送 / 假人），不做提前量外推。
     *
     * <p>它是**内部安全阈值**而非 gameplay 参数，所以留在代码里，不进 msl_config.yml。
     */
    private static final double MAX_OBSERVED_STEP_SQUARED = 4.0D;

    private final MissilePlugin plugin;
    private final MissileType type;
    private final UUID ownerId;
    private final SmallFireball projectile;

    private UUID targetId;
    /**
     * 最近一次干扰的**干扰源**（红外 = 丢出烈焰粉的投掷者；雷达弹 = 丢出铁粒的投掷者）。
     * 在 {@link Settings#decoyLockoutTicks()} 内不会重新锁定它——这就是"脱锁一段时间"（决策 #32）。
     */
    private UUID decoyId;
    /** 脱锁保护剩余 tick（&gt;0 时 {@link #decoyId} 不会被重新截获）。 */
    private int decoyLockoutTicks;
    private final MissileType.TargetKind targetKind;
    /** 弹上重新截获是否只认发射者的筛选白名单（{@code /msl ir ... usefilter}）。 */
    private final boolean whitelistOnly;
    /** SA 的锁定配置快照；非 SA 型号为 {@code null}（详见构造器注释）。 */
    private final SaProfile saProfile;
    Vector direction;
    private Vector targetVelocity = new Vector(0.0D, 0.0D, 0.0D);
    private Location lastTargetLocation;
    private Location inertialPoint;
    private int inertialTicks;
    double speed;
    private int ticks;
    private int decoyTicks;
    private int blindTicks;
    private boolean seekerDead;
    private boolean detonated;

    Missile(MissilePlugin plugin, MissileType type, Player owner, Location origin, Vector aim, Entity target) {
        this(plugin, type, owner, origin, aim, target, MissileType.TargetKind.PLAYER);
    }

    Missile(MissilePlugin plugin, MissileType type, Player owner, Location origin, Vector aim, Entity target,
            MissileType.TargetKind targetKind) {
        this(plugin, type, owner, origin, aim, target, targetKind, false);
    }

    Missile(MissilePlugin plugin, MissileType type, Player owner, Location origin, Vector aim, Entity target,
            MissileType.TargetKind targetKind, boolean whitelistOnly) {
        this(plugin, type, owner, origin, aim, target, targetKind, whitelistOnly, null);
    }

    /**
     * 完整构造器。
     *
     * @param saProfile 超级主动弹的**锁定配置快照**（其它型号传 {@code null}）。
     *                  非空时，弹上重新截获走 {@link TargetSelector#isValidSaTarget} 口径，
     *                  **完全不看全局 filter**（决策 #29）。传快照而不是引用会话状态，
     *                  是为了让"发射那一刻的配置"成为这一发的固定属性。
     */
    Missile(MissilePlugin plugin, MissileType type, Player owner, Location origin, Vector aim, Entity target,
            MissileType.TargetKind targetKind, boolean whitelistOnly, SaProfile saProfile) {
        this.targetKind = targetKind == null ? MissileType.TargetKind.PLAYER : targetKind;
        this.whitelistOnly = whitelistOnly;
        this.saProfile = saProfile;
        this.plugin = plugin;
        this.type = type;
        this.ownerId = owner.getUniqueId();
        this.direction = aim.clone().normalize();
        this.speed = type.initialSpeed();

        final Vector launchDirection = this.direction.clone();
        final double launchStep = this.speed / 20.0D;
        final Player shooter = owner;

        World world = origin.getWorld();
        this.projectile = world.spawn(origin, SmallFireball.class, fireball -> {
            fireball.setShooter(shooter);
            fireball.setIsIncendiary(false);
            fireball.setYield(0.0F);
            fireball.setDirection(launchDirection);
            fireball.setVelocity(launchDirection.clone().multiply(launchStep));
            fireball.setPersistent(false);
        });
        if (target != null) {
            this.setTarget(target);
        }
    }

    /**
     * 推进一 tick。
     *
     * @return {@code false} 表示本发导弹已结束，应从注册表移除
     */
    boolean tick() {
        if (this.detonated) {
            return false;
        }
        if (!this.projectile.isValid() || this.projectile.isDead()) {
            this.cleanup();
            return false;
        }
        this.ticks++;
        if (this.ticks > Settings.maxLifeTicks()) {
            this.detonate();
            return false;
        }

        Location position = this.projectile.getLocation();
        Entity target = this.selectTarget(position);
        if (target != null) {
            this.trackTargetMotion(target);
        }
        this.checkDecoy(position);

        this.steer(position, target);

        this.spawnExhaust(position);

        if (this.shouldDetonate(position, target)) {
            this.detonate();
            return false;
        }
        return true;
    }

    /** 每 tick 选择 / 更新目标；子类可重写（驾束类不需要目标）。 */
    Entity selectTarget(Location position) {
        Entity current = this.resolveTarget();
        return this.type.autonomous()
                ? this.updateAutonomousTarget(position, current)
                : this.updateIlluminatedTarget();
    }

    /** 每 tick 转向与速度控制；子类可重写（驾束类改为跟随发射者视线）。 */
    void steer(Location position, Entity target) {
        this.turnTowards(this.guidedDirection(position, target));
        this.speed = Math.min(this.type.maxSpeed(), this.speed + this.type.acceleration());
        this.projectile.setVelocity(this.direction.clone().multiply(this.speed / 20.0D));
    }

    /** 是否达到引爆条件；子类可重写（驾束类只在命中时引爆）。 */
    boolean shouldDetonate(Location position, Entity target) {
        return this.proximityFuse(position, target);
    }

    /** 红外 / 主动：自主截获与重新截获。 */
    private Entity updateAutonomousTarget(Location position, Entity current) {
        if (this.blindTicks > 0) {
            this.blindTicks--;
        }
        if (current == null && this.blindTicks == 0) {
            Entity acquired = TargetSelector.acquireEntityInCone(position, this.direction,
                    this.type.acquireRange(), this.type.acquireCone(), null, this.owner(), this.targetKind,
                    this.whitelistOnly, this.saProfile);
            // 脱锁保护期内不重新锁回干扰源（决策 #32）；这一 tick 就让它继续惯性飞行
            if (acquired != null && !this.lockedOut(acquired)) {
                this.setTarget(acquired);
                current = acquired;
            }
        }
        if (current != null) {
            this.inertialPoint = current.getLocation().clone();
            this.inertialTicks = Settings.inertialMemoryTicks();
        } else if (this.inertialTicks > 0) {
            this.inertialTicks--;
        } else {
            this.inertialPoint = null;
        }
        return current;
    }

    /** 半主动：必须由发射者准星持续照射，准星附近多人时取最近者。 */
    private Entity updateIlluminatedTarget() {
        if (this.seekerDead) {
            return null;
        }
        Player shooter = this.owner();
        if (shooter == null || !shooter.isOnline() || shooter.isDead()) {
            return null;
        }
        Entity designated = TargetSelector.selectEntity(shooter, this.type.acquireRange(),
                Math.max(this.type.acquireCone(), 10.0D), this.targetKind, this.whitelistOnly);
        if (designated != null && !designated.getUniqueId().equals(this.targetId)) {
            this.setTarget(designated);
            this.notifyOwner(Lang.get("seeker.designate-switch", "target", SeekerListener.describe(designated)));
        }
        return designated;
    }

    private void setTarget(Entity target) {
        this.targetId = target.getUniqueId();
        this.lastTargetLocation = target.getLocation().clone();
        this.targetVelocity = new Vector(0.0D, 0.0D, 0.0D);
        this.inertialPoint = target.getLocation().clone();
        this.inertialTicks = Settings.inertialMemoryTicks();
    }

    private Entity resolveTarget() {
        if (this.targetId == null) {
            return null;
        }
        Entity entity = this.plugin.getServer().getEntity(this.targetId);
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return null;                      // 非生物目标（载具等）同样走这条路径
        }
        if (!entity.getWorld().equals(this.projectile.getWorld())) {
            return null;
        }
        return entity;
    }

    /** 实测目标速度（格/tick），用于主动雷达的提前量解算。 */
    private void trackTargetMotion(Entity target) {
        Location now = target.getLocation();
        if (this.lastTargetLocation != null) {
            Vector step = now.toVector().subtract(this.lastTargetLocation.toVector());
            if (step.lengthSquared() <= MAX_OBSERVED_STEP_SQUARED) {
                this.targetVelocity = this.targetVelocity.multiply(0.65D).add(step.multiply(0.35D));
            } else {
                this.targetVelocity = new Vector(0.0D, 0.0D, 0.0D);
            }
        }
        this.lastTargetLocation = now.clone();
    }

    private Vector guidedDirection(Location position, Entity target) {
        Vector origin = position.toVector();
        if (target != null) {
            Vector aimPoint = TargetSelector.aimPoint(target).toVector();
            if (this.type.radarHoming()) {
                double step = Math.max(0.05D, this.speed / 20.0D);
                double lead = Math.min(60.0D, aimPoint.distance(origin) / step);
                aimPoint = aimPoint.add(this.targetVelocity.clone().multiply(lead));
            }
            Vector to = aimPoint.subtract(origin);
            if (to.lengthSquared() > 1.0E-6D) {
                return to.normalize();
            }
        } else if (this.type.radarHoming() && this.inertialPoint != null) {
            Vector to = this.inertialPoint.toVector().add(new Vector(0.0D, 1.0D, 0.0D)).subtract(origin);
            if (to.lengthSquared() > 1.0E-6D) {
                return to.normalize();
            }
        }
        return this.direction.clone();
    }

    /** 按每 tick 最大转弯角限制转向（罗德里格斯旋转）。 */
    void turnTowards(Vector desired) {
        Vector current = this.direction.clone().normalize();
        Vector wanted = desired.clone().normalize();
        double angle = current.angle(wanted);
        double maxTurn = Math.toRadians(this.type.turnRate());
        if (angle <= maxTurn || angle < 1.0E-4D) {
            this.direction = wanted;
            return;
        }
        Vector axis = current.clone().crossProduct(wanted);
        if (axis.lengthSquared() < 1.0E-8D) {
            this.direction = wanted;
            return;
        }
        Vector unitAxis = axis.normalize();
        Vector rotated = current.clone().multiply(Math.cos(maxTurn))
                .add(unitAxis.clone().crossProduct(current).multiply(Math.sin(maxTurn)))
                .add(unitAxis.clone().multiply(unitAxis.dot(current) * (1.0D - Math.cos(maxTurn))));
        this.direction = rotated.normalize();
    }

    /**
     * 干扰判定：按型号的 {@code decoy-material} 与 {@code decoy-chance} 决定是否脱锁。
     *
     * <p>干扰源是**玩家丢出的掉落物**（§2.4 / 决策 #31）：红外被干扰后脱锁并**改锁那件掉落的烈焰粉**，
     * 同时把投掷者记进 {@link #decoyId}，在 {@link Settings#decoyLockoutTicks()} 内不再锁回他。
     * 子类可重写为空实现。
     */
    void checkDecoy(Location position) {
        if (this.decoyLockoutTicks > 0) {
            this.decoyLockoutTicks--;
        }
        if (this.type.decoyChance() <= 0.0D) {
            return;
        }
        this.decoyTicks++;
        if (this.decoyTicks < Settings.decoyIntervalTicks()) {
            return;
        }
        this.decoyTicks = 0;
        Entity decoy = this.findDecoy(position);
        if (decoy == null || this.plugin.random().nextDouble() >= this.type.decoyChance()) {
            return;
        }
        UUID throwerId = decoyThrower(decoy);
        this.decoyId = throwerId;
        this.decoyLockoutTicks = throwerId == null ? 0 : Settings.decoyLockoutTicks();
        if (this.type.retargetsDecoy()) {
            this.setTarget(decoy);                     // 红外：改锁那件掉落的烈焰粉
            this.blindTicks = 0;
            this.notifyOwner(Lang.get("missile.decoy-infrared", "target", decoyLabel(decoy, throwerId)));
        } else if (this.type.radarHoming()) {
            this.targetId = null;
            this.blindTicks = Settings.reacquireDelayTicks();
            this.inertialTicks = Settings.inertialMemoryTicks();
            this.notifyOwner(Lang.get("missile.decoy-active"));
        } else {
            this.seekerDead = true;
            this.targetId = null;
            this.notifyOwner(Lang.get("missile.decoy-semi-active"));
        }
    }

    /** 脱锁保护期内不重新锁回干扰源。 */
    private boolean lockedOut(Entity candidate) {
        return this.decoyLockoutTicks > 0 && this.decoyId != null
                && this.decoyId.equals(candidate.getUniqueId());
    }

    /**
     * 找当前生效的干扰源：**优先**"玩家刚丢出、仍在 {@code decoy.ttl-ticks} 内"的掉落干扰物；
     * 只有 {@code decoy.require-thrown: false} 时才回退到旧行为（手持干扰物的玩家）。
     *
     * <p>几何规则与旧实现一致：必须在导弹前方（点积 &gt; 0）且在 {@code decoy.search-range} 内，取最近者。
     */
    private Entity findDecoy(Location position) {
        Material material = this.type.decoyMaterial();
        Vector origin = position.toVector();
        double range = Settings.decoySearchRange();
        boolean allowHeld = !Settings.decoyRequireThrown();
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : position.getWorld().getNearbyEntities(position, range, range, range)) {
            Entity candidate = null;
            if (entity instanceof Item item) {
                candidate = this.usableThrownDecoy(item, material);
            }
            if (candidate == null && allowHeld) {
                candidate = this.usableHeldDecoy(entity, material);
            }
            if (candidate == null) {
                continue;
            }
            Vector to = candidate instanceof Player player
                    ? player.getLocation().add(0.0D, 1.0D, 0.0D).toVector().subtract(origin)
                    : candidate.getLocation().toVector().subtract(origin);
            double distance = to.length();
            if (distance > range || this.direction.dot(to) <= 0.0D) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /** 掉落的干扰物是否可用：材质对得上、是**玩家丢出**的、还在有效期内、不是发射者自己丢的。 */
    private Entity usableThrownDecoy(Item item, Material material) {
        if (!item.isValid() || item.isDead() || item.getItemStack().getType() != material) {
            return null;
        }
        UUID thrower = item.getThrower();
        if (thrower == null || thrower.equals(this.ownerId)) {
            return null;                     // 发射器 / 漏斗 / 自然生成的不算；自己丢的也不算
        }
        return this.plugin.seekers().isLiveDecoy(item) ? item : null;
    }

    /** 旧行为回退：手持干扰物的玩家（仅 {@code decoy.require-thrown: false} 时启用）。 */
    private Entity usableHeldDecoy(Entity entity, Material material) {
        if (!(entity instanceof Player player) || player.isDead() || !player.isOnline()) {
            return null;
        }
        if (player.getUniqueId().equals(this.ownerId)
                || player.getGameMode() == GameMode.SPECTATOR
                || !holds(player, material)) {
            return null;
        }
        return player;
    }

    /** 干扰源的投掷者 UUID（掉落物取 {@code getThrower()}，手持回退就是该玩家）。 */
    private static UUID decoyThrower(Entity decoy) {
        if (decoy instanceof Item item) {
            return item.getThrower();
        }
        return decoy instanceof Player player ? player.getUniqueId() : null;
    }

    /** 给发射者看的干扰源名字：优先投掷者名，取不到才回落到实体名。 */
    private static String decoyLabel(Entity decoy, UUID throwerId) {
        Player thrower = throwerId == null ? null : Bukkit.getPlayer(throwerId);
        return thrower != null ? thrower.getName() : SeekerListener.describe(decoy);
    }

    private static boolean holds(Player player, Material material) {
        return player.getInventory().getItemInMainHand().getType() == material
                || player.getInventory().getItemInOffHand().getType() == material;
    }

    private boolean proximityFuse(Location position, Entity target) {
        if (target == null) {
            return false;
        }
        Location center = TargetSelector.aimPoint(target);
        return center.getWorld() != null
                && center.getWorld().equals(position.getWorld())
                && center.distanceSquared(position) <= Settings.proximityFuse() * Settings.proximityFuse();
    }

    /** 尾焰 + 尾烟，每 tick 生成。 */
    void spawnExhaust(Location position) {
        World world = position.getWorld();
        if (world == null || !this.hasNearbyViewer(world, position)) {
            return;
        }
        Vector back = this.direction.clone().multiply(-Settings.exhaustBackOffset());
        Location flame = position.clone().add(back);
        Location smoke = position.clone().add(back.multiply(Settings.exhaustSmokeBackMultiplier()));

        world.spawnParticle(this.type.flameParticle(), flame, Settings.exhaustFlameCount(),
                Settings.exhaustFlameSpread(), Settings.exhaustFlameSpread(),
                Settings.exhaustFlameSpread(), Settings.exhaustFlameExtra());
        world.spawnParticle(Particle.SMALL_FLAME, flame, Settings.exhaustInnerFlameCount(),
                Settings.exhaustInnerFlameSpread(), Settings.exhaustInnerFlameSpread(),
                Settings.exhaustInnerFlameSpread(), Settings.exhaustInnerFlameExtra());
        world.spawnParticle(this.type.smokeParticle(), smoke, Settings.exhaustSmokeCount(),
                Settings.exhaustSmokeSpread(), Settings.exhaustSmokeSpread(),
                Settings.exhaustSmokeSpread(), Settings.exhaustSmokeExtra());
        world.spawnParticle(Particle.CLOUD, smoke, Settings.exhaustCloudCount(),
                Settings.exhaustCloudSpread(), Settings.exhaustCloudSpread(),
                Settings.exhaustCloudSpread(), Settings.exhaustCloudExtra());
    }

    private boolean hasNearbyViewer(World world, Location position) {
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(position) <= Settings.particleViewerRange() * Settings.particleViewerRange()) {
                return true;
            }
        }
        return false;
    }

    /** 引爆：战斗部爆炸 + 清理实体。 */
    void detonate() {
        if (this.detonated) {
            return;
        }
        this.detonated = true;
        Location location = this.currentLocation();
        this.cleanup();
        if (location == null) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Player owner = this.owner();
        boolean breakBlocks = this.plugin.breakBlocks();
        if (owner != null) {
            world.createExplosion(location, this.type.explosionPower(), false, breakBlocks, owner);
        } else {
            world.createExplosion(location, this.type.explosionPower(), false, breakBlocks);
        }
        world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    /** 静默移除（关服 / 超限 / 弹体已消失），不爆炸。 */
    void discard() {
        this.detonated = true;
        this.cleanup();
    }

    private void cleanup() {
        if (this.projectile.isValid()) {
            this.projectile.remove();
        }
    }

    private Location currentLocation() {
        if (this.projectile.isValid()) {
            return this.projectile.getLocation();
        }
        return null;
    }

    private void notifyOwner(String message) {
        Player owner = this.owner();
        if (owner != null) {
            owner.sendActionBar(message);
        }
    }

    Player owner() {
        return this.plugin.getServer().getPlayer(this.ownerId);
    }

    SmallFireball projectile() {
        return this.projectile;
    }

    MissileType type() {
        return this.type;
    }

    UUID targetId() {
        return this.targetId;
    }

    UUID decoyId() {
        return this.decoyId;
    }

    boolean isDetonated() {
        return this.detonated;
    }
}
