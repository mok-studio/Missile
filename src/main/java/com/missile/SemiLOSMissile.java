package com.missile;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 半自动指令瞄准线导弹（semiLOS，驾束 / 线导制导）。
 *
 * <p>飞行方式：每 tick 读取发射者当前视线，在视线前方取瞄准点（被方块挡住时取命中点，
 * 否则取束长上限），导弹向该点收敛，即“持续向玩家准星 / 视线方向飞行”。
 * 不锁定目标、不做干扰判定、不切换目标。
 *
 * <p>速度：5.2 m/s 起步，按型号加速度递增到 30 m/s 后匀速；转向受转弯率限制（12°/tick）。
 */
final class SemiLOSMissile extends Missile {

    /** 束长：视线无遮挡时向前取的瞄准点距离（格）。 */
    private static final double BEAM_LENGTH = 200.0D;
    /** 视线被方块挡住时瞄准点的下限，避免贴脸急转。 */
    private static final double MIN_BEAM_LENGTH = 8.0D;

    SemiLOSMissile(MissilePlugin plugin, MissileType type, Player owner, Location origin, Vector aim,
                   LivingEntity target, MissileType.TargetKind targetKind) {
        super(plugin, type, owner, origin, aim, target, targetKind);
    }

    /** 驾束制导：不锁定任何目标。 */
    @Override
    LivingEntity selectTarget(Location position) {
        return null;
    }

    /** 指令瞄准线不参与干扰判定。 */
    @Override
    void checkDecoy(Location position) {
        // semiLOS 无干扰机制
    }

    /** 每 tick 以发射者当前视线方向为飞行方向；速度 5.2 起步、到 30 后匀速。 */
    @Override
    void steer(Location position, LivingEntity target) {
        Vector desired = this.beamDirection(position);
        if (desired != null) {
            this.turnTowards(desired);
        }
        this.speed = Math.min(this.type().maxSpeed(), this.speed + this.type().acceleration());
        this.projectile().setVelocity(this.direction.clone().multiply(this.speed / 20.0D));
    }

    /** 线导弹只在弹体命中（小火球碰撞事件）时引爆，不做目标近炸。 */
    @Override
    boolean shouldDetonate(Location position, LivingEntity target) {
        return false;
    }

    /**
     * 取束轴方向：发射者视线前方 {@value #BEAM_LENGTH} 格处的瞄准点方向。
     *
     * @return 发射者不在同世界 / 离线时返回 {@code null}（保持原方向继续飞）
     */
    private Vector beamDirection(Location position) {
        Player shooter = this.owner();
        if (shooter == null || !shooter.isOnline() || shooter.isDead()
                || !shooter.getWorld().equals(position.getWorld())) {
            return null;
        }
        Location eye = shooter.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        RayTraceResult blockHit = shooter.getWorld().rayTraceBlocks(eye, look, BEAM_LENGTH,
                FluidCollisionMode.NEVER, true);
        double beam = BEAM_LENGTH;
        if (blockHit != null && blockHit.getHitPosition() != null) {
            beam = Math.max(MIN_BEAM_LENGTH, blockHit.getHitPosition().distance(eye.toVector()));
        }
        Vector aimPoint = eye.toVector().add(look.multiply(beam));
        Vector to = aimPoint.subtract(position.toVector());
        if (to.lengthSquared() <= 1.0E-6D) {
            return null;
        }
        return to.normalize();
    }
}
