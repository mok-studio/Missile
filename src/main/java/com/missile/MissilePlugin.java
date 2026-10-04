package com.missile;

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

    /**
     * 玩家个人导弹开关：只记录**已关闭**的玩家（未记录 = 默认开启）。
     * 仅存内存，不写 config.yml，重启后全部恢复默认开启。
     */
    private static final Map<UUID, Boolean> playerMissileEnabled = new HashMap<>();

    private final Random random = new Random();
    private MissileManager missiles;
    private SeekerListener seekers;
    private RwrManager rwr;
    private BukkitTask tickTask;
    private boolean breakBlocks = true;
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
            this.breakBlocks = this.getConfig().getBoolean("explosion.break-blocks", true);
            this.language = this.getConfig().getString("language", Lang.DEFAULT_LOCALE);
        } catch (Throwable throwable) {
            this.logFailure("读取 config.yml 失败", throwable);
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
        this.getLogger().info(Lang.get("console.enabled"));
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
    }

    public MissileManager missiles() {
        return this.missiles;
    }

    public SeekerListener seekers() {
        return this.seekers;
    }

    public Random random() {
        return this.random;
    }

    /** 战斗部是否破坏方块，读取 config.yml 的 explosion.break-blocks。 */
    public boolean breakBlocks() {
        return this.breakBlocks;
    }

    /** 当前语言标识（如 zh_cn），读取 config.yml 的 language。 */
    public String language() {
        return this.language;
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
