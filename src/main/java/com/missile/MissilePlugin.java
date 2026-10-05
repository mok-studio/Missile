package com.missile;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 插件主类：注册监听器，并每 tick 推进导引头与在飞导弹。
 *
 * <p>型号与锁定目标类型通过 {@code /missile <ir|semi|active|semiLOS> <player|entity>} 选择
 * （别名 {@code /msl}），命令实现见 MissileCommand。
 */
public final class MissilePlugin extends JavaPlugin {

    /** 插件版本号：写在代码内（按要求不放进 config.yml）。 */
    public static final String VERSION = "1.0.2";

    /**
     * 全服导弹开关（{@code /msl global on|off}）：启动时取 msl_config.yml 的
     * {@code missile.global-enabled}；运行期切换会**写回**该键（保留注释，见
     * {@link Settings#writeGlobalEnabled}），除非把 {@code missile.persist-global-switch} 设为 false。
     *
     * <p>与玩家个人开关相互独立——两者都开启时该玩家才可用导弹。
     */
    private static boolean globalEnabled = true;

    /**
     * 玩家个人导弹开关：只记录**已关闭**的玩家（未记录 = 默认开启）。
     * 仅存内存，不写 config.yml，重启后全部恢复默认开启。
     */
    private static final Map<UUID, Boolean> playerMissileEnabled = new HashMap<>();

    private final Random random = new Random();
    private MissileManager missiles;
    private SeekerListener seekers;
    private RwrManager rwr;
    /** PlaceholderAPI 扩展（服务端没装 PAPI 时保持 null）。 */
    private MissilePlaceholders placeholders;
    private FilterStorage storage;
    private BukkitTask tickTask;
    private String language = Lang.DEFAULT_LOCALE;

    @Override
    public void onEnable() {
        // 任何一步抛异常都会让 Paper 直接禁用整个插件（控制台出现
        // "Error occurred while enabling Missile (Is it up to date?)"，之后 /msl 报 plugin is disabled）。
        // 下面按阶段捕获并记录堆栈：出错不再向外抛，插件保持启用，真正原因留在日志里。
        try {
            this.saveDefaultConfig();
        } catch (Throwable throwable) {
            this.logFailure("释放 config.yml 失败", throwable);
        }
        try {
            Settings.load(this);                      // config.yml + msl_config.yml 单一入口，可热重载
            this.language = Settings.language();
            globalEnabled = Settings.missileGlobalEnabled(true);
        } catch (Throwable throwable) {
            this.logFailure("读取配置文件失败", throwable);
        }
        try {
            Lang.load(this, this.language);
            this.language = Lang.locale();
            this.getLogger().info(Lang.get("console.lang-loaded",
                    "locale", Lang.locale(), "count", Lang.size()));
        } catch (Throwable throwable) {
            this.logFailure("加载语言文件失败", throwable);
        }
        try {
            TargetFilter.reset();
        } catch (Throwable throwable) {
            this.logFailure("目标筛选初始化失败", throwable);
        }
        try {
            this.missiles = new MissileManager(this);
            this.seekers = new SeekerListener(this);
            this.rwr = new RwrManager(this);
            this.getServer().getPluginManager().registerEvents(this.seekers, this);
            this.getServer().getPluginManager().registerEvents(new RwrListener(this.rwr), this);
        } catch (Throwable throwable) {
            this.logFailure("监听器注册失败", throwable);
        }
        if (this.missiles == null || this.seekers == null || this.rwr == null) {
            // 核心组件没起来：保持插件启用（不牵连其他插件），但不注册命令与主任务，避免后续 NPE 刷屏
            this.getLogger().severe("[Missile] 核心组件初始化失败，命令与主任务未启用；"
                    + "请把上面第一条 SEVERE 的完整堆栈发给插件作者");
            return;
        }
        try {
            // PlaceholderAPI 是软依赖：只有服务端装了它（plugin.yml 里已声明 softdepend）才注册扩展。
            // MissilePlaceholders 继承 PAPI 的类，所以这里必须先判插件存在，再在 try 里首次引用它
            // （没装 PAPI 时那份类根本不会被 JVM 解析，不会 NoClassDefFoundError）。
            if (this.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                MissilePlaceholders expansion = new MissilePlaceholders(this);
                if (expansion.register()) {
                    this.placeholders = expansion;
                    this.getLogger().info("已注册 PlaceholderAPI 扩展 msl：%msl% / %msl_entity_name% / "
                            + "%msl_target_kind% / %msl_maws% 等");
                } else {
                    this.getLogger().warning("PlaceholderAPI 扩展 msl 注册失败（identifier 可能已被占用）");
                }
            }
        } catch (Throwable throwable) {
            this.logFailure("注册 PlaceholderAPI 扩展失败（插件继续运行，只是没有占位符）", throwable);
        }
        try {
            this.storage = new FilterStorage(this);
            this.storage.start();                    // 异步载入 + 定时脏检查保存
        } catch (Throwable throwable) {
            this.logFailure("筛选数据存储启动失败", throwable);
        }
        try {
            MissileCommand command = new MissileCommand(this);
            PluginCommand main = this.getCommand("missile");
            if (main == null) {
                this.getLogger().warning(Lang.get("console.no-command"));
            } else {
                main.setExecutor(command);
                main.setTabCompleter(command);
            }
            // /msl 通常只是别名（与主命令是同一个 PluginCommand 对象）；若服务端单独提供则一并挂上
            PluginCommand alias = this.getCommand("msl");
            if (alias != null && alias != main) {
                alias.setExecutor(command);
                alias.setTabCompleter(command);
            }
        } catch (Throwable throwable) {
            this.logFailure("命令注册失败", throwable);
        }
        try {
            this.tickTask = this.getServer().getScheduler().runTaskTimer(this, () -> {
                this.seekers.refresh();
                this.missiles.tick();
                this.rwr.tick();
            }, 1L, 1L);
        } catch (Throwable throwable) {
            this.logFailure("主任务启动失败", throwable);
        }
        this.getLogger().info("Missile v" + VERSION + " - " + Lang.get("console.enabled"));
    }

    /**
     * 记录初始化失败但**不向外抛**：单个阶段异常不会导致整个插件被 Paper 禁用。
     *
     * <p>用 {@code log(Level.SEVERE, msg, throwable)} 而不是 {@code severe(msg) + printStackTrace()}，
     * 是为了保证完整堆栈一定写进 {@code logs/latest.log}。
     */
    private void logFailure(String stage, Throwable throwable) {
        this.getLogger().log(Level.SEVERE, "[Missile] " + stage + " - "
                + throwable.getClass().getName() + ": " + throwable.getMessage(), throwable);
    }

    @Override
    public void onDisable() {
        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }
        if (this.missiles != null) {
            this.missiles.shutdown();
        }
        if (this.seekers != null) {
            this.seekers.clearAll();
        }
        if (this.rwr != null) {
            this.rwr.shutdown();
        }
        if (this.storage != null) {
            this.storage.stop();                     // 停定时器 + 同步落盘一次
        }
        if (this.placeholders != null) {
            this.placeholders.unregister();          // 注销 PAPI 扩展，避免重载后残留
            this.placeholders = null;
        }
    }

    public MissileManager missiles() {
        return this.missiles;
    }

    public SeekerListener seekers() {
        return this.seekers;
    }

    /** RWR / MAWS 管理器（占位符 {@code %msl_maws%} 要用它读缓存的方位）。 */
    public RwrManager rwr() {
        return this.rwr;
    }

    public Random random() {
        return this.random;
    }

    /** 战斗部是否破坏方块（来自 config.yml 的 explosion.break-blocks）。 */
    public boolean breakBlocks() {
        return Settings.breakBlocks();
    }

    /** 当前语言标识（如 zh_cn），读取 config.yml 的 language。 */
    public String language() {
        return this.language;
    }

    /** 全服导弹开关是否开启（静态，供事件监听器最前置判断）。 */
    public static boolean isGlobalEnabled() {
        return globalEnabled;
    }

    /** 切换全服导弹开关（仅内存；玩家个人设置不受影响）。 */
    public static void setGlobalEnabled(boolean value) {
        globalEnabled = value;
    }

    /**
     * 把全服开关的值**写回** {@code msl_config.yml}（保留注释），供 {@code /msl global on|off} 调用。
     *
     * <p>只有 {@code missile.persist-global-switch: true}（默认）时才写盘；写盘失败只记日志——
     * 内存里的开关**已经生效**，写盘只是为了重启后仍然记得。
     */
    public void persistGlobalSwitch(boolean value) {
        if (!Settings.persistGlobalSwitch()) {
            return;
        }
        File file = new File(this.getDataFolder(), Settings.MISSILE_CONFIG_FILE);
        if (Settings.writeGlobalEnabled(file, value)) {
            this.getLogger().info("全服导弹开关已写入 " + Settings.MISSILE_CONFIG_FILE + ": " + value);
        } else {
            this.getLogger().warning("未能把全服导弹开关写回 " + Settings.MISSILE_CONFIG_FILE
                    + "（本次仅内存生效；可手工改配置或用 /msl global 再试）");
        }
    }

    /**
     * {@code /msl reload}：重读 config.yml、语言文件与平台级开关，并把待写筛选数据立刻落盘。
     *
     * @return 是否全部成功（任一阶段失败只记日志，不抛出）
     */
    public boolean reloadPluginSettings() {
        boolean success = true;
        try {
            Settings.load(this);
            this.language = Settings.language();
            Lang.load(this, this.language);
            this.language = Lang.locale();
            globalEnabled = Settings.missileGlobalEnabled(globalEnabled);
        } catch (Throwable throwable) {
            success = false;
            this.logFailure("热重载配置失败", throwable);
        }
        try {
            if (this.storage != null) {
                this.storage.saveNow();               // 顺手把筛选数据落盘
            }
        } catch (Throwable throwable) {
            success = false;
            this.logFailure("热重载时保存筛选数据失败", throwable);
        }
        return success;
    }

    /**
     * 该玩家的导弹开关是否开启（**未记录 = 默认开启**）。
     *
     * <p>静态方法，供事件监听器在最前置处快速判断。
     * 注意：方法名不能取 {@code isEnabled()} / {@code setEnabled(boolean)}——
     * {@link JavaPlugin} 已把这两个名字声明为 <b>final</b> 实例方法（插件自身启用标志），
     * 同名声明会直接编译失败。
     */
    public static boolean isPlayerEnabled(UUID playerId) {
        return playerId == null || !Boolean.FALSE.equals(playerMissileEnabled.get(playerId));
    }

    /**
     * 设置玩家的导弹开关（仅内存，不写 config.yml；重启后恢复默认开启）。
     *
     * <p>开启时直接移除记录，保持映射最小（“无记录 = 开启”）。
     */
    public static void setPlayerEnabled(UUID playerId, boolean value) {
        if (playerId == null) {
            return;
        }
        if (value) {
            playerMissileEnabled.remove(playerId);
        } else {
            playerMissileEnabled.put(playerId, Boolean.FALSE);
        }
    }

}
