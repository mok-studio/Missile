package com.missile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 目标筛选系统：按玩家保存一份筛选配置，决定导引头能锁定哪些目标。
 *
 * <p>两种模式：
 * <ul>
 *   <li>{@code useAll = true}（默认）：自由锁定，完全走原有锁定逻辑，筛选列表被忽略但**保留**；</li>
 *   <li>{@code useAll = false}：仅锁定筛选列表内的目标（排除项优先）。</li>
 * </ul>
 *
 * <p>筛选参数（{@code /missile filter ...}）：
 * <ul>
 *   <li>{@code all} —— 切回自由锁定（useAll = true），**保留**已配置的筛选列表；</li>
 *   <li>{@code clear} —— 清空筛选列表并恢复自由锁定；</li>
 *   <li>{@code entity} / {@code player} —— 仅锁定生物（非玩家）/ 仅锁定玩家，二者互斥；</li>
 *   <li>{@code <实体ID>} / {@code !<实体ID>} —— 包含 / 排除该实体，支持 {@code zombie} 或 {@code minecraft:zombie}；</li>
 *   <li>{@code !entity} / {@code !player} —— 排除生物 / 排除玩家。</li>
 * </ul>
 *
 * <p>包含项之间是「或」：命中任一包含类型或包含 ID 即可锁定；排除项优先于包含项。
 * 筛选配置仅存内存，不写配置文件；同一局游戏内重连仍生效，服务器重启后清空。
 */
public final class TargetFilter {

    /** 每个玩家的筛选配置。 */
    private static final Map<UUID, FilterData> playerFilters = new HashMap<>();

    private static final String TYPE_ENTITY = "entity";
    private static final String TYPE_PLAYER = "player";
    private static final String NAMESPACE_PREFIX = "minecraft:";
    private static final String EXCLUDE_PREFIX = "!";

    /** 全部合法实体 ID（已规范化），用于校验输入；无法解析的条目会被跳过。 */
    private static final Set<String> VALID_IDS = collectValidIds();

    private TargetFilter() {
    }

    /** 由主类在 onEnable 调用：清空所有玩家的筛选配置。 */
    public static void reset() {
        playerFilters.clear();
    }

    /**
     * 收集全部合法实体 ID。
     *
     * <p>{@code EntityType.values()} 含 {@code UNKNOWN} 这类无命名空间条目，其
     * {@code getKey()} 会抛 {@link IllegalArgumentException}。必须逐条捕获跳过：
     * 一旦异常从静态初始化里冒出去，本类会变成 NoClassDefFoundError，插件随之崩。
     */
    private static Set<String> collectValidIds() {
        Set<String> ids = new HashSet<>();
        for (EntityType type : EntityType.values()) {
            try {
                ids.add(normalizeId(type.getKey().getKey()));
            } catch (IllegalArgumentException exception) {
                // UNKNOWN 等无 key 条目：跳过
                Bukkit.getLogger().warning("Skip invalid entity: " + type.name());
            } catch (Throwable throwable) {
                // 兜底：静态初始化绝不抛出
                Bukkit.getLogger().warning("Skip invalid entity: " + type.name());
            }
        }
        return ids;
    }

    /** 规范化实体 ID：去空白、转小写、去掉 {@code minecraft:} 前缀。 */
    static String normalizeId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith(NAMESPACE_PREFIX)) {
            value = value.substring(NAMESPACE_PREFIX.length());
        }
        return value;
    }

    /** 目标实体的规范化 ID，如 {@code zombie}；无法解析（UNKNOWN 等）时返回空串。 */
    static String idOf(LivingEntity target) {
        try {
            return normalizeId(target.getType().getKey().getKey());
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    /** 取该玩家的筛选配置，不存在则创建默认配置（仅非热路径调用）。 */
    static FilterData dataOf(UUID playerId) {
        return playerFilters.computeIfAbsent(playerId, key -> new FilterData());
    }

    /**
     * 目标是否允许被该玩家锁定。
     *
     * <p>{@code useAll = true}（无筛选配置 / 自由锁定）时一律放行；否则排除项优先，
     * 再要求命中任一包含项（包含项为空 = 只排除、不限制其余）。
     */
    public static boolean canTarget(UUID playerId, LivingEntity target) {
        if (playerId == null || target == null) {
            return true;
        }
        FilterData data = playerFilters.get(playerId);
        if (data == null || data.useAll) {
            return true;
        }
        boolean isPlayer = target instanceof Player;
        for (String type : data.excludeTypes) {
            if (matchesType(type, isPlayer)) {
                return false;
            }
        }
        String id = idOf(target);
        if (data.excludeIds.contains(id)) {
            return false;
        }
        if (data.includeTypes.isEmpty() && data.includeIds.isEmpty()) {
            return true;
        }
        for (String type : data.includeTypes) {
            if (matchesType(type, isPlayer)) {
                return true;
            }
        }
        return data.includeIds.contains(id);
    }

    private static boolean matchesType(String type, boolean isPlayer) {
        if (TYPE_PLAYER.equals(type)) {
            return isPlayer;
        }
        // entity 表示“非玩家生物”，与 TargetKind.ENTITY 的语义一致
        return TYPE_ENTITY.equals(type) && !isPlayer;
    }

    /**
     * 解析并应用筛选参数（只作用于该玩家自己的配置）。
     *
     * @param args 形如 {@code ["entity", "zombie", "!creeper"]}
     * @return 需要发给玩家的本地化提示；解析失败时返回错误提示且**不修改**原配置
     */
    public static List<String> parse(UUID playerId, String[] args) {
        List<String> messages = new ArrayList<>();
        if (playerId == null || args == null || args.length == 0) {
            messages.add(Lang.get("filter.usage"));
            return messages;
        }
        String single = args.length == 1 ? args[0].trim().toLowerCase(Locale.ROOT) : null;
        if ("all".equals(single)) {
            // 切回自由锁定，但保留已配置的列表
            dataOf(playerId).useAll = true;
            messages.add(Lang.get("filter.applied-all"));
            return messages;
        }
        if ("clear".equals(single)) {
            FilterData data = dataOf(playerId);
            data.clear();
            data.useAll = true;
            messages.add(Lang.get("filter.applied-clear"));
            return messages;
        }
        for (String arg : args) {
            String lower = arg.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(lower) || "clear".equals(lower)) {
                messages.add(Lang.get("filter.usage"));   // 独占关键字不能与其它条件混用
                return messages;
            }
        }

        FilterData draft = dataOf(playerId).copy();       // 先改副本，校验通过再提交
        for (String arg : args) {
            String token = arg.trim();
            if (token.isEmpty()) {
                continue;
            }
            boolean exclude = token.startsWith(EXCLUDE_PREFIX);
            String body = exclude ? token.substring(EXCLUDE_PREFIX.length()).trim() : token;
            String lower = body.toLowerCase(Locale.ROOT);
            String typeToken = normalizeId(lower);   // minecraft:player → player，玩家走类型判断
            if (TYPE_ENTITY.equals(typeToken) || TYPE_PLAYER.equals(typeToken)) {
                List<String> types = exclude ? draft.excludeTypes : draft.includeTypes;
                if (!types.contains(typeToken)) {
                    types.add(typeToken);
                }
                continue;
            }
            String id = normalizeId(body);
            if (!VALID_IDS.contains(id)) {
                messages.add(Lang.get("filter.unknown-id", "input", body));
                return messages;
            }
            List<String> ids = exclude ? draft.excludeIds : draft.includeIds;
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        String conflict = draft.conflict();
        if (conflict != null) {
            messages.add(Lang.get(conflict));
            return messages;
        }
        draft.useAll = false;                             // 一旦加入筛选条件即切到筛选模式
        playerFilters.put(playerId, draft);
        messages.add(Lang.get("filter.applied", "mode", Lang.get("filter.mode-filter")));
        return messages;
    }

    /** 当前筛选的文字描述（{@code /missile filter list}）。 */
    public static List<String> describe(UUID playerId) {
        FilterData data = dataOf(playerId);
        List<String> lines = new ArrayList<>();
        lines.add(Lang.get("filter.list-header",
                "mode", Lang.get(data.useAll ? "filter.mode-all" : "filter.mode-filter")));
        lines.add(Lang.get("filter.list-types",
                "include", join(data.includeTypes), "exclude", join(data.excludeTypes)));
        lines.add(Lang.get("filter.list-ids",
                "include", join(data.includeIds), "exclude", join(data.excludeIds)));
        lines.add(Lang.get("filter.usage"));
        return lines;
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? Lang.get("filter.none") : String.join(", ", values);
    }

    /** 单个玩家的筛选配置。 */
    static final class FilterData {

        /** 包含的类型（entity / player）。 */
        private final List<String> includeTypes = new ArrayList<>();
        /** 排除的类型（entity / player）。 */
        private final List<String> excludeTypes = new ArrayList<>();
        /** 包含的实体 ID。 */
        private final List<String> includeIds = new ArrayList<>();
        /** 排除的实体 ID。 */
        private final List<String> excludeIds = new ArrayList<>();
        /** true = 自由锁定（忽略筛选列表）；false = 仅锁定筛选列表内目标。 */
        private boolean useAll = true;

        private FilterData copy() {
            FilterData copied = new FilterData();
            copied.includeTypes.addAll(this.includeTypes);
            copied.excludeTypes.addAll(this.excludeTypes);
            copied.includeIds.addAll(this.includeIds);
            copied.excludeIds.addAll(this.excludeIds);
            copied.useAll = this.useAll;
            return copied;
        }

        private void clear() {
            this.includeTypes.clear();
            this.excludeTypes.clear();
            this.includeIds.clear();
            this.excludeIds.clear();
        }

        /** entity 与 player 同时存在时的错误语言 key，无冲突返回 {@code null}。 */
        private String conflict() {
            if (this.includeTypes.contains(TYPE_ENTITY) && this.includeTypes.contains(TYPE_PLAYER)) {
                return "filter.error-conflict";
            }
            if (this.excludeTypes.contains(TYPE_ENTITY) && this.excludeTypes.contains(TYPE_PLAYER)) {
                return "filter.error-conflict";
            }
            return null;
        }
    }
}
