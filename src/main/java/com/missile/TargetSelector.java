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
 * 合并后取距离最近者；锁定类型由 {@link MissileType.TargetKind} 决定（玩家 / 生物 / 不限）。
 *
 * <p><b>白名单通道</b>：带 {@code whitelistOnly} 参数的重载用于 {@code /msl ir ... usefilter} ——
 * 候选目标必须是发射者筛选白名单里的（{@link TargetFilter#isWhitelisted}），
 * 其余几何规则（距离 / 锥角 / 视线可达）与非白名单通道完全一致。
 *
 * <p><b>非生物实体（需求 3.9）</b>：默认基线只认“友好 / 中立 / 敌对生物”，
 * 载具、末地水晶、盔甲架等**不在**候选里；但白名单里**显式写了它的实体 ID**
 * （如 {@code /msl filter entity boat}）就无视该限制（{@link TargetFilter#explicitlyWhitelisted}）。
 * 为此本类提供 {@code ...Entity(...)} 后缀的 {@link Entity} 版方法；
 * 旧的 {@link LivingEntity} 版方法保留为薄包装（只取其中生物），因此调用方可以逐层迁移。
 */
public final class TargetSelector {

    /** 射线检测的膨胀半径，让准星擦边也能命中。 */
    private static final double RAY_SIZE = 0.35D;

    private TargetSelector() {
    }

    /**
     * 目标身上用于瞄准 / 测距的点。
     *
     * <p>生物用**眼睛位置**（与改造前完全一致，保证手感不变）；非生物没有眼睛，
     * 用**碰撞箱中心**（载具 / 末地水晶等）。
     */
    static Location aimPoint(Entity entity) {
        if (entity instanceof LivingEntity living) {
            return living.getEyeLocation();
        }
        return entity.getBoundingBox().getCenter().toLocation(entity.getWorld());
    }

    /** 目标是否可作为导弹锁定对象（玩家类型，保留旧签名）。 */
    public static boolean isValidTarget(Player shooter, Player candidate) {
        return isValidTarget(shooter, candidate, MissileType.TargetKind.PLAYER);
    }

    /** 目标是否符合本次锁定的类型要求，并与发射者同世界、非自身。 */
    public static boolean isValidTarget(Player shooter, LivingEntity candidate, MissileType.TargetKind kind) {
        return isValidTarget(shooter, candidate, kind, false);
    }

    /**
     * 目标是否符合本次锁定的类型要求，并与发射者同世界、非自身。
     *
     * @param whitelistOnly {@code true} = 只接受该玩家筛选白名单内的目标（无视筛选模式开关），
     *                      用于 {@code usefilter}；{@code false} = 走原来的筛选模式判定
     */
    public static boolean isValidTarget(Player shooter, LivingEntity candidate, MissileType.TargetKind kind,
                                        boolean whitelistOnly) {
        return isValidTargetEntity(shooter, candidate, kind, whitelistOnly);
    }

    /**
     * 当前**实际生效的候选类别**。
     *
     * <p>规则（用户 2026-10-05 反馈后定稿）：**筛选模式打开**（或 {@code usefilter} 打开）时，
     * "能锁哪一类"以**白名单启用的类别**为准；筛选关闭时才由导弹自己的 {@code TargetKind} 决定。
     *
     * <p>反例（改造前会发生）：{@code /msl filter entity add phantom} 会把"玩家"挡在白名单外、
     * 而 IR 默认模式又把非玩家生物挡在类型外 → 交集为空、连幻翼都锁不上。
     *
     * @return {@code NONE} = 筛选未生效，按 {@code TargetKind} 走
     */
    static TargetFilter.TargetClasses activeClasses(Player shooter, boolean whitelistOnly) {
        if (shooter == null) {
            return TargetFilter.TargetClasses.NONE;
        }
        if (whitelistOnly || TargetFilter.isEnabled(shooter.getUniqueId())) {
            return TargetFilter.targetClasses(shooter.getUniqueId());
        }
        return TargetFilter.TargetClasses.NONE;
    }

    /**
     * 把"白名单类别 + {@code TargetKind}"折算成一次判定用的{@code TargetKind}。
     *
     * <p>{@code BOTH} → {@code ANY}；{@code NONE}（筛选未生效）→ 原样返回导弹自己的 kind。
     */
    static MissileType.TargetKind effectiveKind(Player shooter, MissileType.TargetKind kind, boolean whitelistOnly) {
        return switch (activeClasses(shooter, whitelistOnly)) {
            case PLAYER -> MissileType.TargetKind.PLAYER;
            case ENTITY -> MissileType.TargetKind.ENTITY;
            case BOTH -> MissileType.TargetKind.ANY;
            case NONE -> kind;
        };
    }

    /**
     * 目标（任意实体）是否符合本次锁定要求。
     *
     * <p>生物走"类别 + 筛选 / 白名单"两关（类别由 {@link #effectiveKind} 折算）；
     * **非生物只能靠"白名单显式列出的实体 ID"放行**。
     */
    public static boolean isValidTargetEntity(Player shooter, Entity candidate, MissileType.TargetKind kind,
                                              boolean whitelistOnly) {
        if (shooter == null || candidate == null || !candidate.isValid() || candidate.isDead()) {
            return false;
        }
        if (candidate.getUniqueId().equals(shooter.getUniqueId())) {
            return false;
        }
        if (!candidate.getWorld().equals(shooter.getWorld())) {
            return false;
        }
        MissileType.TargetKind effective = effectiveKind(shooter, kind, whitelistOnly);
        boolean notABaselineCreature = !(candidate instanceof LivingEntity) || candidate instanceof ArmorStand;
        if (!notABaselineCreature && isValidKind((LivingEntity) candidate, effective)) {
            return whitelistOnly
                    ? TargetFilter.isWhitelisted(shooter.getUniqueId(), (LivingEntity) candidate)
                    : TargetFilter.canTarget(shooter.getUniqueId(), (LivingEntity) candidate);
        }
        if (!notABaselineCreature) {
            return false;                      // 是生物但不符合本次生效的类别
        }
        // 非生物（载具 / 末地水晶 / 盔甲架 / 展示实体…）：默认不可锁，显式白名单才放行
        return TargetFilter.explicitlyWhitelisted(shooter.getUniqueId(), candidate, whitelistOnly);
    }

    /** 目标实体本身是否属于要求的类型（玩家 / 生物 / 不限）。 */
    public static boolean isValidKind(LivingEntity candidate, MissileType.TargetKind kind) {
        if (candidate == null || !candidate.isValid() || candidate.isDead()) {
            return false;
        }
        // 盔甲架等"玩家放置的实体方块/模型"不算生物基线；白名单显式列出时由
        // isValidTargetEntity 的 explicitlyWhitelisted 分支放行
        if (candidate instanceof ArmorStand) {
            return false;
        }
        if (kind == MissileType.TargetKind.ENTITY) {
            return !(candidate instanceof Player);
        }
        if (!(candidate instanceof Player player)) {
            // ANY：非玩家生物同样合法；PLAYER：不合法
            return kind == MissileType.TargetKind.ANY;
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
        return rayTarget(shooter, range, kind, false);
    }

    /** 读取发射者视线命中的目标实体（{@code whitelistOnly} 见 {@link #isValidTarget}）。 */
    public static LivingEntity rayTarget(Player shooter, double range, MissileType.TargetKind kind,
                                         boolean whitelistOnly) {
        Entity hit = rayTargetEntity(shooter, range, kind, whitelistOnly);
        return hit instanceof LivingEntity living ? living : null;
    }

    /** 读取发射者视线命中的**任意**实体（含显式白名单放行的非生物）。 */
    public static Entity rayTargetEntity(Player shooter, double range, MissileType.TargetKind kind,
                                         boolean whitelistOnly) {
        return rayTargetEntity(shooter, range, kind, whitelistOnly, null);
    }

    /**
     * 同上，但可传入 SA 快照：{@code saProfile != null} 时判定完全走 SA 口径
     * （**不看全局 filter**，§1.2 / 决策 #29）。
     */
    public static Entity rayTargetEntity(Player shooter, double range, MissileType.TargetKind kind,
                                         boolean whitelistOnly, SaProfile saProfile) {
        Location eye = shooter.getEyeLocation();
        RayTraceResult result = shooter.getWorld().rayTraceEntities(
                eye, eye.getDirection(), range, RAY_SIZE,
                entity -> accepts(shooter, entity, kind, whitelistOnly, saProfile));
        return result == null ? null : result.getHitEntity();
    }

    /**
     * 统一入口：{@code saProfile} 非空走 SA 判定，否则走原有"类型 + 筛选"判定。
     *
     * <p>把两条通道收在一个方法里，是为了让 {@code selectEntity} / {@code acquireEntityInCone}
     * 只保留一份扫描与几何代码（距离 / 锥角 / 视线规则对两者完全相同）。
     */
    private static boolean accepts(Player shooter, Entity candidate, MissileType.TargetKind kind,
                                   boolean whitelistOnly, SaProfile saProfile) {
        return saProfile != null
                ? isValidSaTarget(shooter, candidate, saProfile)
                : isValidTargetEntity(shooter, candidate, kind, whitelistOnly);
    }

    /**
     * SA（超级主动弹）专用判定：只看 {@link SaProfile} 快照，**完全不看全局 filter**。
     *
     * <p>规则（§1.2 + 决策 #27~#30）：
     * <ul>
     *   <li>永远锁不到自己（{@code candidate != shooter} 硬约束）；</li>
     *   <li>{@code ANY}（default）= 玩家与非玩家生物都行，取最近的；**不做玩家优先两段式**；</li>
     *   <li>{@code ENTITY} = 非玩家生物；safilter 非空时只认列出的 ID；</li>
     *   <li>{@code PLAYER} = 玩家（除自己）；safilter 非空时只认列出的玩家；</li>
     *   <li>非生物（载具 / 末地水晶 / 盔甲架…）在 ANY / ENTITY 下**必须被 safilter 显式列出**才放行
     *       （与决策 #22 同口径），瞄准点自然是碰撞箱中心。</li>
     * </ul>
     */
    public static boolean isValidSaTarget(Player shooter, Entity candidate, SaProfile saProfile) {
        if (shooter == null || candidate == null || saProfile == null) {
            return false;
        }
        if (!candidate.isValid() || candidate.isDead()) {
            return false;
        }
        if (candidate.getUniqueId().equals(shooter.getUniqueId())) {
            return false;                      // 锁不到自己
        }
        if (!candidate.getWorld().equals(shooter.getWorld())) {
            return false;
        }
        String id = candidate instanceof Player ? "" : TargetFilter.idOf(candidate);
        if (candidate instanceof Player player) {
            return saProfile.allowsPlayers() && saProfile.allowsPlayer(player);
        }
        boolean baselineCreature = candidate instanceof LivingEntity && !(candidate instanceof ArmorStand);
        if (baselineCreature) {
            return saProfile.allowsCreatures() && saProfile.allowsEntityId(id);
        }
        return saProfile.allowsNonLiving(id);
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
        return select(shooter, range, coneDeg, kind, false);
    }

    /**
     * 选择锁定目标（{@code whitelistOnly} 见 {@link #isValidTarget}）。
     *
     * <p>{@code whitelistOnly} 只改变**候选目标集合**（必须命中白名单），
     * 几何规则（{@code range} / {@code coneDeg} / 视线可达）完全不变。
     */
    public static LivingEntity select(Player shooter, double range, double coneDeg, MissileType.TargetKind kind,
                                      boolean whitelistOnly) {
        Entity selected = selectEntity(shooter, range, coneDeg, kind, whitelistOnly);
        return selected instanceof LivingEntity living ? living : null;
    }

    /**
     * 选择锁定目标（任意实体版）。
     *
     * @param whitelistOnly {@code true} = 候选只看白名单；此时非生物实体同样按白名单放行
     */
    public static Entity selectEntity(Player shooter, double range, double coneDeg, MissileType.TargetKind kind,
                                      boolean whitelistOnly) {
        return selectEntity(shooter, range, coneDeg, kind, whitelistOnly, null);
    }

    /**
     * 同上，但可传入 SA 快照：非空时判定完全走 SA 口径（不看全局 filter）。
     * 距离 / 锥角 / 视线可达等几何规则两者完全一致。
     */
    public static Entity selectEntity(Player shooter, double range, double coneDeg, MissileType.TargetKind kind,
                                      boolean whitelistOnly, SaProfile saProfile) {
        Location eye = shooter.getEyeLocation();
        Vector eyeVector = eye.toVector();
        Vector look = eye.getDirection().normalize();

        List<Entity> candidates = new ArrayList<>();
        Entity direct = rayTargetEntity(shooter, range, kind, whitelistOnly, saProfile);
        if (direct != null) {
            candidates.add(direct);
        }
        for (Entity candidate : shooter.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!accepts(shooter, candidate, kind, whitelistOnly, saProfile)) {
                continue;
            }
            if (candidates.contains(candidate)) {
                continue;
            }
            Vector to = aimPoint(candidate).toVector().subtract(eyeVector);
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

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : candidates) {
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
        return acquireInCone(origin, forward, range, coneDeg, ignored, owner, kind, false);
    }

    /**
     * 导引头自主截获：在导弹前方视场内取最近目标（红外 / 主动雷达用）。
     *
     * @param origin        导引头位置
     * @param forward       导引头朝向（单位向量）
     * @param kind          锁定目标类型（玩家 / 生物 / 不限）
     * @param whitelistOnly {@code true} = 弹上重新截获同样只认筛选白名单（{@code usefilter} 用）
     * @return 未截获到目标时返回 {@code null}
     */
    public static LivingEntity acquireInCone(Location origin, Vector forward, double range, double coneDeg,
                                             java.util.UUID ignored, Entity owner, MissileType.TargetKind kind,
                                             boolean whitelistOnly) {
        Entity acquired = acquireEntityInCone(origin, forward, range, coneDeg, ignored, owner, kind, whitelistOnly);
        return acquired instanceof LivingEntity living ? living : null;
    }

    /**
     * 导引头自主截获（任意实体版）：非生物实体同样只在"白名单显式列出"时才截获。
     */
    public static Entity acquireEntityInCone(Location origin, Vector forward, double range, double coneDeg,
                                             java.util.UUID ignored, Entity owner, MissileType.TargetKind kind,
                                             boolean whitelistOnly) {
        return acquireEntityInCone(origin, forward, range, coneDeg, ignored, owner, kind, whitelistOnly, null);
    }

    /**
     * 同上，但可传入 SA 快照：非空时弹上重新截获也走 SA 口径（不看全局 filter）。
     */
    public static Entity acquireEntityInCone(Location origin, Vector forward, double range, double coneDeg,
                                             java.util.UUID ignored, Entity owner, MissileType.TargetKind kind,
                                             boolean whitelistOnly, SaProfile saProfile) {
        if (coneDeg <= 0.0D || range <= 0.0D) {
            return null;
        }
        Vector originVector = origin.toVector();
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : origin.getWorld().getNearbyEntities(origin, range, range, range)) {
            if (owner != null) {
                if (!accepts(owner instanceof Player player ? player : null,
                        candidate, kind, whitelistOnly, saProfile)) {
                    continue;   // 弹上重新截获同样遵守发射者的锁定配置
                }
            } else if (!(candidate instanceof LivingEntity living) || !isValidKind(living, kind)) {
                continue;       // 没有发射者（理论上不会发生）时退回只认生物的基线
            }
            if (ignored != null && candidate.getUniqueId().equals(ignored)) {
                continue;
            }
            if (owner != null && candidate.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            Vector to = aimPoint(candidate).toVector().subtract(originVector);
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
