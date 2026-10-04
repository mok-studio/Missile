package com.missile;

import org.bukkit.Material;
import org.bukkit.Particle;

/**
 * 四种导弹的静态性能参数与制导特性。
 *
 * <p>速度单位统一为 m/s（= 格/秒）。游戏内速度 = speed / 20 格每 tick。
 */
public enum MissileType {

    /**
     * 红外导弹：发射后自主追踪，不受玩家控制；可被烈焰棒干扰，
     * 15% 概率脱锁并转向追踪该烈焰棒持有者。纯追踪（无提前量）。
     */
    INFRARED("type.infrared", 5.0D, 25.0D, 1.0D, 6.0D,
            Material.BLAZE_ROD, 0.15D, 3.0F,
            true, true, 55.0D, 30.0D,
            Particle.FLAME, Particle.LARGE_SMOKE),

    /**
     * 半主动雷达寻的：发射后必须持续看向目标，由发射者的准星持续照射；
     * 准星附近多名玩家时自动切换最近者；铁粒干扰，5% 脱锁（脱锁后不再接受照射）。
     */
    SEMI_ACTIVE("type.semi-active", 5.2D, 30.0D, 1.2D, 4.0D,
            Material.IRON_NUGGET, 0.05D, 3.5F,
            false, false, 128.0D, 10.0D,
            Particle.SMALL_FLAME, Particle.CAMPFIRE_COSY_SMOKE),

    /**
     * 主动雷达寻的：发射后自主追踪，带提前量（比例导引）与惯性记忆；
     * 铁粒干扰，2.5% 脱锁，脱锁后可自主重新截获。
     */
    ACTIVE("type.active", 6.0D, 40.0D, 1.5D, 7.0D,
            Material.IRON_NUGGET, 0.025D, 4.5F,
            true, false, 70.0D, 40.0D,
            Particle.COPPER_FIRE_FLAME, Particle.WHITE_SMOKE),

    /**
     * 半自动指令瞄准线（semiLOS，驾束 / 线导）：发射后持续向发射者当前视线方向飞行，
     * 不锁定目标、不做干扰判定、不切换目标；由子类 SemiLOSMissile 实现。
     * 速度 5.2 m/s 起步，加速到 30 m/s 后匀速；转弯率 12°/tick 为可调取值（原需求未给定）。
     * decoyChance = 0 表示该型号不参与干扰判定。
     */
    SEMI_LOS("type.semi-los", 5.2D, 30.0D, 1.2D, 12.0D,
            Material.AIR, 0.0D, 3.5F,
            false, false, 0.0D, 0.0D,
            Particle.SOUL_FIRE_FLAME, Particle.SMOKE),

    /**
     * 超级主动弹（super_active）：制导与主动雷达完全一致（{@link #radarHoming()}：
     * 比例导引打提前量 + 脱锁惯性记忆 + 自主重截获），**并且会触发敌方 RWR**。
     * **免疫一切干扰**（decoyChance = 0，Missile#checkDecoy 会直接返回）。
     * 初速 8 m/s、最大 850 m/s、加速度 4 m/tick、战斗部威力 10；
     * 转弯率 30°/tick、自主截获 100 格 / 45° 锥。
     */
    SUPER_ACTIVE("super_active_name", 8.0D, 850.0D, 4.0D, 30.0D,
            Material.AIR, 0.0D, 10.0F,
            true, false, 100.0D, 45.0D,
            Particle.COPPER_FIRE_FLAME, Particle.WHITE_SMOKE);

    private final String displayNameKey;
    private final double initialSpeed;
    private final double maxSpeed;
    private final double acceleration;
    private final double turnRate;
    private final Material decoyMaterial;
    private final double decoyChance;
    private final float explosionPower;
    private final boolean autonomous;
    private final boolean retargetsDecoy;
    private final double acquireRange;
    private final double acquireCone;
    private final Particle flameParticle;
    private final Particle smokeParticle;

    MissileType(String displayNameKey, double initialSpeed, double maxSpeed, double acceleration,
                double turnRate, Material decoyMaterial, double decoyChance, float explosionPower,
                boolean autonomous, boolean retargetsDecoy, double acquireRange, double acquireCone,
                Particle flameParticle, Particle smokeParticle) {
        this.displayNameKey = displayNameKey;
        this.initialSpeed = initialSpeed;
        this.maxSpeed = maxSpeed;
        this.acceleration = acceleration;
        this.turnRate = turnRate;
        this.decoyMaterial = decoyMaterial;
        this.decoyChance = decoyChance;
        this.explosionPower = explosionPower;
        this.autonomous = autonomous;
        this.retargetsDecoy = retargetsDecoy;
        this.acquireRange = acquireRange;
        this.acquireCone = acquireCone;
        this.flameParticle = flameParticle;
        this.smokeParticle = smokeParticle;
    }

    /** 型号显示名，取自语言文件的 {@code type.*} 文案。 */
    public String displayName() {
        return Lang.get(this.displayNameKey);
    }

    /** 初速 m/s。 */
    public double initialSpeed() {
        return this.initialSpeed;
    }

    /** 最大速度 m/s。 */
    public double maxSpeed() {
        return this.maxSpeed;
    }

    /** 加速度 m/s 每 tick。 */
    public double acceleration() {
        return this.acceleration;
    }

    /** 每 tick 最大转弯角，单位度。 */
    public double turnRate() {
        return this.turnRate;
    }

    /** 干扰物材质。 */
    public Material decoyMaterial() {
        return this.decoyMaterial;
    }

    /** 每次干扰判定的脱锁概率。 */
    public double decoyChance() {
        return this.decoyChance;
    }

    /** 战斗部爆炸威力。 */
    public float explosionPower() {
        return this.explosionPower;
    }

    /** 是否自主寻的（无需发射者持续照射）。 */
    public boolean autonomous() {
        return this.autonomous;
    }

    /** 脱锁时是否直接转向追踪干扰源（红外特性）。 */
    public boolean retargetsDecoy() {
        return this.retargetsDecoy;
    }

    /** 是否为驾束（指令瞄准线）制导：不需要锁定目标，跟随发射者视线。 */
    public boolean beamRiding() {
        return this == SEMI_LOS;
    }

    /**
     * 是否为雷达制导（比例导引打提前量 + 脱锁惯性记忆 + 自主重截获），
     * 同时意味着它会辐射、能被敌方 RWR 察觉。
     *
     * <p>主动雷达（ACTIVE）与超级主动弹（SUPER_ACTIVE）共用这条路径：
     * {@code Missile} 的提前量 / 惯性记忆与 {@code RwrManager} 的威胁统计都以本方法为准。
     */
    public boolean radarHoming() {
        return this == ACTIVE || this == SUPER_ACTIVE;
    }

    /** 导引头作用距离：自主型为重新截获距离，半主动型为照射距离。 */
    public double acquireRange() {
        return this.acquireRange;
    }

    /** 导引头视场半角（度）：自主型为重新截获锥角，半主动型为准星照射锥角。 */
    public double acquireCone() {
        return this.acquireCone;
    }

    /** 尾焰粒子。 */
    public Particle flameParticle() {
        return this.flameParticle;
    }

    /** 尾烟粒子。 */
    public Particle smokeParticle() {
        return this.smokeParticle;
    }

    /**
     * 解析命令 / 别名参数。
     *
     * @return 无法识别时返回 {@code null}
     */
    public static MissileType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "ir", "infrared", "hongwai", "红外", "1" -> INFRARED;
            case "semi", "sarh", "semi_active", "semi-active", "banzhudong", "半主动", "2" -> SEMI_ACTIVE;
            case "active", "arh", "zhudong", "主动", "3" -> ACTIVE;
            case "semilos", "semi_los", "semi-los", "los", "line", "zhiling", "指令", "驾束", "线导", "4" ->
                    SEMI_LOS;
            case "super_active", "superactive", "super-active", "super", "chaojizhudong", "超级主动", "5" ->
                    SUPER_ACTIVE;
            default -> null;
        };
    }

    /** 锁定目标类型：玩家，或生物（非玩家 LivingEntity）。 */
    public enum TargetKind {

        /** 只锁定玩家（默认）。 */
        PLAYER,
        /** 只锁定生物：非玩家 LivingEntity，排除盔甲架等导弹外形实体。 */
        ENTITY;

        /**
         * 解析命令参数。
         *
         * @return 无法识别时返回 {@code null}
         */
        public static TargetKind parse(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (key) {
                case "player", "players", "p", "玩家", "1" -> PLAYER;
                case "entity", "entities", "mob", "mobs", "生物", "实体", "2" -> ENTITY;
                default -> null;
            };
        }
    }
}
