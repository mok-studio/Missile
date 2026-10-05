package com.missile;

import java.util.List;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI 扩展（identifier {@code msl}），随插件打包、**自动注册**，不需要用户手动装扩展。
 *
 * <p>提供的占位符（{@code %msl%} 与 {@code %msl_xxx%} 两种写法都在）：
 * <table border="1">
 *   <caption>占位符一览</caption>
 *   <tr><th>占位符</th><th>含义</th></tr>
 *   <tr><td>{@code %msl%}</td><td>当前型号显示名（红外导弹 / 主动雷达寻的 …）</td></tr>
 *   <tr><td>{@code %msl_type%}</td><td>当前型号的配置 id（{@code infrared} / {@code super-active} …）</td></tr>
 *   <tr><td>{@code %msl_entity_name%}</td><td>导引头锁定的目标名（玩家显示玩家 ID）；没锁定时显示"搜索中"</td></tr>
 *   <tr><td>{@code %msl_locked%}</td><td>当前是否有锁定（{@code true} / {@code false}）</td></tr>
 *   <tr><td>{@code %msl_lock%}</td><td>整句锁定状态（"锁定 X" / "搜索中"）</td></tr>
 *   <tr><td>{@code %msl_target_kind%}</td><td>「目标」类型，按 {@code /msl filter} 的**内容**判断（玩家 / 实体 / 玩家/实体）</td></tr>
 *   <tr><td>{@code %msl_maws%}</td><td>MAWS 最近威胁的方位箭头；无威胁时空串</td></tr>
 *   <tr><td>{@code %msl_armed%}</td><td>导引头是否已开启（{@code true} / {@code false}）</td></tr>
 *   <tr><td>{@code %msl_on%}</td><td>该玩家个人导弹开关（{@code /msl on|off}）</td></tr>
 *   <tr><td>{@code %msl_global%}</td><td>全服导弹开关</td></tr>
 *   <tr><td>{@code %msl_active%}</td><td>当前在飞的导弹数量</td></tr>
 * </table>
 *
 * <p>无法识别的占位符返回 {@code null}（PAPI 约定：保持原样不替换）。
 * 这些取值与导引头 ActionBar 的内部替换**共用同一个 {@link #value} 方法**，
 * 所以 TAB 里显示的和屏幕上看到的不可能出现两套口径。
 */
public final class MissilePlaceholders extends PlaceholderExpansion {

    /** 全部支持的参数名（不含 {@code %msl%} 的空参数形式），{@link #render} 用它做替换。 */
    private static final List<String> KEYS = List.of("type", "type_name", "entity_name", "locked", "lock",
            "target_kind", "maws", "armed", "on", "global", "active");

    private final MissilePlugin plugin;

    public MissilePlaceholders(MissilePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "msl";
    }

    @Override
    public String getAuthor() {
        return "Missile";
    }

    @Override
    public String getVersion() {
        return MissilePlugin.VERSION;
    }

    /** {@code /papi reload} 之后仍然保持注册（否则重载一次扩展就没了）。 */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return null;
        }
        Player online = player.getPlayer();
        if (online == null) {
            return null;                  // 离线玩家没有导引头 / 告警状态
        }
        return value(this.plugin, online, params == null ? "" : params);
    }

    /**
     * 把一段文本里的 {@code %msl%} / {@code %msl_xxx%} 全部替换掉。
     *
     * <p>给导引头 ActionBar 用：**不依赖 PlaceholderAPI**，没装 PAPI 的服务器也能正常显示。
     * {@code %msl%} 与 {@code %msl_xxx%} 不会互相误匹配（一个是 {@code %msl%}、
     * 另一个在 {@code msl} 后面跟的是下划线），所以替换顺序无所谓。
     */
    static String render(MissilePlugin plugin, Player player, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text.replace("%msl%", value(plugin, player, ""));
        for (String key : KEYS) {
            String token = "%msl_" + key + "%";
            if (out.contains(token)) {
                out = out.replace(token, value(plugin, player, key));
            }
        }
        return out;
    }

    /**
     * 单个占位符的取值（ActionBar 与 PAPI 共用）。
     *
     * @param params 形如 {@code ""}（即 {@code %msl%}）、{@code entity_name}、{@code target_kind}
     * @return 未知占位符返回 {@code null}
     */
    static String value(MissilePlugin plugin, Player player, String params) {
        String key = params == null ? "" : params.trim().toLowerCase(Locale.ROOT);
        SeekerListener.SeekerState state = plugin.seekers() == null
                ? null : plugin.seekers().stateIfPresent(player);
        MissileType type = state == null ? MissileType.INFRARED : state.type();
        return switch (key) {
            case "", "msl", "type_name" -> type.coloredName();
            case "type", "type_id" -> type.configId();
            case "entity_name" -> state == null || state.targetName() == null
                    ? Lang.get("seeker.lock-search") : state.targetName();
            case "locked" -> String.valueOf(state != null && state.targetName() != null);
            case "lock" -> state == null || state.targetName() == null
                    ? Lang.get("seeker.lock-search")
                    : Lang.get("seeker.lock-found", "target", state.targetName());
            case "target_kind" -> targetLabel(player, state);
            case "maws" -> {
                RwrManager rwr = plugin.rwr();
                yield rwr == null ? "" : rwr.mawsArrow(player);
            }
            case "armed" -> String.valueOf(state != null && state.armed());
            case "on" -> String.valueOf(MissilePlugin.isPlayerEnabled(player.getUniqueId()));
            case "global" -> String.valueOf(MissilePlugin.isGlobalEnabled());
            case "active" -> String.valueOf(plugin.missiles() == null ? 0 : plugin.missiles().activeCount());
            default -> null;
        };
    }

    /**
     * ActionBar 里「**目标:**」那个字段的取值（§2.1 / 决策 #33）。
     *
     * <p>文案是 **混合 / 任意 / 实体 / 玩家**，口径 = **实际生效类别**：
     * <ul>
     *   <li>{@code SUPER_ACTIVE} → 看 **safilter**（{@link SaProfile} 的三种模式）：
     *       {@code ANY → 任意}、{@code ENTITY → 实体}、{@code PLAYER → 玩家}；</li>
     *   <li>其它型号 → 看 {@code TargetSelector#activeClasses}：筛选模式（或 {@code usefilter}）打开时
     *       以白名单启用的类别为准（两类都启用 = **混合**）；筛选没生效时按导弹自己的锁定类型，
     *       {@code ANY} 显示"任意"；</li>
     *   <li>没有导引头状态时按"玩家"起步（与 {@code lockKind} 的默认值一致）。</li>
     * </ul>
     *
     * <p>注意：**「锁定 …」那半句不在此列**——它由 {@code %msl_entity_name%} 提供，
     * 未锁定时仍显示"搜索中"。
     */
    private static String targetLabel(Player player, SeekerListener.SeekerState state) {
        if (state != null && state.type() == MissileType.SUPER_ACTIVE) {
            return switch (state.saProfile().mode()) {
                case ANY -> Lang.get("target.any");
                case ENTITY -> Lang.get("target.entity");
                case PLAYER -> Lang.get("target.player");
            };
        }
        MissileType.TargetKind kind = state == null ? MissileType.TargetKind.PLAYER : state.lockKind();
        boolean whitelistOnly = state != null && state.whitelistOnly();
        String none = switch (kind) {
            case ANY -> Lang.get("target.any");
            case ENTITY -> Lang.get("target.entity");
            case PLAYER -> Lang.get("target.player");
        };
        return TargetFilter.labelOf(TargetSelector.activeClasses(player, whitelistOnly), none);
    }
}
