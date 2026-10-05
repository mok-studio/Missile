package com.missile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * config.yml 的单一入口，支持运行时热重载（{@code /msl reload} 再次调用 {@link #load} 即可）。
 *
 * <p>设计约定：
 * <ul>
 *   <li>文案默认仍来自 {@code lang/<language>.yml}；{@code config.yml} 的 {@code messages:}
 *       板块**按 key 覆盖**（只写要改的条目，其余继续用语言文件）。</li>
 *   <li>所有可人为调节的数值都在本类暴露，代码里不再写死。</li>
 *   <li>导弹型号参数放在 {@code types.<型号>.<键>} 板块，由 {@link #typeNumber} 等读取；
 *       配置缺失或值非法时，{@link MissileType} 枚举里的数值作为出厂默认兜底。</li>
 *   <li>{@link #load(MissilePlugin)} 只重新读取文件、不产生副作用，可安全重复调用。</li>
 * </ul>
 *
 * <p>版本号不在本文件也不在 config.yml，见 {@link MissilePlugin#VERSION}。
 */
public final class Settings {

    /** 导弹参数配置文件名（放在插件数据目录 plugins/Missile/ 下）。 */
    public static final String MISSILE_CONFIG_FILE = "msl_config.yml";

    private static String language = Lang.DEFAULT_LOCALE;
    private static boolean breakBlocks = true;
    private static Map<String, String> messageOverrides = Collections.emptyMap();
    private static String storageType = "JSON";
    private static int autoSaveSeconds = 5;
    private static String jsonFile = "data/filters.json";
    private static String mysqlHost = "127.0.0.1";
    private static int mysqlPort = 3306;
    private static String mysqlDatabase = "minecraft";
    private static String mysqlUser = "root";
    private static String mysqlPassword = "";
    private static String mysqlTable = "missile_filters";
    private static boolean mysqlUseSsl;
    private static boolean persistGlobalSwitch = true;
    private static boolean rwrEnabled = true;
    private static double rwrAlertRange = 256.0D;
    private static boolean rwrSoundEnabled = true;
    private static int rwrTrackInterval = 4;
    private static int rwrMissileInterval = 2;
    private static float rwrTrackVolume = 0.7F;
    private static float rwrTrackHighPitch = 1.8F;
    private static float rwrTrackLowPitch = 0.7F;
    private static float rwrMissileVolume = 1.0F;
    private static float rwrMissilePitch = 1.6F;
    private static boolean mawsEnabled = true;
    private static double mawsRange = 40.0D;
    private static double mawsClosingSpeed = 10.0D;
    private static boolean mawsIncludeOwn;
    private static boolean mawsSoundEnabled;
    private static int mawsSoundInterval = 2;
    private static double lockRange = 128.0D;
    private static double lockCone = 10.0D;
    private static int seekerRefreshTicks = 2;
    private static String seekerLockPadding = " &f&k1";
    private static double muzzleOffset = 1.2D;
    private static int maxActiveMissiles = 64;
    private static int maxLifeTicks = 600;
    private static double proximityFuse = 3.0D;
    private static int inertialMemoryTicks = 100;
    private static int decoyIntervalTicks = 20;
    private static double decoySearchRange = 48.0D;
    private static boolean decoyRequireThrown = true;
    private static int decoyTtlTicks = 60;
    private static int decoyLockoutTicks = 100;
    private static int reacquireDelayTicks = 60;
    private static double beamLength = 200.0D;
    private static double beamMinLength = 8.0D;
    private static double particleViewerRange = 96.0D;
    private static int exhaustFlameCount = 4;
    private static double exhaustFlameSpread = 0.06D;
    private static double exhaustFlameExtra;
    private static int exhaustInnerFlameCount = 2;
    private static double exhaustInnerFlameSpread = 0.05D;
    private static double exhaustInnerFlameExtra;
    private static int exhaustSmokeCount = 3;
    private static double exhaustSmokeSpread = 0.14D;
    private static double exhaustSmokeExtra = 0.004D;
    private static int exhaustCloudCount = 2;
    private static double exhaustCloudSpread = 0.16D;
    private static double exhaustCloudExtra = 0.002D;
    private static double exhaustBackOffset = 0.55D;
    private static double exhaustSmokeBackMultiplier = 3.0D;

    /** 当前 config.yml 句柄（通用项）：{@link #load} 每次重新获取（Bukkit 的 reloadConfig 会换掉对象）。 */
    private static FileConfiguration config;
    /** 当前 msl_config.yml 句柄（导弹参数）：{@link #load} 每次重新读取。 */
    private static FileConfiguration missileConfig;
    /** 型号枚举参数（Material / Particle）的解析缓存；{@link #load} 时清空。 */
    private static final Map<String, Object> typeEnumCache = new HashMap<>();
    /** 「配置写了但无法识别」的哨兵：与缓存未命中（null）区分开，保证坏值只告警一次。 */
    private static final Object INVALID = new Object();
    private static Logger logger;

    private Settings() {
    }

    /**
     * 读取（或重读）两份配置文件。主类在 onEnable 与 {@code /msl reload} 时调用。
     *
     * <ul>
     *   <li>{@code config.yml} —— **通用项**：语言、文案覆盖、目标筛选存储</li>
     *   <li>{@code msl_config.yml} —— **导弹参数**：全服开关、发射与导引头、飞行、驾束、
     *       粒子、模型、战斗部、型号性能、RWR、MAWS</li>
     * </ul>
     */
    public static void load(MissilePlugin plugin) {
        plugin.reloadConfig();
        config = plugin.getConfig();
        logger = plugin.getLogger();
        typeEnumCache.clear();

        // ---------------- 通用项：config.yml ----------------
        language = config.getString("language", Lang.DEFAULT_LOCALE);
        messageOverrides = readMessages(config);
        storageType = config.getString("filter.storage.type", "JSON");
        autoSaveSeconds = Math.max(1, config.getInt("filter.storage.auto-save-interval-seconds", 5));
        jsonFile = config.getString("filter.storage.json-file", "data/filters.json");
        mysqlHost = config.getString("filter.storage.mysql.host", "127.0.0.1");
        mysqlPort = config.getInt("filter.storage.mysql.port", 3306);
        mysqlDatabase = config.getString("filter.storage.mysql.database", "minecraft");
        mysqlUser = config.getString("filter.storage.mysql.user", "root");
        mysqlPassword = config.getString("filter.storage.mysql.password", "");
        mysqlTable = config.getString("filter.storage.mysql.table", "missile_filters");
        mysqlUseSsl = config.getBoolean("filter.storage.mysql.use-ssl", false);

        // ---------------- 导弹参数：msl_config.yml ----------------
        missileConfig = loadMissileConfig(plugin);
        loadMissile(missileConfig);
        loadRwr(missileConfig);
    }

    /**
     * 读取 {@code msl_config.yml}：文件不存在时先从 jar 释放一份默认配置。
     *
     * <p>不复用 Bukkit 的 {@code getConfig()}（它只认 {@code config.yml}），
     * 所以这里自己管文件；{@code /msl reload} 会重新读一遍。
     */
    private static FileConfiguration loadMissileConfig(MissilePlugin plugin) {
        File file = new File(plugin.getDataFolder(), MISSILE_CONFIG_FILE);
        if (!file.exists()) {
            try {
                plugin.saveResource(MISSILE_CONFIG_FILE, false);
            } catch (IllegalArgumentException missingResource) {
                // jar 里没有这个资源（例如打包漏了）：继续用内置默认值，只在控制台提醒
                if (logger != null) {
                    logger.warning("jar 内缺少 " + MISSILE_CONFIG_FILE + "，本次运行全部使用内置默认参数");
                }
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * 读取 {@code msl_config.yml} 里"通用导弹参数"的各个板块
     * （除型号 {@code types:} 与 RWR 之外的部分）。
     *
     * <p>独立成方法的原因同 {@link #loadRwr}：它不依赖插件实例，可以脱离服务端
     * 用一份 {@link FileConfiguration} 直接验证配置键名与夹取。
     *
     * @param mc msl_config.yml 的内容
     */
    static void loadMissile(FileConfiguration mc) {
        // 全服开关：值本身交给 MissilePlugin（运行期可切），这里只读"是否写回配置"的策略
        persistGlobalSwitch = mc.getBoolean("missile.persist-global-switch", true);

        // 战斗部
        breakBlocks = mc.getBoolean("explosion.break-blocks", true);

        // 发射与导引头
        lockRange = Math.max(1.0D, mc.getDouble("launcher.lock-range", 128.0D));
        lockCone = clampDouble(mc.getDouble("launcher.lock-cone", 10.0D), 0.0D, 180.0D);
        seekerRefreshTicks = clampInt(mc.getInt("launcher.refresh-interval-ticks", 2), 1, 200);
        seekerLockPadding = readLockPadding(mc);
        muzzleOffset = Math.max(0.0D, mc.getDouble("launcher.muzzle-offset", 1.2D));
        maxActiveMissiles = clampInt(mc.getInt("launcher.max-active", 64), 1, 4096);

        // 弹体飞行
        maxLifeTicks = clampInt(mc.getInt("flight.max-life-ticks", 600), 1, 1000000);
        proximityFuse = Math.max(0.0D, mc.getDouble("flight.proximity-fuse", 3.0D));
        inertialMemoryTicks = clampInt(mc.getInt("flight.inertial-memory-ticks", 100), 0, 1000000);
        // 干扰（诱饵）：新配置键在 decoy: 段；旧版写在 flight.decoy-* 里的值仍会被读取（向后兼容）
        decoyIntervalTicks = clampInt(mc.getInt("decoy.interval-ticks",
                mc.getInt("flight.decoy-interval-ticks", 20)), 1, 1000000);
        decoySearchRange = Math.max(0.0D, mc.getDouble("decoy.search-range",
                mc.getDouble("flight.decoy-search-range", 48.0D)));
        decoyRequireThrown = mc.getBoolean("decoy.require-thrown", true);
        decoyTtlTicks = clampInt(mc.getInt("decoy.ttl-ticks", 60), 1, 1000000);
        decoyLockoutTicks = clampInt(mc.getInt("decoy.lockout-ticks", 100), 0, 1000000);
        reacquireDelayTicks = clampInt(mc.getInt("flight.reacquire-delay-ticks", 60), 0, 1000000);

        // 驾束
        beamLength = Math.max(1.0D, mc.getDouble("beam.length", 200.0D));
        beamMinLength = Math.max(0.0D, mc.getDouble("beam.min-length", 8.0D));

        // 粒子
        particleViewerRange = Math.max(1.0D, mc.getDouble("particles.viewer-range", 96.0D));
        exhaustFlameCount = clampInt(mc.getInt("particles.flame-count", 4), 0, 1000);
        exhaustFlameSpread = clampDouble(mc.getDouble("particles.flame-spread", 0.06D), 0.0D, 32.0D);
        exhaustFlameExtra = clampDouble(mc.getDouble("particles.flame-extra", 0.0D), 0.0D, 32.0D);
        exhaustInnerFlameCount = clampInt(mc.getInt("particles.inner-flame-count", 2), 0, 1000);
        exhaustInnerFlameSpread = clampDouble(mc.getDouble("particles.inner-flame-spread", 0.05D), 0.0D, 32.0D);
        exhaustInnerFlameExtra = clampDouble(mc.getDouble("particles.inner-flame-extra", 0.0D), 0.0D, 32.0D);
        exhaustSmokeCount = clampInt(mc.getInt("particles.smoke-count", 3), 0, 1000);
        exhaustSmokeSpread = clampDouble(mc.getDouble("particles.smoke-spread", 0.14D), 0.0D, 32.0D);
        exhaustSmokeExtra = clampDouble(mc.getDouble("particles.smoke-extra", 0.004D), 0.0D, 32.0D);
        exhaustCloudCount = clampInt(mc.getInt("particles.cloud-count", 2), 0, 1000);
        exhaustCloudSpread = clampDouble(mc.getDouble("particles.cloud-spread", 0.16D), 0.0D, 32.0D);
        exhaustCloudExtra = clampDouble(mc.getDouble("particles.cloud-extra", 0.002D), 0.0D, 32.0D);
        exhaustBackOffset = Math.max(0.0D, mc.getDouble("particles.back-offset", 0.55D));
        exhaustSmokeBackMultiplier = Math.max(1.0D, mc.getDouble("particles.smoke-back-multiplier", 3.0D));

        // MAWS（导弹逼近告警接收机）
        mawsEnabled = mc.getBoolean("maws.enabled", true);
        mawsRange = Math.max(1.0D, mc.getDouble("maws.range", 40.0D));
        // 需求：接近率大于 10 m/s 的箭 / 导弹才告警（0 = 不做接近率过滤）
        mawsClosingSpeed = Math.max(0.0D, mc.getDouble("maws.closing-speed", 10.0D));
        mawsIncludeOwn = mc.getBoolean("maws.include-own", false);
        mawsSoundEnabled = mc.getBoolean("maws.sound.enabled", false);
        mawsSoundInterval = clampInt(mc.getInt("maws.sound.interval-ticks", 2), 1, 200);
    }

    /**
     * 读取全服导弹开关的初值。
     *
     * <p>热重载时传当前运行值当兜底：{@code msl_config.yml} 里删掉这一行不应该把开关重置。
     *
     * @param fallback 配置里没写这一行时使用的值
     */
    public static boolean missileGlobalEnabled(boolean fallback) {
        FileConfiguration mc = missileConfig;
        return mc == null ? fallback : mc.getBoolean("missile.global-enabled", fallback);
    }

    /**
     * {@code /msl global on|off} 是否要把新值写回 {@code msl_config.yml}。
     *
     * <p>配置键 {@code missile.persist-global-switch}，默认 {@code true}。
     * 设成 {@code false} 就回到"只存内存、重启回配置初值"的旧行为。
     */
    public static boolean persistGlobalSwitch() {
        return persistGlobalSwitch;
    }

    /** 顶层键：行首无缩进、形如 {@code key:}。 */
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*:");

    /**
     * {@code global-enabled} 条目：捕获「缩进+键+冒号后空白」「值」「行尾（注释 + 换行符）」三段。
     *
     * <p><b>第 3 段必须把换行符一起吃掉</b>（{@code [^\n]*\n?}）：漏了它就会把这一行和下一行
     * 悄悄合并成一行（YAML 仍能解析，因为多出来的内容会被当成注释，所以只有逐字节比对才发现）。
     */
    private static final Pattern GLOBAL_ENTRY =
            Pattern.compile("([ \\t]+global-enabled:[ \\t]*)([^ \\t#\\r\\n]+)([^\\n]*\\n?)");

    /**
     * 把 {@code missile.global-enabled} 的新值**原地写回** msl_config.yml。
     *
     * <p><b>为什么不用 Bukkit 的 {@code saveConfig()}</b>：那会用内存里的 ConfigurationSection 重写整个文件，
     * **注释全部丢失**（当初把全服开关做成"只存内存"正是为了避开这一点）。这里改成"只替换那一行的值"：
     * 逐行处理、用 {@code split("(?<=\\n)")} 保留每行原有的换行符，因此
     * **注释、缩进、空行、键顺序、CRLF/LF 全部原样保留**。
     *
     * <p>落盘用"临时文件 + 原子替换"，避免写到一半崩溃导致配置被截断。
     *
     * <p>包内可见，便于脱离服务端直接验证（见 {@code dist/PersistCheck.java}）。
     *
     * @param file  msl_config.yml
     * @param value 新值
     * @return 是否成功写入；{@code false} = 文件不存在 / 找不到 missile 段 / 读写出错（调用方记日志即可）
     */
    static boolean writeGlobalEnabled(File file, boolean value) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String[] lines = text.split("(?<=\\n)");          // 行尾换行符保留在行内
            String section = "";
            int insertAt = -1;
            boolean replaced = false;
            for (int index = 0; index < lines.length && !replaced; index++) {
                String line = lines[index];
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;                                  // 注释与空行一律不动
                }
                if (TOP_LEVEL_KEY.matcher(line).lookingAt()) {
                    section = trimmed.substring(0, trimmed.indexOf(':'));
                    if ("missile".equals(section)) {
                        insertAt = index + 1;                  // 键缺失时插到 missile: 段首
                    }
                    continue;
                }
                if (!"missile".equals(section)) {
                    continue;
                }
                Matcher entry = GLOBAL_ENTRY.matcher(line);
                if (entry.lookingAt()) {
                    // 第 1 段（缩进 + 键 + 空格）与第 3 段（可能的行尾注释 + 换行）原样保留
                    lines[index] = entry.group(1) + value + entry.group(3);
                    replaced = true;
                }
            }
            if (!replaced) {
                if (insertAt < 0) {
                    return false;                              // 连 missile: 段都没有：不动文件
                }
                String ending = lines[insertAt - 1].endsWith("\r\n") ? "\r\n" : "\n";
                lines[insertAt] = "  global-enabled: " + value + ending;
            }
            Path target = file.toPath();
            Path temp = target.resolveSibling(file.getName() + ".tmp");
            Files.write(temp, String.join("", lines).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException failure) {
            if (logger != null) {
                logger.warning("写回 " + MISSILE_CONFIG_FILE + " 失败：" + failure);
            }
            return false;
        }
    }

    /**
     * 读取 {@code rwr:} 板块。
     *
     * <p>独立成方法的原因：它不依赖插件实例，可以脱离服务端用一份
     * {@link FileConfiguration} 直接验证"配置键名 + 夹取"（见 {@code dist/RwrCheck.java}）。
     */
    static void loadRwr(FileConfiguration config) {
        rwrEnabled = config.getBoolean("rwr.enabled", true);
        rwrAlertRange = Math.max(1.0D, config.getDouble("rwr.alert-range", 256.0D));
        rwrSoundEnabled = config.getBoolean("rwr.sound.enabled", true);
        // 需求：敌跟踪 0.2s（4 tick）、敌导弹 0.1s（2 tick）。夹取防呆，避免 0 导致每 tick 刷音
        rwrTrackInterval = clampInt(config.getInt("rwr.sound.track-interval-ticks", 4), 1, 200);
        rwrMissileInterval = clampInt(config.getInt("rwr.sound.missile-interval-ticks", 2), 1, 200);
        // 音量允许 >1（客户端会放大），上限 10 防爆音；音调原版有效范围 0.5~2.0
        rwrTrackVolume = (float) clampDouble(config.getDouble("rwr.sound.track-volume", 0.7D), 0.0D, 10.0D);
        rwrTrackHighPitch = (float) clampDouble(config.getDouble("rwr.sound.track-high-pitch", 1.8D), 0.5D, 2.0D);
        rwrTrackLowPitch = (float) clampDouble(config.getDouble("rwr.sound.track-low-pitch", 0.7D), 0.5D, 2.0D);
        rwrMissileVolume = (float) clampDouble(config.getDouble("rwr.sound.missile-volume", 1.0D), 0.0D, 10.0D);
        rwrMissilePitch = (float) clampDouble(config.getDouble("rwr.sound.missile-pitch", 1.6D), 0.5D, 2.0D);
    }

    /** 数值夹取（配置防呆）。 */
    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 数值夹取（配置防呆）。 */
    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 把 {@code messages:} 板块读成「点分 key → 文本」的平铺表。
     *
     * <p>两种写法都支持：
     * <ol>
     *   <li><b>嵌套</b>：{@code command: { enabled: "…" }} → 路径 {@code command.enabled}</li>
     *   <li><b>扁平</b>：{@code "command.enabled": "…"} → 键名本身含点</li>
     * </ol>
     * 扁平写法必须用 {@code getValues(false)} 取字面键名：Bukkit 默认以 {@code .} 作路径分隔符，
     * {@code get("command.enabled")} 会被当成路径去找 {@code command} 子节，从而静默取不到值。
     */
    private static Map<String, String> readMessages(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("messages");
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<String, String> overrides = new HashMap<>();
        for (String key : section.getKeys(true)) {
            if (section.isString(key)) {
                overrides.put(key, section.getString(key));
            }
        }
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            if (entry.getValue() instanceof String text && entry.getKey().contains(".")) {
                overrides.put(entry.getKey(), text);
            }
        }
        return overrides;
    }

    /** 语言标识（{@code lang/<language>.yml}）。 */
    public static String language() {
        return language;
    }

    /** 战斗部是否破坏方块。 */
    public static boolean breakBlocks() {
        return breakBlocks;
    }

    /**
     * config.yml 对某条文案的覆盖。
     *
     * @return 未覆盖时返回 {@code null}（由 {@link Lang} 回退到语言文件）
     */
    public static String messageOverride(String key) {
        return messageOverrides.get(key);
    }

    /** 已覆盖的文案条数（启动日志用）。 */
    public static int overrideCount() {
        return messageOverrides.size();
    }

    // ------------------------------------------------------------------ 目标筛选存储

    /** 存储类型：{@code JSON} 或 {@code MYSQL}。 */
    public static String filterStorageType() {
        return storageType;
    }

    /** 脏检查保存周期（秒）。 */
    public static int filterAutoSaveSeconds() {
        return autoSaveSeconds;
    }

    /** JSON 存储文件（相对插件数据目录）。 */
    public static String filterJsonFile() {
        return jsonFile;
    }

    public static String mysqlHost() {
        return mysqlHost;
    }

    public static int mysqlPort() {
        return mysqlPort;
    }

    public static String mysqlDatabase() {
        return mysqlDatabase;
    }

    public static String mysqlUser() {
        return mysqlUser;
    }

    public static String mysqlPassword() {
        return mysqlPassword;
    }

    public static String mysqlTable() {
        return mysqlTable;
    }

    public static boolean mysqlUseSsl() {
        return mysqlUseSsl;
    }

    // ------------------------------------------------------------------ RWR（雷达告警接收机）

    /** RWR 总开关：false = 完全不告警（BossBar 与告警音都关）。 */
    public static boolean rwrEnabled() {
        return rwrEnabled;
    }

    /** 告警距离上限（格）。 */
    public static double rwrAlertRange() {
        return rwrAlertRange;
    }

    /** 是否播放告警音。 */
    public static boolean rwrSoundEnabled() {
        return rwrSoundEnabled;
    }

    /** 敌跟踪告警音间隔（tick）：需求值 0.2s = 4 tick。 */
    public static int rwrTrackInterval() {
        return rwrTrackInterval;
    }

    /** 敌导弹告警音间隔（tick）：需求值 0.1s = 2 tick。 */
    public static int rwrMissileInterval() {
        return rwrMissileInterval;
    }

    public static float rwrTrackVolume() {
        return rwrTrackVolume;
    }

    public static float rwrTrackHighPitch() {
        return rwrTrackHighPitch;
    }

    public static float rwrTrackLowPitch() {
        return rwrTrackLowPitch;
    }

    public static float rwrMissileVolume() {
        return rwrMissileVolume;
    }

    public static float rwrMissilePitch() {
        return rwrMissilePitch;
    }

    // ------------------------------------------------------------------ 发射与导引头

    /** 导引头锁定距离（格）。配置键 {@code launcher.lock-range}。 */
    public static double lockRange() {
        return lockRange;
    }

    /** 准星锥角半角（度）。配置键 {@code launcher.lock-cone}。 */
    public static double lockCone() {
        return lockCone;
    }

    /** 导引头提示与锁定刷新周期（tick）。配置键 {@code launcher.refresh-interval-ticks}。 */
    public static int seekerRefreshTicks() {
        return seekerRefreshTicks;
    }

    /**
     * 锁定目标时 ActionBar 两端各加的一段文本（§2.5）。配置键 {@code launcher.lock-padding}，
     * 默认 {@code " &f&k1"}（一个空格 + 白色乱码）；写成空串 = 不加包裹。
     *
     * <p>只作用于"发射前已锁定"的那条 ActionBar：未锁定（搜索中）与驾束弹固定文案都不加。
     */
    public static String seekerLockPadding() {
        return seekerLockPadding;
    }

    /**
     * 读取锁定包裹段：缺键用默认值；显式写空串表示关闭（所以这里不能用 {@code getString(path, def)} 的语义）。
     */
    private static String readLockPadding(FileConfiguration config) {
        if (!config.isSet("launcher.lock-padding")) {
            return " &f&k1";
        }
        String value = config.getString("launcher.lock-padding");
        return value == null ? "" : value;
    }

    /** 出膛点前移距离（格）。配置键 {@code launcher.muzzle-offset}。 */
    public static double muzzleOffset() {
        return muzzleOffset;
    }

    /** 同时在飞的导弹上限。配置键 {@code launcher.max-active}。 */
    public static int maxActiveMissiles() {
        return maxActiveMissiles;
    }

    // ------------------------------------------------------------------ 弹体飞行

    /** 弹体最长寿命（tick）。配置键 {@code flight.max-life-ticks}。 */
    public static int maxLifeTicks() {
        return maxLifeTicks;
    }

    /** 近炸引信半径（格）。配置键 {@code flight.proximity-fuse}。 */
    public static double proximityFuse() {
        return proximityFuse;
    }

    /** 脱锁惯性记忆时长（tick）。配置键 {@code flight.inertial-memory-ticks}。 */
    public static int inertialMemoryTicks() {
        return inertialMemoryTicks;
    }

    /** 干扰判定周期（tick）。配置键 {@code decoy.interval-ticks}（旧键 {@code flight.decoy-interval-ticks} 仍兼容）。 */
    public static int decoyIntervalTicks() {
        return decoyIntervalTicks;
    }

    /** 干扰源搜索半径（格）。配置键 {@code decoy.search-range}（旧键 {@code flight.decoy-search-range} 仍兼容）。 */
    public static double decoySearchRange() {
        return decoySearchRange;
    }

    /**
     * 是否**只有玩家丢出**的干扰物才有效。配置键 {@code decoy.require-thrown}，默认 {@code true}。
     *
     * <p>写成 {@code false} 就退回旧行为（手持干扰物也算）。
     */
    public static boolean decoyRequireThrown() {
        return decoyRequireThrown;
    }

    /** 丢出的干扰物有效时长（tick）。配置键 {@code decoy.ttl-ticks}，默认 60 = 3 秒。 */
    public static int decoyTtlTicks() {
        return decoyTtlTicks;
    }

    /**
     * 被干扰后的**脱锁保护时长**（tick）。配置键 {@code decoy.lockout-ticks}，默认 100 = 5 秒。
     *
     * <p>这段时间内导弹不会重新锁回干扰源（红外 = 丢出烈焰粉的玩家），
     * 也就是用户说的"烈焰粉有概率让导弹脱锁一段时间"（决策 #32）。写 0 = 关闭保护。
     */
    public static int decoyLockoutTicks() {
        return decoyLockoutTicks;
    }

    /** 重截获延迟（tick）。配置键 {@code flight.reacquire-delay-ticks}。 */
    public static int reacquireDelayTicks() {
        return reacquireDelayTicks;
    }

    // ------------------------------------------------------------------ 驾束

    /** 驾束射线长度（格）。配置键 {@code beam.length}。 */
    public static double beamLength() {
        return beamLength;
    }

    /** 驾束最小射线长度（格）。配置键 {@code beam.min-length}。 */
    public static double beamMinLength() {
        return beamMinLength;
    }

    // ------------------------------------------------------------------ 粒子

    /** 粒子生成所需的最近观察者距离（格）。配置键 {@code particles.viewer-range}。 */
    public static double particleViewerRange() {
        return particleViewerRange;
    }

    public static int exhaustFlameCount() {
        return exhaustFlameCount;
    }

    public static double exhaustFlameSpread() {
        return exhaustFlameSpread;
    }

    public static double exhaustFlameExtra() {
        return exhaustFlameExtra;
    }

    public static int exhaustInnerFlameCount() {
        return exhaustInnerFlameCount;
    }

    public static double exhaustInnerFlameSpread() {
        return exhaustInnerFlameSpread;
    }

    public static double exhaustInnerFlameExtra() {
        return exhaustInnerFlameExtra;
    }

    public static int exhaustSmokeCount() {
        return exhaustSmokeCount;
    }

    public static double exhaustSmokeSpread() {
        return exhaustSmokeSpread;
    }

    public static double exhaustSmokeExtra() {
        return exhaustSmokeExtra;
    }

    public static int exhaustCloudCount() {
        return exhaustCloudCount;
    }

    public static double exhaustCloudSpread() {
        return exhaustCloudSpread;
    }

    public static double exhaustCloudExtra() {
        return exhaustCloudExtra;
    }

    /** 尾焰相对弹体向后的偏移（格）。配置键 {@code particles.back-offset}。 */
    public static double exhaustBackOffset() {
        return exhaustBackOffset;
    }

    /** 尾烟再往后推的倍数。配置键 {@code particles.smoke-back-multiplier}。 */
    public static double exhaustSmokeBackMultiplier() {
        return exhaustSmokeBackMultiplier;
    }

    // ------------------------------------------------------------------ MAWS（导弹逼近告警接收机）

    /** MAWS 总开关。配置键 {@code maws.enabled}。 */
    public static boolean mawsEnabled() {
        return mawsEnabled;
    }

    /** MAWS 触发距离（格），需求值 40。配置键 {@code maws.range}。 */
    public static double mawsRange() {
        return mawsRange;
    }

    /**
     * MAWS 触发所需的最小**接近率**（米/秒 = 格/秒），需求值 10。
     * 配置键 {@code maws.closing-speed}；写 0 = 不做接近率过滤（回到"在范围内就报"）。
     */
    public static double mawsClosingSpeed() {
        return mawsClosingSpeed;
    }

    /** 是否把玩家**自己射出**的箭 / 导弹也算进 MAWS（默认 false，避免刚发射就自曝）。配置键 {@code maws.include-own}。 */
    public static boolean mawsIncludeOwn() {
        return mawsIncludeOwn;
    }

    /** MAWS 是否播放告警音。配置键 {@code maws.sound.enabled}（默认 false）。 */
    public static boolean mawsSoundEnabled() {
        return mawsSoundEnabled;
    }

    /** MAWS 告警音间隔（tick）。配置键 {@code maws.sound.interval-ticks}。 */
    public static int mawsSoundInterval() {
        return mawsSoundInterval;
    }

    // ------------------------------------------------------------------ 导弹型号参数

    /**
     * 读取型号数值参数 {@code types.<configId>.<key>}（见 {@link MissileType#configId()}）。
     *
     * @param fallback 配置没写、或值不是数字时使用的出厂默认值（即枚举里写死的那个数）
     */
    public static double typeNumber(MissileType type, String key, double fallback) {
        FileConfiguration current = missileConfig;
        if (current == null) {
            return fallback;
        }
        return current.getDouble(typePath(type, key), fallback);
    }

    /**
     * 读取型号文本参数（目前用于型号色 {@code color}）。
     *
     * @param fallback 配置没写、或只写了空白时使用的出厂默认值
     */
    public static String typeString(MissileType type, String key, String fallback) {
        FileConfiguration current = missileConfig;
        if (current == null) {
            return fallback;
        }
        String value = current.getString(typePath(type, key));
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 读取型号布尔参数 {@code types.<configId>.<key>}。 */
    public static boolean typeFlag(MissileType type, String key, boolean fallback) {
        FileConfiguration current = missileConfig;
        if (current == null) {
            return fallback;
        }
        return current.getBoolean(typePath(type, key), fallback);
    }

    /**
     * 读取型号材质参数（如干扰物 {@code decoy-material}）。
     *
     * @return 配置写了但材质名无法识别时告警一次并返回 {@code fallback}
     */
    public static Material typeMaterial(MissileType type, String key, Material fallback) {
        Object resolved = typeEnum(type, key, Material::matchMaterial, "Material（方块/物品）");
        return resolved instanceof Material material ? material : fallback;
    }

    /**
     * 读取型号粒子参数（{@code flame-particle} / {@code smoke-particle}）。
     *
     * @return 配置写了但粒子名无法识别时告警一次并返回 {@code fallback}
     */
    public static Particle typeParticle(MissileType type, String key, Particle fallback) {
        Object resolved = typeEnum(type, key, Settings::parseParticle, "Particle（粒子）");
        return resolved instanceof Particle particle ? particle : fallback;
    }

    /** 粒子名解析：Bukkit 的 {@code Particle.valueOf} 大小写敏感，这里统一转大写并吞掉非法名。 */
    private static Object parseParticle(String raw) {
        try {
            return Particle.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAParticle) {
            return null;
        }
    }

    /**
     * 解析 {@code types.<id>.<key>} 里的枚举名（材质 / 粒子）。
     *
     * <p>这些访问器每 tick 都被在飞的导弹调用，所以按配置路径缓存解析结果；
     * 缓存由 {@link #load} 清空 → 坏值每次载入只告警一次（不刷屏），
     * 改好 config.yml 后 {@code /msl reload} 即可重新解析。
     *
     * @param parser 名字 → 枚举值；无法识别时必须返回 {@code null}
     * @return 解析结果，或 {@link #INVALID} 表示无法使用
     */
    private static Object typeEnum(MissileType type, String key, Function<String, Object> parser,
                                   String kindLabel) {
        String path = typePath(type, key);
        Object cached = typeEnumCache.get(path);
        if (cached != null) {
            return cached;
        }
        FileConfiguration current = missileConfig;
        String raw = current == null ? null : current.getString(path, null);
        if (raw == null || raw.isBlank()) {
            // 没写这一行属于正常情况（用出厂默认值），静默处理
            typeEnumCache.put(path, INVALID);
            return INVALID;
        }
        Object parsed = parser.apply(raw.trim());
        if (parsed == null) {
            if (logger != null) {
                logger.warning("config.yml 的 " + path + " = \"" + raw + "\" 不是可识别的 " + kindLabel
                        + "名，已改用内置默认值（改好后用 /msl reload 重新载入即可生效）");
            }
            parsed = INVALID;
        }
        typeEnumCache.put(path, parsed);
        return parsed;
    }

    /** 型号参数在 config.yml 里的完整路径：{@code types.<configId>.<key>}。 */
    private static String typePath(MissileType type, String key) {
        return "types." + type.configId() + "." + key;
    }
}
