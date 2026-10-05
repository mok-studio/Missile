package com.missile;

import java.io.File;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 极简多语言文案表（无第三方依赖）。
 *
 * <p>文案读取顺序：{@code plugins/Missile/lang/<locale>.yml}；文件不存在时从插件内置资源释放
 * （jar 内 {@code lang/zh_cn.yml}、{@code lang/en_us.yml}）。默认语言 {@value #DEFAULT_LOCALE}，
 * 由 {@code config.yml} 的 {@code language} 指定。
 *
 * <p>用法：
 * <ul>
 *   <li>界面 / 动作栏 / BossBar：{@code Lang.get("rwr.bossbar-missile", "dir", dir)}</li>
 *   <li>聊天消息（自动加前缀）：{@code Lang.msg("command.no-permission")}</li>
 * </ul>
 *
 * <p>文案来源与覆盖：先查 {@code config.yml} 的 {@code messages:} 板块
 * （{@link Settings#messageOverride}），没有再回退到 {@code lang/<language>.yml}。
 *
 * <p>占位符写 {@code {name}}，参数为交替的「名称, 值」。颜色支持 {@code &} 代码（{@code &6} {@code &c}）
 * 与十六进制 {@code &#RRGGBB}（展开为原版 {@code §x§R§R§G§G§B§B}）。
 * 缺失的 key 会返回 key 本身并在控制台告警一次，便于发现漏翻。
 */
public final class Lang {

    /** 默认语言。 */
    public static final String DEFAULT_LOCALE = "zh_cn";

    /** 十六进制颜色：{@code &#RRGGBB} 或原版 {@code &x&R&R&G&G&B&B}。 */
    private static final Pattern HEX_COLOR =
            Pattern.compile("&#([0-9a-fA-F]{6})|&[xX]((?:&[0-9a-fA-F]){6})");

    /** 颜色代码：&a &7 &l 等。 */
    private static final Pattern COLOR = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private static final Set<String> MISSING = new HashSet<>();

    private static YamlConfiguration configuration = new YamlConfiguration();
    private static String locale = DEFAULT_LOCALE;
    private static JavaPlugin owner;

    private Lang() {
    }

    /**
     * 由主类在 onEnable 调用：释放并加载语言文件。
     *
     * @param plugin    插件实例
     * @param requested 目标语言，{@code null} / 空白 / 资源缺失时回退到 {@link #DEFAULT_LOCALE}
     */
    public static void load(JavaPlugin plugin, String requested) {
        owner = plugin;
        String target = requested == null || requested.isBlank()
                ? DEFAULT_LOCALE
                : requested.trim().toLowerCase(Locale.ROOT);
        String path = "lang/" + target + ".yml";
        if (!hasResource(plugin, path)) {
            plugin.getLogger().warning("缺少内置语言文件 " + path + "，回退到 " + DEFAULT_LOCALE);
            target = DEFAULT_LOCALE;
            path = "lang/" + target + ".yml";
        }
        locale = target;
        File file = new File(plugin.getDataFolder(), path);
        try {
            if (!file.exists()) {
                plugin.saveResource(path, false);
            }
            configuration = YamlConfiguration.loadConfiguration(file);
        } catch (Exception exception) {
            plugin.getLogger().warning("语言文件 " + path + " 加载失败：" + exception.getMessage());
            configuration = new YamlConfiguration();
        }
        MISSING.clear();
    }

    private static boolean hasResource(JavaPlugin plugin, String path) {
        try (InputStream stream = plugin.getResource(path)) {
            return stream != null;
        } catch (Exception exception) {
            return false;
        }
    }

    /** 当前语言标识，如 {@code zh_cn}。 */
    public static String locale() {
        return locale;
    }

    /** 已加载的文案条目数，用于启动日志。 */
    public static int size() {
        return configuration.getKeys(true).size();
    }

    /** 取文案（界面 / 动作栏 / BossBar 用，不加前缀）。 */
    public static String get(String key) {
        return raw(key);
    }

    /**
     * 取文案并替换占位符。
     *
     * @param placeholders 交替的「名称, 值」，如 {@code "dir", "3点钟", "count", 2}
     */
    public static String get(String key, Object... placeholders) {
        String text = raw(key);
        if (placeholders == null || placeholders.length < 2) {
            return text;
        }
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            String name = String.valueOf(placeholders[index]);
            String value = String.valueOf(placeholders[index + 1]);
            text = text.replace("{" + name + "}", value);
        }
        return text;
    }

    /** 聊天消息：自动拼接前缀（{@code prefix} 文案）。 */
    public static String msg(String key, Object... placeholders) {
        return raw("prefix") + get(key, placeholders);
    }

    private static String raw(String key) {
        String override = Settings.messageOverride(key);
        String text;
        if (override != null) {
            text = override;                          // config.yml 的 messages 覆盖优先
        } else {
            Object value = configuration.get(key);
            if (value == null) {
                if (MISSING.add(key) && owner != null) {
                    owner.getLogger().warning("语言文件 " + locale + " 缺少文案: " + key);
                }
                text = key;
            } else {
                text = String.valueOf(value);
            }
        }
        return colorize(text);
    }

    /**
     * 颜色转换：{@code &} 代码与十六进制。
     *
     * <p>十六进制支持两种写法，都会展开成原版 {@code §x§R§R§G§G§B§B}（1.16+ 客户端可直接显示）：
     * <ul>
     *   <li>简写 {@code &#RRGGBB}（推荐）</li>
     *   <li>原版写法 {@code &x&R&R&G&G&B&B}</li>
     * </ul>
     * config.yml 与 lang 文件里的文本走同一套转换。
     */
    public static String colorize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher hex = HEX_COLOR.matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (hex.find()) {
            String digits = hex.group(1) != null ? hex.group(1) : hex.group(2).replace("&", "");
            StringBuilder legacy = new StringBuilder("§x");
            for (char digit : digits.toLowerCase(Locale.ROOT).toCharArray()) {
                legacy.append('§').append(digit);
            }
            hex.appendReplacement(expanded, Matcher.quoteReplacement(legacy.toString()));
        }
        hex.appendTail(expanded);
        Matcher matcher = COLOR.matcher(expanded.toString());
        return matcher.find() ? matcher.replaceAll("§$1") : expanded.toString();
    }
}
