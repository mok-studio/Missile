package com.missile;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 视线与准星目标选择。
 *
 * <p>1.21.11 中 {@code org.bukkit.util.EntityHitResult} 已被移除，射线命中实体统一通过
 * {@link RayTraceResult#getHitEntity()} 读取，语义与旧版 EntityHitResult 一致。
 *
 * <p>选择规则（对应“视线内目标则锁定” + “准星附近多个目标时自动切换最近者”）：
 * 先读取准星射线命中的实体（EntityHitResult 语义），再收集准星锥角内的实体，
 * 合并后取距离最近者；锁定类型由 {@link MissileType.TargetKind} 决定（玩家 / 生物）。
 */
public final class TargetSelector {

    /** 射线检测的膨胀半径，让准星擦边也能命中。 */
    private static final double RAY_SIZE = 0.35D;

    private TargetSelector() {
    }

    /** 目标是否可作为导弹锁定对象（玩家类型，保留旧签名）。 */
    public static boolean isValidTarget(Player shooter, Player candidate) {
        return isValidTarget(shooter, candidate, MissileType.TargetKind.PLAYER);
    }

    /** 目标是否符合本次锁定的类型要求，并与发射者同世界、非自身。 */
    public static boolean isValidTarget(Player shooter, LivingEntity candidate, MissileType.TargetKind kind) {
        if (candidate == null || !candidate.isValid() || candidate.isDead()) {
            return false;
        }
        if (!isValidKind(candidate, kind)) {
            return false;
        }
        if (!TargetFilter.canTarget(shooter.getUniqueId(), candidate)) {
            return false;   // 该玩家的目标筛选（useAll = false 时生效）
        }
        if (candidate.getUniqueId().equals(shooter.getUniqueId())) {
            return false;
        }
        return candidate.getWorld().equals(shooter.getWorld());
    }

    /** 目标实体本身是否属于要求的类型（玩家 / 生物）。 */
    public static boolean isValidKind(LivingEntity candidate, MissileType.TargetKind kind) {
        if (candidate == null || !candidate.isValid() || candidate.isDead()) {
            return false;
        }
        // 排除导弹自身外形（marker 盔甲架）一类非生物目标
        if (candidate instanceof ArmorStand) {
            return false;
        }
        if (kind == MissileType.TargetKind.ENTITY) {
            return !(candidate instanceof Player);
        }
        if (!(candidate instanceof Player player)) {
            return false;
        }
        return player.isOnline() && player.getGameMode() != GameMode.SPECTATOR;
    }

    /**
     * 读取发射者视线命中的玩家（EntityHitResult 判定，保留旧签名）。
     *
     * @return 命中的玩家，未命中玩家时返回 {@code null}
     */
    public static Player rayTarget(Player shooter, double range) {
        LivingEntity hit = rayTarget(shooter, range, MissileType.TargetKind.PLAYER);
        return hit instanceof Player player ? player : null;
    }

    /**
     * 读取发射者视线命中的目标实体。
     *
     * <p>1.21.11 已移除 {@code org.bukkit.util.EntityHitResult}，射线命中实体统一通过
     * {@link RayTraceResult#getHitEntity()} 读取，语义等价。
     *
     * @return 命中的目标，未命中时返回 {@code null}
     */
    public static LivingEntity rayTarget(Player shooter, double range, MissileType.TargetKind kind) {
        Location eye = shooter.getEyeLocation();
        RayTraceResult result = shooter.getWorld().rayTraceEntities(
                eye, eye.getDirection(), range, RAY_SIZE,
                entity -> entity instanceof LivingEntity living && isValidTarget(shooter, living, kind));
        if (result == null || !(result.getHitEntity() instanceof LivingEntity hit)) {
            return null;
        }
        return hit;
    }

    /**
     * 选择锁定目标（玩家类型，保留旧签名）。
     *
     * @param range   最大锁定距离
     * @param coneDeg 准星锥角半角（度）
     */
    public static Player select(Player shooter, double range, double coneDeg) {
        LivingEntity selected = select(shooter, range, coneDeg, MissileType.TargetKind.PLAYER);
        return selected instanceof Player player ? player : null;
    }

    /**
     * 选择锁定目标：准星射线命中的目标 + 准星锥角内的目标，取距离最近者。
     *
     * @param range   最大锁定距离
     * @param coneDeg 准星锥角半角（度）
     * @param kind    锁定目标类型（玩家 / 生物）
     */
    public static LivingEntity select(Player shooter, double range, double coneDeg, MissileType.TargetKind kind) {
        Location eye = shooter.getEyeLocation();
        Vector eyeVector = eye.toVector();
        Vector look = eye.getDirection().normalize();

        List<LivingEntity> candidates = new ArrayList<>();
        LivingEntity direct = rayTarget(shooter, range, kind);
        if (direct != null) {
            candidates.add(direct);
        }
        for (Entity entity : shooter.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity candidate) || !isValidTarget(shooter, candidate, kind)) {
                continue;
            }
            if (candidates.contains(candidate)) {
                continue;
            }
            Vector to = candidate.getEyeLocation().toVector().subtract(eyeVector);
            double distance = to.length();
            if (distance < 0.01D || distance > range) {
                continue;
            }
            if (Math.toDegrees(look.angle(to)) > coneDeg) {
                continue;
            }
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            if (!shooter.hasLineOfSight(candidate)) {
                continue;
            }
            double distance = candidate.getLocation().distanceSquared(eye);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 导引头自主截获（玩家类型，保留旧签名）。
     *
     * @param origin  导引头位置
     * @param forward 导引头朝向（单位向量）
     * @return 未截获到目标时返回 {@code null}
     */
    public static Player acquireInCone(Location origin, Vector forward, double range, double coneDeg,
                                       java.util.UUID ignored, Entity owner) {
        LivingEntity acquired = acquireInCone(origin, forward, range, coneDeg, ignored, owner,
                MissileType.TargetKind.PLAYER);
        return acquired instanceof Player player ? player : null;
    }

    /**
     * 导引头自主截获：在导弹前方视场内取最近目标（红外 / 主动雷达用）。
     *
     * @param origin  导引头位置
     * @param forward 导引头朝向（单位向量）
     * @param kind    锁定目标类型（玩家 / 生物）
     * @return 未截获到目标时返回 {@code null}
     */
    public static LivingEntity acquireInCone(Location origin, Vector forward, double range, double coneDeg,
                                             java.util.UUID ignored, Entity owner, MissileType.TargetKind kind) {
        if (coneDeg <= 0.0D || range <= 0.0D) {
            return null;
        }
        Vector originVector = origin.toVector();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : origin.getWorld().getNearbyEntities(origin, range, range, range)) {
            if (!(entity instanceof LivingEntity candidate) || !isValidKind(candidate, kind)) {
                continue;
            }
            if (owner != null && !TargetFilter.canTarget(owner.getUniqueId(), candidate)) {
                continue;   // 弹上重新截获同样遵守发射者的筛选配置
            }
            if (ignored != null && candidate.getUniqueId().equals(ignored)) {
                continue;
            }
            if (owner != null && candidate.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            Vector to = candidate.getEyeLocation().toVector().subtract(originVector);
            double distance = to.length();
            if (distance < 0.01D || distance > range) {
                continue;
            }
            if (Math.toDegrees(forward.angle(to)) > coneDeg) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
