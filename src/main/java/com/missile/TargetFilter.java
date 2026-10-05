package com.missile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 目标筛选：按玩家保存一份**白名单**，由 {@code /msl filter ...} 修改，可被存储层持久化。
 *
 * <p>数据模型（每玩家一份）：
 * <ul>
 *   <li>{@code enabled} —— 筛选模式开关。关闭 = 自由锁定（完全忽略筛选数据）。</li>
 *   <li>{@code entity} 类 —— {@code entityTypeIncluded} 表示"任意生物"，外加上一份实体 ID 白名单；
 *       命中白名单或（未列出且启用了 entity 类）即允许。</li>
 *   <li>{@code player} 类 —— {@code playerTypeIncluded} 表示允许玩家；玩家白名单为空 = 允许所有玩家，
 *       非空 = **只允许名单内玩家**。</li>
 * </ul>
 *
 * <p>命令语义（{@link #parse}）：
 * <pre>
 *   /msl filter entity [set|add|clear] [&lt;实体ID...&gt;]
 *   /msl filter player [set|add|clear] [&lt;玩家名...&gt;]
 *   /msl filter clear           清空全部筛选数据（不动 on/off 开关）
 *   /msl filter on | off        启用 / 停用筛选模式
 * </pre>
 * · {@code set} = 先清空**全部**筛选数据，再写入本次类型与 ID；{@code add} 与"直接跟 ID"都是追加。<br>
 * · {@code set}/{@code add} 之后的 ID 是**必选**的；缺失时该 {@code set}/{@code add} 整体无效（前缀容错）。<br>
 * · 只要本次成功写入了类型或 ID，就自动把筛选模式置为 {@code on}（否则命令看起来"没生效"）。
 *
 * <p>关于"任意实体"的边界：本类只接收 {@link LivingEntity}，所以载具、末地水晶、实体方块
 * 这类**非生物实体天然不可能进入锁定流程**（由 {@code TargetSelector#isValidKind} 保证）；
 * 需要"指定 minecraft:boat 就能锁载具"的话，得先把整条管线的目标类型从 LivingEntity 放宽到 Entity。
 */
public final class TargetFilter {

    /** 每个玩家的筛选数据（保持插入顺序，便于 list 输出稳定）。 */
    private static final Map<UUID, FilterData> playerFilters = new LinkedHashMap<>();

    private static final String TYPE_ENTITY = "entity";
    private static final String TYPE_PLAYER = "player";
    private static final String MODE_SET = "set";
    private static final String MODE_ADD = "add";
    private static final String MODE_CLEAR = "clear";
    private static final String MODE_ON = "on";
    private static final String MODE_OFF = "off";
    private static final String NAMESPACE_PREFIX = "minecraft:";

    /**
     * 服务端实体注册表里"玩家"这个实体的**完整键**（§2.2）：它不能当 {@code entity} 类的 ID，
     * 玩家请用 {@code player} 类。注意它与类名常量 {@link #TYPE_PLAYER}（{@code "player"}）不是一回事。
     */
    private static final String PLAYER_ENTITY_ID = NAMESPACE_PREFIX + TYPE_PLAYER;

    /** 全部合法实体 ID（已规范化），用于校验输入；UNKNOWN 等无法解析的条目会被跳过。 */
    private static final Set<String> VALID_IDS = collectValidIds();

    private TargetFilter() {
    }

    /** 由主类在 onEnable 调用：清空所有玩家的筛选数据。 */
    public static void reset() {
        playerFilters.clear();
    }

    /**
     * 收集全部合法实体 ID（**完整注册键**）。
     *
     * <p><b>加固（风险 #1）</b>：整个循环（含注册表本身）都包在 {@code try/catch (Throwable)} 里。
     * 注册表整体不可用时返回空集合，**绝不让静态初始化抛出**——代价只是实体 ID 校验暂时不认识任何 ID
     * （会打一条 warning），而不是整个插件崩掉。
     */
    private static Set<String> collectValidIds() {
        Set<String> ids = new HashSet<>();
        try {
            for (String key : registryKeys()) {
                String id = normalizeId(key);
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        } catch (Throwable throwable) {
            // 整个注册表都读不出来：放弃校验，但不毒化本类
            warn("读取实体注册表失败，实体 ID 校验本次不可用: " + throwable);
            ids.clear();
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * 服务端实体注册表里的全部键。
     *
     * <p>首选 Paper 的 {@code RegistryAccess#registryAccess().getRegistry(RegistryKey.ENTITY_TYPE)} ——
     * 它就是"服务端注册表"本身（§2.2 要求实体 ID 要有注册来源）。在没有服务端的离线环境里它会抛
     * {@code No RegistryAccess implementation found}，此时回退到 {@link EntityType#values()} +
     * {@link EntityType#getKey()}：两者给出的键集合一致（都形如 {@code minecraft:oak_boat}），
     * 所以回退**不会带来行为差异**，只是来源不同。
     *
     * <p>{@code EntityType.values()} 含 {@code UNKNOWN} 这类无键条目，其 {@code getKey()} 会抛异常，
     * 必须逐条捕获跳过。
     */
    private static List<String> registryKeys() {
        List<String> keys = new ArrayList<>();
        try {
            // 注意：这里刻意用全限定名 + 独立 try，避免注册表 API 在无服务端环境下把本类初始化打断
            io.papermc.paper.registry.RegistryAccess access =
                    io.papermc.paper.registry.RegistryAccess.registryAccess();
            for (EntityType type : access.getRegistry(io.papermc.paper.registry.RegistryKey.ENTITY_TYPE)) {
                keys.add(type.getKey().toString());
            }
            if (!keys.isEmpty()) {
                return keys;
            }
        } catch (Throwable throwable) {
            warn("无法从服务端注册表读取实体键，回退到 EntityType.values()：" + throwable);
        }
        for (EntityType type : EntityType.values()) {
            try {
                keys.add(type.getKey().toString());
            } catch (Throwable throwable) {
                // UNKNOWN 等无键条目：跳过
            }
        }
        return keys;
    }

    /**
     * 全部合法实体 ID（**完整注册键**，升序），供 Tab 补全使用。
     *
     * <p>与校验用的是同一个集合，所以"补全里能选到的"必定"填进去能用"。
     */
    public static List<String> registryIdList() {
        List<String> ids = new ArrayList<>(VALID_IDS);
        Collections.sort(ids);
        return ids;
    }

    /**
     * 只在有 Bukkit 服务端时写日志。
     *
     * <p>本类的静态初始化可能发生在没有服务端的环境（独立验证程序），
     * 而 {@code Bukkit.getLogger()} 内部要拿 Server，直接调用会在 catch 里再抛一次。
     */
    private static void warn(String message) {
        try {
            Bukkit.getLogger().warning(message);
        } catch (Throwable ignored) {
            // 没有服务端：日志没人看，忽略
        }
    }

    /**
     * 规范化实体 ID 为**完整注册 ID**（{@code minecraft:oak_boat}）。
     *
     * <p>§2.2：实体 ID 必须有注册来源，所以一律带命名空间；输入允许省略前缀的简写
     * （{@code oak_boat} → {@code minecraft:oak_boat}），非 {@code minecraft} 命名空间的键原样保留。
     * 空输入返回空串。
     */
    static String normalizeId(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        return value.indexOf(':') < 0 ? NAMESPACE_PREFIX + value : value;
    }

    /** 去掉命名空间的短 ID（{@code minecraft:oak_boat} → {@code oak_boat}），用于组别名匹配。 */
    static String bareId(String id) {
        return id != null && id.startsWith(NAMESPACE_PREFIX)
                ? id.substring(NAMESPACE_PREFIX.length()) : (id == null ? "" : id);
    }

    /**
     * 该输入是否是**服务端注册表里的合法实体键**（允许省略命名空间的简写）。
     *
     * <p>供命令层复用（全局 filter 与 SA 的 {@code entity <ID>} 用同一套校验）。
     */
    public static boolean isRegistryId(String raw) {
        String id = normalizeId(raw);
        return !id.isEmpty() && VALID_IDS.contains(id);
    }

    /**
     * 组别名展开（{@code boat} → 所有 {@code *_boat} / {@code *_raft}）。
     *
     * <p>与全局 filter 用的是**同一套实现**（§1.2：safilter 的 ID 写入前走同一套别名展开与规范化）。
     */
    public static List<String> expandAliasForCommand(String raw) {
        return expandAlias(normalizeId(raw));
    }

    /**
     * 常用**组别名** → 实际实体 ID 的展开。
     *
     * <p>存在的理由：1.21.11 已经没有 {@code EntityType.BOAT}——1.19 起船按木材拆成了
     * {@code OAK_BOAT} / {@code SPRUCE_BOAT} / … / {@code BAMBOO_RAFT}（还有对应的 chest 变体），
     * 而需求里举的例子正是 {@code minecraft:boat}。这里把 {@code boat} 当作"所有船"的组别名，
     * 写入白名单时展开成实际 ID。
     *
     * <p>展开方式是**按后缀动态匹配当前注册表**，所以以后 Mojang 加新木材也不用改代码。
     *
     * @return 展开出的实际 ID（已排序、去重）；不是组别名时返回空列表
     */
    private static List<String> expandAlias(String alias) {
        List<String> suffixes = switch (bareId(alias)) {
            case "boat", "boats" -> List.of("_boat", "_raft");
            case "minecart", "minecarts" -> List.of("minecart");
            default -> List.of();
        };
        if (suffixes.isEmpty()) {
            return List.of();
        }
        // 直接在"合法注册键"集合上展开：结果必然落在服务端注册表的键上（§2.2）
        List<String> result = new ArrayList<>();
        for (String id : VALID_IDS) {
            for (String suffix : suffixes) {
                if (id.endsWith(suffix)) {
                    result.add(id);
                    break;
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    /** 目标实体的规范化 ID，如 {@code minecraft:zombie}；无法解析（UNKNOWN / 无类型）时返回空串。 */
    static String idOf(Entity target) {
        if (target == null) {
            return "";
        }
        try {
            EntityType type = target.getType();
            return type == null ? "" : normalizeId(type.getKey().getKey());
        } catch (Throwable throwable) {
            // UNKNOWN 这类无键条目、以及代理/异常实体：一律按"认不出来"处理，
            // 不让一次 ID 解析把整条制导链打断
            return "";
        }
    }

    /**
     * 目标是否被白名单**显式列出**（按实体类型 ID 判定）。
     *
     * <p>用途（需求 3.9）：非生物实体——载具、末地水晶、盔甲架等——**默认不可锁定**
     * （基线是"友好 / 中立 / 敌对生物"），但白名单里**明确写了它的 ID** 就无视该限制。
     *
     * <p>与 {@link #canTarget} / {@link #isWhitelisted} 的区别：**只看 {@code entityIds}**，
     * 不看 {@code entityTypeIncluded}——所以"任意生物"不会顺带把载具也放进来。
     *
     * @param ignoreFilterMode {@code true} = 无视筛选模式开关（{@code usefilter} 走这条，
     *                         因为那时白名单本身就是候选集合）；{@code false} = 只在筛选模式下生效
     */
    public static boolean explicitlyWhitelisted(UUID playerId, Entity target, boolean ignoreFilterMode) {
        if (playerId == null || target == null) {
            return false;
        }
        FilterData data = playerFilters.get(playerId);
        if (data == null || (!ignoreFilterMode && !data.enabled)) {
            return false;
        }
        String id = idOf(target);
        return !id.isEmpty() && data.entityIds.contains(id);
    }

    /** 按筛选模式判定是否被显式列出（{@code ignoreFilterMode = false}）。 */
    public static boolean explicitlyWhitelisted(UUID playerId, Entity target) {
        return explicitlyWhitelisted(playerId, target, false);
    }

    /** 取该玩家的筛选数据，不存在则创建（仅命令与存储路径调用，热路径用 {@link #canTarget}）。 */
    static FilterData dataOf(UUID playerId) {
        return playerFilters.computeIfAbsent(playerId, key -> new FilterData());
    }

    /** 只读快照，供存储层持久化。 */
    static Map<UUID, FilterData> snapshot() {
        return Collections.unmodifiableMap(playerFilters);
    }

    /** 存储层回填用：整体替换某个玩家的数据。 */
    static void restore(UUID playerId, FilterData data) {
        if (playerId != null && data != null) {
            playerFilters.put(playerId, data);
        }
    }

    /**
     * 目标是否允许被该玩家锁定（{@code TargetSelector} 的开导引头锁定与弹上重截获两个入口都会调用）。
     *
     * <p>热路径：不做任何对象创建，只读一次 map。
     */
    public static boolean canTarget(UUID playerId, LivingEntity target) {
        if (playerId == null || target == null) {
            return true;
        }
        FilterData data = playerFilters.get(playerId);
        if (data == null || !data.enabled) {
            return true;                              // 无筛选数据 / 未开启筛选模式 → 自由锁定
        }
        return data.allows(target);
    }

    /**
     * {@code usefilter} 用：目标是否命中该玩家的**白名单**。
     *
     * <p>与 {@link #canTarget} 的关键区别是**无视 {@code enabled} 开关**：
     * {@code /msl ir ... usefilter} 的语义是"能锁谁完全由白名单决定"，
     * 所以即使筛选模式处于 {@code off}（自由锁定），也要按白名单判定。
     *
     * <p>该玩家没有任何筛选数据时返回 {@code false}：空白名单 = 谁都不能锁，
     * 与"完全按照 {@code /msl filter list} 里的目标"一致（对应风险 #7 的同类语义）。
     */
    public static boolean isWhitelisted(UUID playerId, LivingEntity target) {
        if (playerId == null || target == null) {
            return false;
        }
        FilterData data = playerFilters.get(playerId);
        return data != null && data.allows(target);
    }

    /**
     * 该玩家筛选数据里启用的目标分类（**只看内容，不看 {@code enabled} 开关**）。
     *
     * <p>用途：ActionBar 的"目标"与占位符 {@code %msl_target_kind%} 需要按筛选内容判断
     * "现在允许锁的是玩家还是实体"，需求明确写的是"按 filter 内容判断"。
     */
    public enum TargetClasses {

        /** 没有任何筛选数据（既没启用玩家类也没启用实体类）。 */
        NONE,
        /** 只启用了玩家类。 */
        PLAYER,
        /** 只启用了实体类（非玩家生物）。 */
        ENTITY,
        /** 两类都启用。 */
        BOTH
    }

    /**
     * 该玩家的**筛选模式是否开启**（{@code /msl filter on|off}）。
     *
     * <p>用途：筛选模式打开时，"能锁哪一类目标"以白名单启用的类别为准，
     * 而不是导弹自己的 {@code TargetKind}——否则会出现"玩家被白名单挡、生物被类型挡，
     * 交集为空、什么都锁不上"（用户 2026-10-05 报的问题：{@code /msl filter entity add phantom}
     * 之后连幻翼都锁不上）。
     */
    public static boolean isEnabled(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        FilterData data = playerFilters.get(playerId);
        return data != null && data.enabled;
    }

    /**
     * 读取该玩家的目标分类。
     *
     * <p>热路径只读一次 map，不做对象创建以外的工作。
     */
    public static TargetClasses targetClasses(UUID playerId) {
        if (playerId == null) {
            return TargetClasses.NONE;
        }
        FilterData data = playerFilters.get(playerId);
        if (data == null) {
            return TargetClasses.NONE;
        }
        if (data.playerTypeIncluded && data.entityTypeIncluded) {
            return TargetClasses.BOTH;
        }
        if (data.playerTypeIncluded) {
            return TargetClasses.PLAYER;
        }
        if (data.entityTypeIncluded) {
            return TargetClasses.ENTITY;
        }
        return TargetClasses.NONE;
    }

    /**
     * 把目标分类翻译成「目标:」文案（§2.1 / 决策 #33）：**混合 / 任意 / 实体 / 玩家**。
     *
     * <p>调用方自己决定传哪一类：ActionBar 传的是 {@code TargetSelector#activeClasses}
     * （**实际生效**的类别）；{@link TargetClasses#NONE} 时由调用方决定显示"任意"还是按型号类型。
     *
     * @param classes 已经折算好的类别
     * @param none    类别为 {@code NONE} 时用它（通常是"任意"或型号当前锁定类型）
     */
    public static String labelOf(TargetClasses classes, String none) {
        return switch (classes) {
            case PLAYER -> Lang.get("target.player");
            case ENTITY -> Lang.get("target.entity");
            case BOTH -> Lang.get("target.mixed");
            case NONE -> none;
        };
    }

    /**
     * 解析并应用 {@code /msl filter ...} 参数。
     *
     * <p><b>前缀容错</b>：遇到第一个非法参数即停止解析，**保留此之前已生效的部分**。
     *
     * @return 需要发给玩家的本地化提示
     */
    public static List<String> parse(UUID playerId, String[] args) {
        List<String> messages = new ArrayList<>();
        if (playerId == null || args == null || args.length == 0) {
            messages.add(Lang.get("filter.usage"));
            return messages;
        }
        FilterData data = dataOf(playerId);
        String head = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);

        if (MODE_ON.equals(head) || MODE_OFF.equals(head)) {
            if (MODE_ON.equals(head) && data.isEmpty()) {
                // 空白名单 + 筛选模式 = 谁都锁不上（风险 #7 的坑）。这里**直接不开**并说明原因，
                // 让"enabled = true ⟹ 白名单非空"成为一个恒成立的不变量。
                messages.add(Lang.get("filter.error-empty-whitelist"));
                return messages;
            }
            data.enabled = MODE_ON.equals(head);
            messages.add(Lang.get("filter.applied", "mode",
                    Lang.get(data.enabled ? "filter.mode-filter" : "filter.mode-all")));
            return messages;
        }
        if (MODE_CLEAR.equals(head)) {
            data.clearAll();
            messages.add(Lang.get("filter.cleared-all"));
            messages.addAll(autoDisableWhenEmpty(data));
            return messages;
        }
        if (!TYPE_ENTITY.equals(head) && !TYPE_PLAYER.equals(head)) {
            messages.add(Lang.get("filter.usage"));
            return messages;
        }

        boolean entity = TYPE_ENTITY.equals(head);
        String second = args.length > 1 && args[1] != null
                ? args[1].trim().toLowerCase(Locale.ROOT) : null;
        if (MODE_CLEAR.equals(second)) {
            if (entity) {
                data.clearEntity();
                messages.add(Lang.get("filter.cleared-entity"));
            } else {
                data.clearPlayer();
                messages.add(Lang.get("filter.cleared-player"));
            }
            messages.addAll(autoDisableWhenEmpty(data));
            return messages;
        }

        boolean replace = MODE_SET.equals(second);
        boolean append = MODE_ADD.equals(second);
        int firstValue = replace || append ? 2 : 1;
        if ((replace || append) && args.length <= firstValue) {
            // set / add 的必选 ID 缺失 → 该 set / add 无效：不清空、不写入（前缀容错）
            messages.add(Lang.get("filter.error-need-value", "mode", second));
            return messages;
        }
        if (replace) {
            data.clearAll();                          // set = 先清空全部筛选数据
        }
        if (entity) {
            data.entityTypeIncluded = true;
        } else {
            data.playerTypeIncluded = true;
        }

        for (int index = firstValue; index < args.length; index++) {
            String token = args[index] == null ? "" : args[index].trim();
            if (token.isEmpty()) {
                continue;
            }
            if (entity) {
                String id = normalizeId(token);
                if (PLAYER_ENTITY_ID.equals(id)) {
                    // §2.2 / §1.2：`minecraft:player` 不能当实体 ID，玩家请用 player 类
                    messages.add(Lang.get("filter.error-player-as-entity"));
                    break;
                }
                if (VALID_IDS.contains(id)) {
                    data.entityIds.add(id);
                    continue;
                }
                // 不是单个实体 ID：试试"组别名"（如 boat → 所有 *_boat / *_raft）
                List<String> expanded = expandAlias(id);
                if (expanded.isEmpty()) {
                    messages.add(Lang.get("filter.unknown-id", "input", token));
                    break;
                }
                data.entityIds.addAll(expanded);
                messages.add(Lang.get("filter.alias-expanded",
                        "input", token, "count", expanded.size()));
            } else if (!addPlayer(data, token)) {
                messages.add(Lang.get("filter.unknown-player", "input", token));
                break;
            }
        }

        // 写入即启用筛选模式，避免"命令看起来没生效"；但如果这一轮**什么都没写进去**
        // （例如 `set` 后面跟的 ID 全是未知的：先 clearAll 再报错 → 数据变空），
        // 就必须保持自由锁定，否则又会掉进"on + 空名单 = 什么都锁不上"的坑。
        // 不变量：enabled = true ⟹ 白名单非空
        data.enabled = !data.isEmpty();
        Collection<String> values = entity ? data.entityIds : data.playerNames;
        String shown = values.isEmpty()
                ? Lang.get(entity ? "filter.values-any-entity" : "filter.values-all-players")
                : join(values);
        messages.add(Lang.get("filter.applied-type", "type", head, "values", shown));
        return messages;
    }

    /** 当前筛选的文字描述（{@code /msl filter list}，只读）。 */
    public static List<String> describe(UUID playerId) {
        FilterData data = dataOf(playerId);
        List<String> lines = new ArrayList<>();
        lines.add(Lang.get("filter.list-header",
                "mode", Lang.get(data.enabled ? "filter.mode-filter" : "filter.mode-all")));

        List<String> types = new ArrayList<>();
        if (data.entityTypeIncluded) {
            types.add(TYPE_ENTITY);
        }
        if (data.playerTypeIncluded) {
            types.add(TYPE_PLAYER);
        }
        lines.add(Lang.get("filter.list-types",
                "types", types.isEmpty() ? Lang.get("filter.none") : join(types)));
        lines.add(Lang.get("filter.list-ids",
                "ids", data.entityIds.isEmpty() ? Lang.get("filter.none") : join(data.entityIds)));
        lines.add(Lang.get("filter.list-players",
                "players", data.playerNames.isEmpty() ? Lang.get("filter.none") : join(data.playerNames)));
        lines.add(Lang.get("filter.usage"));
        return lines;
    }

    /**
     * 清空类操作之后的收尾：如果白名单被清空而模式还开着，就把模式关回 {@code off} 并提示。
     *
     * <p>理由：{@code on} + 空白名单 = 任何目标都锁不上（风险 #7），与其让玩家对着"锁不上"发呆，
     * 不如自动退出筛选模式并明确告知。这也是维持"enabled = true ⟹ 白名单非空"的一环。
     */
    private static List<String> autoDisableWhenEmpty(FilterData data) {
        if (!data.enabled || !data.isEmpty()) {
            return List.of();
        }
        data.enabled = false;
        return List.of(Lang.get("filter.auto-off-empty"));
    }

    /**
     * 把玩家名写入白名单。
     *
     * <p>参数来源是"服务器上的玩家"，所以只接受**当前在线**的名字（与 Tab 补全一致）；
     * 命中后同时记录 UUID，之后该玩家改名/离线仍能匹配。
     *
     * @return 找不到该玩家时返回 {@code false}（调用方按前缀容错停止）
     */
    private static boolean addPlayer(FilterData data, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            return false;
        }
        data.playerIds.add(online.getUniqueId());
        data.playerNames.add(online.getName().toLowerCase(Locale.ROOT));
        return true;
    }

    private static String join(Collection<String> values) {
        return values.isEmpty() ? Lang.get("filter.none") : String.join(", ", values);
    }

    /**
     * 单个玩家的筛选数据。
     *
     * <p>字段为**包内可见**：接下来加入的存储层与本类同包，直接读写可省掉一堆访问器
     * （也避免再次踩到"嵌套类 private 成员外部不可见"的编译坑）。
     */
    static final class FilterData {

        /** 是否处于筛选模式（false = 自由锁定，完全忽略下面的数据）。 */
        boolean enabled;
        /** 是否允许"任意生物"（未在 {@link #entityIds} 中列出的生物）。 */
        boolean entityTypeIncluded;
        /** 实体 ID 白名单（已规范化，如 {@code zombie}）。 */
        final Set<String> entityIds = new LinkedHashSet<>();
        /** 是否允许玩家类。 */
        boolean playerTypeIncluded;
        /** 玩家白名单（UUID 主键）。 */
        final Set<UUID> playerIds = new LinkedHashSet<>();
        /** 玩家白名单（名字兜底，小写）。 */
        final Set<String> playerNames = new LinkedHashSet<>();

        /** 白名单判定。 */
        private boolean allows(LivingEntity target) {            if (target instanceof Player player) {
                if (this.playerIds.isEmpty() && this.playerNames.isEmpty()) {
                    return this.playerTypeIncluded;    // 空名单 = 允许所有玩家（未启用 player 类则不允许）
                }
                return this.playerIds.contains(player.getUniqueId())
                        || this.playerNames.contains(player.getName().toLowerCase(Locale.ROOT));
            }
            String id = idOf(target);
            if (!id.isEmpty() && this.entityIds.contains(id)) {
                return true;                           // 显式列入白名单
            }
            return this.entityTypeIncluded;            // 未列出的生物按"任意实体"处理
        }

        /**
         * 是否**完全没有**任何筛选数据（既没启用玩家类也没启用实体类，且两份名单都空）。
         *
         * <p>这个状态下若把筛选模式打开，就会变成"什么都锁不上"（风险 #7）。
         * 因此 {@code /msl filter on} 会拒绝开启，清空类操作也会顺手把模式关回 off，
         * 从而保证"enabled = true ⟹ 白名单非空"。
         */
        boolean isEmpty() {
            return !this.playerTypeIncluded && !this.entityTypeIncluded
                    && this.playerIds.isEmpty() && this.playerNames.isEmpty()
                    && this.entityIds.isEmpty();
        }

        /** 清空全部筛选数据（不改变 {@link #enabled}，开关由 on / off 单独控制）。 */
        private void clearAll() {
            clearEntity();
            clearPlayer();
        }

        private void clearEntity() {
            this.entityTypeIncluded = false;
            this.entityIds.clear();
        }

        private void clearPlayer() {
            this.playerTypeIncluded = false;
            this.playerIds.clear();
            this.playerNames.clear();
        }
    }
}
