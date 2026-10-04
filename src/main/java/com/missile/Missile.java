package com.missile;

import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 单发导弹实例。
 *
 * <p>实体方案：{@link SmallFireball} 作为弹体（物理运动 + 碰撞 + ProjectileHitEvent），
 * {@link ArmorStand}（marker，手持 TNT）每 tick 同步到弹体位置作为外形；
 * 尾焰与尾烟粒子每 tick 在弹尾生成。
 *
 * <p>制导：红外 = 纯追踪 + 烈焰棒改锁；半主动 = 由发射者准星持续照射；
 * 主动 = 比例导引（按实测目标速度解算提前量）+ 脱锁惯性记忆 + 自主重截获；
 * 半自动指令瞄准线由子类 SemiLOSMissile 重写制导钩子实现。
 *
 * <p>可重写钩子：selectTarget 选目标、steer 转向与速度、shouldDetonate 引爆条件、
 * checkDecoy 干扰判定。
 */
public class Missile {

    private static final int MAX_LIFE_TICKS = 20 * 30;
    private static final int DECOY_INTERVAL_TICKS = 20;
    private static final int REACQUIRE_DELAY_TICKS = 60;
    private static final int INERTIAL_MEMORY_TICKS = 100;
    private static final double DECOY_RANGE = 48.0D;
    private static final double PROXIMITY_FUSE = 3.0D;
    private static final double PARTICLE_RANGE_SQUARED = 96.0D * 96.0D;
    private static final double MAX_OBSERVED_STEP_SQUARED = 4.0D;

    private final MissilePlugin plugin;
    private final MissileType type;
    private final UUID ownerId;
    private final SmallFireball projectile;
    private final ArmorStand body;

    private UUID targetId;
    private UUID decoyId;
    private final MissileType.TargetKind targetKind;
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

    Missile(MissilePlugin plugin, MissileType type, Player owner, Location origin, Vector aim, LivingEntity target) {
        this(plugin, type, owner, origin, aim, target, MissileType.TargetKind.PLAYER);
    }

    Missile(MissilePlugin plugin, MissileType type, Player owner, Location origin, Vector aim, LivingEntity target,
            MissileType.TargetKind targetKind) {
        this.targetKind = targetKind == null ? MissileType.TargetKind.PLAYER : targetKind;
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
        this.body = world.spawn(origin, ArmorStand.class, stand -> {
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setVisible(true);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(false);
            stand.setItem(EquipmentSlot.HAND, new ItemStack(Material.TNT));
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
        if (this.ticks > MAX_LIFE_TICKS) {
            this.detonate();
            return false;
        }

        Location position = this.projectile.getLocation();
        LivingEntity target = this.selectTarget(position);
        if (target != null) {
            this.trackTargetMotion(target);
        }
        this.checkDecoy(position);

        this.steer(position, target);

        this.syncBody(position);
        this.spawnExhaust(position);

        if (this.shouldDetonate(position, target)) {
            this.detonate();
            return false;
        }
        return true;
    }

    /** 每 tick 选择 / 更新目标；子类可重写（驾束类不需要目标）。 */
    LivingEntity selectTarget(Location position) {
        LivingEntity current = this.resolveTarget();
        return this.type.autonomous()
                ? this.updateAutonomousTarget(position, current)
                : this.updateIlluminatedTarget();
    }

    /** 每 tick 转向与速度控制；子类可重写（驾束类改为跟随发射者视线）。 */
    void steer(Location position, LivingEntity target) {
        this.turnTowards(this.guidedDirection(position, target));
        this.speed = Math.min(this.type.maxSpeed(), this.speed + this.type.acceleration());
        this.projectile.setVelocity(this.direction.clone().multiply(this.speed / 20.0D));
    }

    /** 是否达到引爆条件；子类可重写（驾束类只在命中时引爆）。 */
    boolean shouldDetonate(Location position, LivingEntity target) {
        return this.proximityFuse(position, target);
    }

    /** 红外 / 主动：自主截获与重新截获。 */
    private LivingEntity updateAutonomousTarget(Location position, LivingEntity current) {
        if (this.blindTicks > 0) {
            this.blindTicks--;
        }
        if (current == null && this.blindTicks == 0) {
            LivingEntity acquired = TargetSelector.acquireInCone(position, this.direction,
                    this.type.acquireRange(), this.type.acquireCone(), null, this.owner(), this.targetKind);
            if (acquired != null) {
                this.setTarget(acquired);
                current = acquired;
            }
        }
        if (current != null) {
            this.inertialPoint = current.getLocation().clone();
            this.inertialTicks = INERTIAL_MEMORY_TICKS;
        } else if (this.inertialTicks > 0) {
            this.inertialTicks--;
        } else {
            this.inertialPoint = null;
        }
        return current;
    }

    /** 半主动：必须由发射者准星持续照射，准星附近多人时取最近者。 */
    private LivingEntity updateIlluminatedTarget() {
        if (this.seekerDead) {
            return null;
        }
        Player shooter = this.owner();
        if (shooter == null || !shooter.isOnline() || shooter.isDead()) {
            return null;
        }
        LivingEntity designated = TargetSelector.select(shooter, this.type.acquireRange(),
                Math.max(this.type.acquireCone(), 10.0D), this.targetKind);
        if (designated != null && !designated.getUniqueId().equals(this.targetId)) {
            this.setTarget(designated);
            this.notifyOwner(Lang.get("seeker.designate-switch", "target", designated.getName()));
        }
        return designated;
    }

    private void setTarget(LivingEntity target) {
        this.targetId = target.getUniqueId();
        this.lastTargetLocation = target.getLocation().clone();
        this.targetVelocity = new Vector(0.0D, 0.0D, 0.0D);
        this.inertialPoint = target.getLocation().clone();
        this.inertialTicks = INERTIAL_MEMORY_TICKS;
    }

    private LivingEntity resolveTarget() {
        if (this.targetId == null) {
            return null;
        }
        Entity entity = this.plugin.getServer().getEntity(this.targetId);
        if (!(entity instanceof LivingEntity target) || target.isDead() || !target.isValid()) {
            return null;
        }
        if (!target.getWorld().equals(this.projectile.getWorld())) {
            return null;
        }
        return target;
    }

    /** 实测目标速度（格/tick），用于主动雷达的提前量解算。 */
    private void trackTargetMotion(LivingEntity target) {
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

    private Vector guidedDirection(Location position, LivingEntity target) {
        Vector origin = position.toVector();
        if (target != null) {
            Vector aimPoint = target.getEyeLocation().toVector();
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

    /** 干扰判定：烈焰棒 / 铁粒，按各自概率脱锁；子类可重写为空实现。 */
    void checkDecoy(Location position) {
        if (this.type.decoyChance() <= 0.0D) {
            return;
        }
        this.decoyTicks++;
        if (this.decoyTicks < DECOY_INTERVAL_TICKS) {
            return;
        }
        this.decoyTicks = 0;
        Player decoy = this.findDecoyHolder(position);
        if (decoy == null || this.plugin.random().nextDouble() >= this.type.decoyChance()) {
            return;
        }
        this.decoyId = decoy.getUniqueId();
        if (this.type.retargetsDecoy()) {
            this.setTarget(decoy);
            this.blindTicks = 0;
            this.notifyOwner(Lang.get("missile.decoy-infrared", "target", decoy.getName()));
        } else if (this.type.radarHoming()) {
            this.targetId = null;
            this.blindTicks = REACQUIRE_DELAY_TICKS;
            this.inertialTicks = INERTIAL_MEMORY_TICKS;
            this.notifyOwner(Lang.get("missile.decoy-active"));
        } else {
            this.seekerDead = true;
            this.targetId = null;
            this.notifyOwner(Lang.get("missile.decoy-semi-active"));
        }
    }

    private Player findDecoyHolder(Location position) {
        Material material = this.type.decoyMaterial();
        Vector origin = position.toVector();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : position.getWorld()
                .getNearbyEntities(position, DECOY_RANGE, DECOY_RANGE, DECOY_RANGE)) {
            if (!(entity instanceof Player player) || player.isDead() || !player.isOnline()) {
                continue;
            }
            if (player.getUniqueId().equals(this.ownerId)
                    || player.getGameMode() == GameMode.SPECTATOR
                    || !holds(player, material)) {
                continue;
            }
            Vector to = player.getLocation().add(0.0D, 1.0D, 0.0D).toVector().subtract(origin);
            double distance = to.length();
            if (distance > DECOY_RANGE || this.direction.dot(to) <= 0.0D) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private static boolean holds(Player player, Material material) {
        return player.getInventory().getItemInMainHand().getType() == material
                || player.getInventory().getItemInOffHand().getType() == material;
    }

    private boolean proximityFuse(Location position, LivingEntity target) {
        if (target == null) {
            return false;
        }
        Location center = target.getEyeLocation();
        return center.getWorld() != null
                && center.getWorld().equals(position.getWorld())
                && center.distanceSquared(position) <= PROXIMITY_FUSE * PROXIMITY_FUSE;
    }

    void syncBody(Location position) {
        if (!this.body.isValid()) {
            return;
        }
        Location location = position.clone();
        location.setDirection(this.direction);
        this.body.teleport(location);
    }

    /** 尾焰 + 尾烟，每 tick 生成。 */
    void spawnExhaust(Location position) {
        World world = position.getWorld();
        if (world == null || !this.hasNearbyViewer(world, position)) {
            return;
        }
        Vector back = this.direction.clone().multiply(-0.55D);
        Location flame = position.clone().add(back);
        Location smoke = position.clone().add(back.multiply(3.0D));

        world.spawnParticle(this.type.flameParticle(), flame, 4, 0.06D, 0.06D, 0.06D, 0.0D);
        world.spawnParticle(Particle.SMALL_FLAME, flame, 2, 0.05D, 0.05D, 0.05D, 0.0D);
        world.spawnParticle(this.type.smokeParticle(), smoke, 3, 0.14D, 0.14D, 0.14D, 0.004D);
        world.spawnParticle(Particle.CLOUD, smoke, 2, 0.16D, 0.16D, 0.16D, 0.002D);
    }

    private boolean hasNearbyViewer(World world, Location position) {
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(position) <= PARTICLE_RANGE_SQUARED) {
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
        if (this.body.isValid()) {
            this.body.remove();
        }
        if (this.projectile.isValid()) {
            this.projectile.remove();
        }
    }

    private Location currentLocation() {
        if (this.projectile.isValid()) {
            return this.projectile.getLocation();
        }
        if (this.body.isValid()) {
            return this.body.getLocation();
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
