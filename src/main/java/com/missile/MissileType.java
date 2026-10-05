package com.missile;

import org.bukkit.Material;
import org.bukkit.Particle;

/**
 * 四种导弹的静态性能参数与制导特性。
 *
 * <p>速度单位统一为 m/s（= 格/秒）。游戏内速度 = speed / 20 格每 tick。
 *
 * <p><b>取值来源（本轮改造）</b>：所有访问器优先读 {@code config.yml} 的
 * {@code types.<configId>.<key>}（实现见 {@link Settings#typeNumber} 等），
 * 配置未写或值非法时才回落到本枚举构造函数里的默认值。
 * 也就是说枚举里的数字 = **出厂默认值**，服务器管理员改 config 即可调整性能，
 * 不需要改代码、不需要重编译。公式、判定与阈值一律未改。
 */
public enum MissileType {

    /**
     * 红外导弹：发射后自主追踪，不受玩家控制；可被**玩家丢出的烈焰粉**干扰，
     * 15% 概率脱锁并改锁那件掉落的烈焰粉（投掷者因此脱身）。
     * 手持烈焰粉**无效**（见 {@code decoy.require-thrown}）。纯追踪（无提前量）。
     */
    INFRARED("infrared", "type.infrared", 5.0D, 25.0D, 1.0D, 6.0D,
            Material.BLAZE_POWDER, 0.15D, 3.0F,
            true, true, 55.0D, 30.0D,
            Particle.FLAME, Particle.LARGE_SMOKE),

    /**
     * 半主动雷达寻的：发射后必须持续看向目标，由发射者的准星持续照射；
     * 准星附近多名玩家时自动切换最近者；铁粒干扰，5% 脱锁（脱锁后不再接受照射）。
     */
    SEMI_ACTIVE("semi-active", "type.semi-active", 5.2D, 30.0D, 1.2D, 4.0D,
            Material.IRON_NUGGET, 0.05D, 3.5F,
            false, false, 128.0D, 10.0D,
            Particle.SMALL_FLAME, Particle.CAMPFIRE_COSY_SMOKE),

    /**
     * 主动雷达寻的：发射后自主追踪，带提前量（比例导引）与惯性记忆；
     * 铁粒干扰，2.5% 脱锁，脱锁后可自主重新截获。
     */
    ACTIVE("active", "type.active", 6.0D, 40.0D, 1.5D, 7.0D,
            Material.IRON_NUGGET, 0.025D, 4.5F,
            true, false, 70.0D, 40.0D,
            Particle.COPPER_FIRE_FLAME, Particle.WHITE_SMOKE),

    /**
     * 半自动指令瞄准线（semiLOS，驾束 / 线导）：发射后持续向发射者当前视线方向飞行，
     * 不锁定目标、不做干扰判定、不切换目标；由子类 SemiLOSMissile 实现。
     * 速度 5.2 m/s 起步，加速到 30 m/s 后匀速；转弯率 12°/tick 为可调取值（原需求未给定）。
     * decoyChance = 0 表示该型号不参与干扰判定。
     */
    SEMI_LOS("semi-los", "type.semi-los", 5.2D, 30.0D, 1.2D, 12.0D,
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
    SUPER_ACTIVE("super-active", "super_active_name", 8.0D, 850.0D, 4.0D, 30.0D,
            Material.AIR, 0.0D, 10.0F,
            true, false, 100.0D, 45.0D,
            Particle.COPPER_FIRE_FLAME, Particle.WHITE_SMOKE);

    private final String configId;
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

    MissileType(String configId, String displayNameKey, double initialSpeed, double maxSpeed,
                double acceleration, double turnRate, Material decoyMaterial, double decoyChance,
                float explosionPower, boolean autonomous, boolean retargetsDecoy, double acquireRange,
                double acquireCone, Particle flameParticle, Particle smokeParticle) {
        this.configId = configId;
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

    /**
     * 本型号在 {@code config.yml} 里的配置键（{@code types.<configId>.<key>}）。
     *
     * <p>与枚举名解耦：枚举重命名不会悄悄改掉服务器已写好的配置路径。
     */
    public String configId() {
        return this.configId;
    }

    /** 型号显示名，取自语言文件的 {@code type.*} 文案。**不带颜色**（见 {@link #coloredName()}）。 */
    public String displayName() {
        return Lang.get(this.displayNameKey);
    }

    /**
     * 型号出厂默认颜色（决策 #34）：红外 {@code &c}、半主动 {@code &6}、
     * 主动与超级主动 {@code &a}、驾束 {@code &b}。
     */
    private String defaultColor() {
        return switch (this) {
            case INFRARED -> "&c";
            case SEMI_ACTIVE -> "&6";
            case ACTIVE, SUPER_ACTIVE -> "&a";
            case SEMI_LOS -> "&b";
        };
    }

    /** 型号颜色（{@code &} 色码）。配置键 {@code types.<id>.color}，出厂默认见 {@link #defaultColor()}。 */
    public String color() {
        return Settings.typeString(this, "color", this.defaultColor());
    }

    /**
     * 带颜色的型号名，供 {@code %msl%} 与所有 {@code {missile}} 占位使用（决策 #34）。
     *
     * <p>{@link #displayName()} 本身保持**无色**：控制台日志、离线断言、纯文本场合继续用它。
     */
    public String coloredName() {
        return Lang.colorize(this.color() + this.displayName());
    }

    /** 初速 m/s。配置键 {@code types.<id>.initial-speed}。 */
    public double initialSpeed() {
        return Math.max(0.0D, Settings.typeNumber(this, "initial-speed", this.initialSpeed));
    }

    /** 最大速度 m/s。配置键 {@code types.<id>.max-speed}。 */
    public double maxSpeed() {
        return Math.max(0.0D, Settings.typeNumber(this, "max-speed", this.maxSpeed));
    }

    /** 加速度 m/s 每 tick。配置键 {@code types.<id>.acceleration}。 */
    public double acceleration() {
        return Math.max(0.0D, Settings.typeNumber(this, "acceleration", this.acceleration));
    }

    /** 每 tick 最大转弯角，单位度。配置键 {@code types.<id>.turn-rate}。 */
    public double turnRate() {
        return Math.max(0.0D, Settings.typeNumber(this, "turn-rate", this.turnRate));
    }

    /** 干扰物材质。配置键 {@code types.<id>.decoy-material}（写成 AIR 且脱锁率为 0 = 免疫干扰）。 */
    public Material decoyMaterial() {
        return Settings.typeMaterial(this, "decoy-material", this.decoyMaterial);
    }

    /** 每次干扰判定的脱锁概率（0~1）。配置键 {@code types.<id>.decoy-chance}；{@code <= 0} 表示免疫干扰。 */
    public double decoyChance() {
        double value = Settings.typeNumber(this, "decoy-chance", this.decoyChance);
        // 概率语义：夹到 [0,1]。负数原本表示"免疫"，夹成 0 后语义一致（Missile#checkDecoy 判 <= 0）
        return Math.min(1.0D, Math.max(0.0D, value));
    }

    /** 战斗部爆炸威力。配置键 {@code types.<id>.explosion-power}。 */
    public float explosionPower() {
        return (float) Math.max(0.0D, Settings.typeNumber(this, "explosion-power", this.explosionPower));
    }

    /** 是否自主寻的（无需发射者持续照射）。配置键 {@code types.<id>.autonomous}。 */
    public boolean autonomous() {
        return Settings.typeFlag(this, "autonomous", this.autonomous);
    }

    /** 脱锁时是否直接转向追踪干扰源（红外特性）。配置键 {@code types.<id>.retargets-decoy}。 */
    public boolean retargetsDecoy() {
        return Settings.typeFlag(this, "retargets-decoy", this.retargetsDecoy);
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

    /** 导引头作用距离：自主型为重新截获距离，半主动型为照射距离。配置键 {@code types.<id>.acquire-range}。 */
    public double acquireRange() {
        return Math.max(0.0D, Settings.typeNumber(this, "acquire-range", this.acquireRange));
    }

    /** 导引头视场半角（度）：自主型为重新截获锥角，半主动型为准星照射锥角。配置键 {@code types.<id>.acquire-cone}。 */
    public double acquireCone() {
        double value = Settings.typeNumber(this, "acquire-cone", this.acquireCone);
        // 锥角夹到 [0,180]：超过 180° 与 180° 等价，负数无意义
        return Math.min(180.0D, Math.max(0.0D, value));
    }

    /** 尾焰粒子。配置键 {@code types.<id>.flame-particle}。 */
    public Particle flameParticle() {
        return Settings.typeParticle(this, "flame-particle", this.flameParticle);
    }

    /** 尾烟粒子。配置键 {@code types.<id>.smoke-particle}。 */
    public Particle smokeParticle() {
        return Settings.typeParticle(this, "smoke-particle", this.smokeParticle);
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

    /** 锁定目标类型：玩家 / 生物 / 不限。 */
    public enum TargetKind {

        /** 只锁定玩家（默认）。 */
        PLAYER,
        /** 只锁定生物：非玩家 LivingEntity，排除盔甲架等导弹外形实体。 */
        ENTITY,
        /**
         * 不限类型：玩家与生物都算合法候选。
         *
         * <p>给 {@code /msl ir ... usefilter} 用："能锁谁"完全由筛选白名单决定
         * （白名单里可以同时有玩家类与实体类），而"怎么锁"（128 格 / 10° 锥 / 视线可达）
         * 沿用原规则不变。
         */
        ANY;

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
                case "any", "all", "both", "unlimited", "任意", "不限", "3" -> ANY;
                default -> null;
            };
        }
    }
}
