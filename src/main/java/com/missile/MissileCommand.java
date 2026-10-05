package com.missile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/**
 * 命令实现（{@code /missile}，别名 {@code /msl}）。
 *
 * <p>权限分级（plugin.yml 里**故意不给命令声明 permission**，改由本类按子命令校验，
 * 否则只带 {@code missile.admin} 的管理员会被 {@code missile.use} 挡在外面）：
 * <ul>
 *   <li>{@code missile.use} —— {@code /msl <型号>}、{@code /msl filter ...}、
 *       {@code /msl on|off}、{@code /msl default}、{@code /msl status}</li>
 *   <li>{@code missile.admin} —— {@code /msl super_active [参数]}、{@code /msl global on|off}、
 *       {@code /msl reload}</li>
 * </ul>
 *
 * <p><b>参数容错</b>：从左往右解析，遇到第一个非法参数即停止，**只执行非法参数之前的部分**。
 * 例：{@code /msl super_active player 114514} 等价于 {@code /msl super_active player}。
 * 被忽略的部分不会额外提示（避免刷屏）。
 */
public final class MissileCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_USE = "missile.use";
    private static final String PERMISSION_ADMIN = "missile.admin";

    private static final List<String> TYPE_ARGS = List.of("ir", "semi", "active", "semiLOS");
    private static final List<String> IR_MODES = List.of("default", "player", "entity");
    /** 跟在 {@code /msl ir [<模式>]} 之后的白名单通道开关。 */
    private static final List<String> IR_FILTER_ARGS = List.of("usefilter", "filteroff");
    private static final List<String> SA_MODES = List.of("default", "entity", "player", "filter");
    /** {@code /msl super_active filter} 的必选参数（§1.1）。 */
    private static final List<String> SA_FILTER_ARGS = List.of("list", "clear");
    /**
     * {@code /msl super_active <entity|player>} 之后用于**增删 safilter 条目**的动词（1.0.3 新增）。
     *
     * <p>语义按"针对这一类条目"理解：{@code set} 覆盖该类、{@code add} 追加、{@code clear} 清空该类、
     * {@code remove} 摘掉列出的条目；顺带把 SA 模式切到对应的 {@code entity} / {@code player}。
     */
    private static final List<String> SA_LIST_MODES = List.of("set", "add", "remove", "clear");
    private static final List<String> FILTER_HEADS = List.of("entity", "player", "clear", "on", "off", "list");
    /** {@code /msl filter <entity|player>} 之后的动词（1.0.3 起含 {@code remove}）。 */
    private static final List<String> FILTER_MODES = List.of("set", "add", "remove", "clear");
    private static final List<String> SWITCHES = List.of("on", "off");

    /** {@code minecraft:player}：不能当实体 ID（玩家走 player 类）。 */
    private static final String PLAYER_ENTITY_ID = "minecraft:player";
    private static final String TYPE_ENTITY = "entity";
    private static final String TYPE_PLAYER = "player";

    /** 实体 ID 补全候选（懒加载；跳过 UNKNOWN 这类无 key 条目）。 */
    private static List<String> entityIds;

    private final MissilePlugin plugin;

    public MissileCommand(MissilePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Lang.msg("command.player-only"));
            return true;
        }
        boolean canUse = player.hasPermission(PERMISSION_USE);
        boolean canAdmin = player.hasPermission(PERMISSION_ADMIN);
        if (!canUse && !canAdmin) {
            player.sendMessage(Lang.msg("command.no-permission"));
            return true;
        }
        SeekerListener.SeekerState state = this.plugin.seekers().state(player);

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            this.sendStatus(player, state);
            return true;
        }
        String head = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);

        switch (head) {
            case "global" -> {
                if (this.requireAdmin(player, canAdmin)) {
                    this.global(player, args);
                }
                return true;
            }
            case "reload" -> {
                if (this.requireAdmin(player, canAdmin)) {
                    this.reload(player);
                }
                return true;
            }
            case "on", "off" -> {
                if (!this.requireUse(player, canUse)) {
                    return true;
                }
                boolean enable = "on".equals(head);
                MissilePlugin.setPlayerEnabled(player.getUniqueId(), enable);
                player.sendMessage(Lang.msg(enable ? "command.enabled" : "command.disabled"));
                return true;
            }
            case "default" -> {
                // 1.0.3：/msl default —— 把该玩家的全部导弹设置恢复出厂值（权限 missile.use）
                if (this.requireUse(player, canUse)) {
                    this.resetDefaults(player, state);
                }
                return true;
            }
            case "filter" -> {
                if (this.requireUse(player, canUse)) {
                    this.filter(player, args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
                }
                return true;
            }
            default -> {
                // 落到下面的型号解析
            }
        }

        MissileType type = MissileType.parse(head);
        if (type == null) {
            player.sendMessage(Lang.msg("command.unknown-missile", "input", args[0]));
            return true;
        }
        if (type == MissileType.SUPER_ACTIVE) {
            if (!this.requireAdmin(player, canAdmin)) {
                return true;
            }
        } else if (!this.requireUse(player, canUse)) {
            return true;
        }

        state.type(type);
        // 换型号后立刻按"新型号实际生效的锁定类型"重算一次锁定（IR 模式 / SA 类型都可能改变它）
        state.updateLock(state.selectLock(player));
        player.sendMessage(this.typeMessage(type));
        this.applyTypeArgs(player, state, type, args);
        return true;
    }

    /** {@code /msl filter ...}：转发给 {@link TargetFilter}；{@code list} 为只读查看。 */
    private void filter(Player player, String[] filterArgs) {
        if (filterArgs.length > 0 && filterArgs[0].equalsIgnoreCase("list")) {
            for (String line : TargetFilter.describe(player.getUniqueId())) {
                player.sendMessage(line);
            }
            return;
        }
        for (String message : TargetFilter.parse(player.getUniqueId(), filterArgs)) {
            player.sendMessage(message);
        }
    }

    /**
     * {@code /msl default}（1.0.3 新增，权限 {@code missile.use}）：把该玩家的**全部导弹设置**
     * 恢复出厂值。
     *
     * <p>条目明确排除了两样东西，这里一概不动：
     * <ul>
     *   <li>玩家选的**型号**（{@code /msl <型号>}）——它是"当前选择"，不是设置；</li>
     *   <li>{@code /msl on|off} **个人开关**——单独一条命令管理。</li>
     * </ul>
     *
     * <p>其余全部重置：IR 工作模式与 usefilter、SA 模式与 safilter、导引头（关闭并清空锁定）、
     * 以及**全局目标筛选白名单**（连同 {@code on/off} 模式；数据层的脏检查会把它同步落盘，
     * 所以重启后仍是"出厂状态"）。
     */
    private void resetDefaults(Player player, SeekerListener.SeekerState state) {
        state.resetToDefaults();
        TargetFilter.resetPlayer(player.getUniqueId());
        player.sendMessage(Lang.msg("command.default-reset"));
    }

    /** {@code /msl global <on|off>}：全服开关，写内存（玩家个人设置不受影响）。 */
    private void global(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Lang.get("command.usage-brief"));
            return;
        }
        String value = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
        if (!SWITCHES.contains(value)) {
            player.sendMessage(Lang.get("command.usage-brief"));   // 非法 → 只执行到 /msl global
            return;
        }
        boolean enable = "on".equals(value);
        MissilePlugin.setGlobalEnabled(enable);
        this.plugin.persistGlobalSwitch(enable);      // 写回 msl_config.yml（保留注释；可用配置关掉）
        player.sendMessage(Lang.msg(enable ? "command.global-on" : "command.global-off"));
    }

    /** {@code /msl reload}：重读配置/语言并落盘筛选数据。 */
    private void reload(Player player) {
        if (this.plugin.reloadPluginSettings()) {
            player.sendMessage(Lang.get("command.reloaded"));      // 文案自带前缀
        }
    }

    /**
     * 型号专属参数（前缀容错）：
     * <ul>
     *   <li>{@code ir [default|player|entity] [usefilter|filteroff]} —— 缺省不改动</li>
     *   <li>{@code super_active [default|entity|player] [set|add|remove|clear] [值...]} —— 1.0.3 扩参</li>
     *   <li>{@code semi} / {@code active} / {@code semiLOS} —— 无参数（多余参数忽略）</li>
     * </ul>
     */
    private void applyTypeArgs(Player player, SeekerListener.SeekerState state, MissileType type, String[] args) {
        if (args.length < 2) {
            return;
        }
        String first = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case INFRARED -> {
                int index = 1;
                if (IR_MODES.contains(first)) {
                    SeekerListener.SeekerState.IrMode mode = switch (first) {
                        case "entity" -> SeekerListener.SeekerState.IrMode.ENTITY;
                        case "player" -> SeekerListener.SeekerState.IrMode.PLAYER;
                        default -> SeekerListener.SeekerState.IrMode.DEFAULT;
                    };
                    state.irMode(mode);
                    player.sendMessage(Lang.msg(switch (first) {
                        case "entity" -> "command.ir-mode-entity";
                        case "player" -> "command.ir-mode-player";
                        default -> "command.ir-mode-default";
                    }));
                    index = 2;
                }
                // usefilter / filteroff 允许紧跟在型号后（省略模式）或跟在模式后。
                // 只写模式**不会**动白名单通道：要关就显式写 filteroff（用户 2026-10-05 要求），
                // 所以 /msl ir <模式> 只改模式、/msl ir（无参数）什么都不改。
                if (args.length > index && args[index] != null) {
                    String option = args[index].trim().toLowerCase(Locale.ROOT);
                    if ("usefilter".equals(option)) {
                        state.irUseFilter(true);
                        player.sendMessage(Lang.msg("command.ir-usefilter-on"));
                    } else if ("filteroff".equals(option)) {
                        state.irUseFilter(false);
                        player.sendMessage(Lang.msg("command.ir-usefilter-off"));
                    }
                }
            }
            case SUPER_ACTIVE -> {
                if (!SA_MODES.contains(first)) {
                    return;                                    // 非法 → 忽略（前缀容错）
                }
                // §1.1 的可选参数（entity/player 后面的动词与 ID、filter 后面的 list|clear）
                String[] rest = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[0];
                switch (first) {
                    case "entity" -> this.applySaEntity(player, state, rest);
                    case "player" -> this.applySaPlayer(player, state, rest);
                    case "filter" -> this.applySaFilter(player, state, rest.length > 0 ? rest[0] : null);
                    default -> {
                        // default = 真正的任意目标 + 清空 safilter（决策 #27）
                        state.saReset();
                        player.sendMessage(Lang.msg("command.sa-default"));
                    }
                }
            }
            default -> {
                // semi / active / semiLOS：不支持额外参数，全部按容错规则忽略
            }
        }
    }

    /**
     * {@code /msl super_active entity [<set|add|remove|clear>] [<完整注册ID...>]}（§1.1 / §1.2，1.0.3 扩参）。
     *
     * <p>不带任何参数 = 除玩家外的任意实体（**实体优先、不做玩家优先两段式**，拍板④），并清空 safilter；
     * 带 ID = 只锁这些实体，ID 走与全局 filter **同一套**规范化 + 组别名展开，且拒绝 {@code minecraft:player}。
     *
     * <p>动词（1.0.3 新增）：{@code set} 覆盖实体类 · {@code add} 追加 · {@code remove} 摘掉 ·
     * {@code clear} 清空实体类；**裸 ID（不写动词）= 追加**，与 1.0.2 的 {@code entity <ID>} 完全兼容。
     * 无论哪种写法都会把 SA 模式切到 {@code entity}。
     *
     * <p>前缀容错：{@code entity} 本身合法，所以即使后面的 ID 非法，**模式照旧生效**，
     * 且只有非法参数**之前**收集到的条目会被写入。
     */
    private void applySaEntity(Player player, SeekerListener.SeekerState state, String[] rest) {
        state.saMode(SaProfile.Mode.ENTITY);
        String verb = rest.length > 0 && rest[0] != null ? rest[0].trim().toLowerCase(Locale.ROOT) : "";
        if (verb.isEmpty()) {
            state.clearSaFilter();
            player.sendMessage(Lang.msg("command.sa-entity"));
            return;
        }
        if (SA_LIST_MODES.contains(verb)) {
            this.applySaEntityList(player, state, verb, Arrays.copyOfRange(rest, 1, rest.length));
            return;
        }
        this.applySaEntityList(player, state, "add", rest);          // 裸 ID = 追加（向后兼容）
    }

    /**
     * {@code /msl super_active player [<set|add|remove|clear>] [<在线玩家名...>]}（1.0.3 扩参）。
     *
     * <p>不带任何参数 = 除**自己**外的任意玩家，并清空 safilter；带名字 = 只锁这些玩家
     * （{@code set}/{@code add} 只接受服务器上**在线**的玩家：解析 UUID + 名字兜底）。
     * {@code remove} 额外支持**离线玩家**——名字本来就在 safilter 里，按名单摘即可。
     */
    private void applySaPlayer(Player player, SeekerListener.SeekerState state, String[] rest) {
        state.saMode(SaProfile.Mode.PLAYER);
        String verb = rest.length > 0 && rest[0] != null ? rest[0].trim().toLowerCase(Locale.ROOT) : "";
        if (verb.isEmpty()) {
            state.clearSaFilter();
            player.sendMessage(Lang.msg("command.sa-player"));
            return;
        }
        if (SA_LIST_MODES.contains(verb)) {
            this.applySaPlayerList(player, state, verb, Arrays.copyOfRange(rest, 1, rest.length));
            return;
        }
        this.applySaPlayerList(player, state, "add", rest);          // 裸名字 = 追加（向后兼容）
    }

    /**
     * safilter 实体类的 {@code set|add|remove|clear}（1.0.3）。
     *
     * <p>单个条目的解析沿用 1.0.2 的规则（规范化 → 注册表校验 → 组别名展开 → 拒绝
     * {@code minecraft:player}），非法条目按前缀容错**停止收集**，之前收集到的照常写入。
     */
    private void applySaEntityList(Player player, SeekerListener.SeekerState state, String verb, String[] values) {
        if ("clear".equals(verb)) {
            state.clearSaEntityIds();
            player.sendMessage(Lang.msg("command.sa-list-cleared", "type", TYPE_ENTITY));
            return;
        }
        if (values.length == 0) {
            player.sendMessage(Lang.msg("command.sa-list-usage"));
            return;
        }
        List<String> resolved = new ArrayList<>();
        for (String raw : values) {
            String token = raw == null ? "" : raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            String id = TargetFilter.normalizeId(token);
            if (PLAYER_ENTITY_ID.equals(id)) {
                player.sendMessage(Lang.msg("filter.error-player-as-entity"));
                break;
            }
            if (TargetFilter.isRegistryId(id)) {
                resolved.add(id);
                continue;
            }
            List<String> expanded = TargetFilter.expandAliasForCommand(id);
            if (expanded.isEmpty()) {
                player.sendMessage(Lang.msg("filter.unknown-id", "input", token));
                break;
            }
            resolved.addAll(expanded);
            player.sendMessage(Lang.msg("command.sa-filter-alias", "input", token, "count", expanded.size()));
        }
        if (resolved.isEmpty()) {
            return;                                   // 一条合法条目都没有：不改名单
        }
        switch (verb) {
            case "set" -> {
                state.clearSaEntityIds();
                resolved.forEach(state::addSaEntityId);
                player.sendMessage(Lang.msg("command.sa-list-set",
                        "type", TYPE_ENTITY, "values", join(resolved)));
            }
            case "add" -> {
                resolved.forEach(state::addSaEntityId);
                player.sendMessage(Lang.msg("command.sa-list-added",
                        "type", TYPE_ENTITY, "values", join(resolved)));
            }
            default -> {
                List<String> removed = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (String id : resolved) {
                    if (state.removeSaEntityId(id)) {
                        removed.add(id);
                    } else {
                        missing.add(id);
                    }
                }
                player.sendMessage(Lang.msg(removed.isEmpty() ? "command.sa-list-none" : "command.sa-list-removed",
                        "type", TYPE_ENTITY, "values", join(removed.isEmpty() ? missing : removed)));
                if (!removed.isEmpty() && !missing.isEmpty()) {
                    player.sendMessage(Lang.msg("command.sa-list-missing", "values", join(missing)));
                }
            }
        }
    }

    /**
     * safilter 玩家类的 {@code set|add|remove|clear}（1.0.3）。
     *
     * <p>{@code set}/{@code add} 只接受**在线**玩家名（与全局 filter 的 player 类一致）；
     * {@code remove} 允许离线名字（它本来就在名单里），按"名字 → UUID"索引把两者一起摘掉。
     */
    private void applySaPlayerList(Player player, SeekerListener.SeekerState state, String verb, String[] values) {
        if ("clear".equals(verb)) {
            state.clearSaPlayers();
            player.sendMessage(Lang.msg("command.sa-list-cleared", "type", TYPE_PLAYER));
            return;
        }
        if (values.length == 0) {
            player.sendMessage(Lang.msg("command.sa-list-usage"));
            return;
        }
        boolean removing = "remove".equals(verb);
        // 两份平行列表（而不是把在线/离线混在一起）：离线条目的 UUID 为 null，
        // 否则"离线名字 + 在线名字"混排时会把 UUID 配错人。
        List<String> names = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (String raw : values) {
            String token = raw == null ? "" : raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            Player online = this.plugin.getServer().getPlayerExact(token);
            if (online != null) {
                names.add(online.getName());
                ids.add(online.getUniqueId());
                continue;
            }
            if (removing) {
                names.add(token);                     // 离线也能从名单里摘掉（名字本来就存着）
                ids.add(null);
                continue;
            }
            player.sendMessage(Lang.msg("filter.unknown-player", "input", token));
            break;                                    // 前缀容错：之前的条目照常写入
        }
        if (names.isEmpty()) {
            return;
        }
        switch (verb) {
            case "set" -> {
                state.clearSaPlayers();
                addSaPlayers(state, names, ids);
                player.sendMessage(Lang.msg("command.sa-list-set",
                        "type", TYPE_PLAYER, "values", join(names)));
            }
            case "add" -> {
                addSaPlayers(state, names, ids);
                player.sendMessage(Lang.msg("command.sa-list-added",
                        "type", TYPE_PLAYER, "values", join(names)));
            }
            default -> {
                List<String> removed = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (int index = 0; index < names.size(); index++) {
                    if (state.removeSaPlayer(names.get(index), ids.get(index))) {
                        removed.add(names.get(index));
                    } else {
                        missing.add(names.get(index));
                    }
                }
                player.sendMessage(Lang.msg(removed.isEmpty() ? "command.sa-list-none" : "command.sa-list-removed",
                        "type", TYPE_PLAYER, "values", join(removed.isEmpty() ? missing : removed)));
                if (!removed.isEmpty() && !missing.isEmpty()) {
                    player.sendMessage(Lang.msg("command.sa-list-missing", "values", join(missing)));
                }
            }
        }
    }

    /** 把（名字, UUID）逐对写进 safilter；UUID 为 null 的条目不写入（set/add 不会出现）。 */
    private static void addSaPlayers(SeekerListener.SeekerState state, List<String> names, List<UUID> ids) {
        for (int index = 0; index < names.size(); index++) {
            UUID id = ids.get(index);
            if (id != null) {
                state.addSaPlayer(id, names.get(index));
            }
        }
    }

    /** 逗号连接条目；空列表显示"（无）"（复用 filter 的文案，保持两处口径一致）。 */
    private static String join(List<String> values) {
        return values.isEmpty() ? Lang.get("filter.none") : String.join(", ", values);
    }

    /**
     * {@code /msl super_active filter <list|clear>}：查看 / 清空 safilter（§1.1）。
     *
     * <p>{@code clear} 会**清空 safilter 并自动退回 default**（任意目标）；缺必选参数时只报用法、不改状态。
     */
    private void applySaFilter(Player player, SeekerListener.SeekerState state, String raw) {
        String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!SA_FILTER_ARGS.contains(mode)) {
            player.sendMessage(Lang.msg("command.sa-filter-usage"));
            return;
        }
        if ("clear".equals(mode)) {
            state.saReset();
            player.sendMessage(Lang.msg("command.sa-filter-cleared"));
            return;
        }
        this.sendSaFilter(player, state);
    }

    /**
     * 打印该玩家 safilter 的当前内容（{@code /msl super_active filter list} 与 {@code /msl status} 共用）。
     *
     * <p>两处共用同一段输出，保证"命令里看到的"与"状态里看到的"永远一致。
     */
    private void sendSaFilter(Player player, SeekerListener.SeekerState state) {
        SaProfile profile = state.saProfile();
        player.sendMessage(Lang.msg("command.sa-filter-header", "mode", saModeLabel(profile.mode())));
        if (profile.isEmpty()) {
            player.sendMessage(Lang.msg("command.sa-filter-empty"));
            return;
        }
        if (!profile.entityIds().isEmpty()) {
            player.sendMessage(Lang.msg("command.sa-filter-entities",
                    "values", String.join(", ", profile.entityIds())));
        }
        if (!profile.playerNames().isEmpty() || !profile.playerIds().isEmpty()) {
            List<String> shown = new ArrayList<>(profile.playerNames());
            if (shown.isEmpty()) {
                profile.playerIds().forEach(id -> shown.add(id.toString()));
            }
            Collections.sort(shown);
            player.sendMessage(Lang.msg("command.sa-filter-players",
                    "values", String.join(", ", shown)));
        }
    }

    /** safilter 列表里的模式名（复用 kind.* 文案，与 /msl status 的显示口径一致）。 */
    private static String saModeLabel(SaProfile.Mode mode) {
        return switch (mode) {
            case ANY -> Lang.get("kind.any");
            case ENTITY -> Lang.get("kind.entity");
            case PLAYER -> Lang.get("kind.player");
        };
    }

    private boolean requireUse(Player player, boolean canUse) {        if (canUse) {
            return true;
        }
        player.sendMessage(Lang.msg("command.no-permission"));
        return false;
    }

    private boolean requireAdmin(Player player, boolean canAdmin) {
        if (canAdmin) {
            return true;
        }
        player.sendMessage(Lang.msg("command.admin-only"));
        return false;
    }

    /** 型号一览文案（选中型号与 status 共用）。 */
    private String typeMessage(MissileType type) {
        String detail = type.beamRiding()
                ? Lang.get("command.detail-beam")
                : Lang.get("command.detail-decoy", "decoy", decoyName(type),
                        "chance", trim(type.decoyChance() * 100.0D));
        return Lang.msg("command.status-missile", "missile", type.coloredName(),
                "min", trim(type.initialSpeed()), "max", trim(type.maxSpeed()), "extra", detail);
    }

    private void sendStatus(Player player, SeekerListener.SeekerState state) {
        MissileType current = state.type();
        player.sendMessage(this.typeMessage(current));
        player.sendMessage(Lang.get("command.status-detail",
                "kind", SeekerListener.kindLabel(state.lockKind()),
                "seeker", state.armed() ? Lang.get("command.state-on") : Lang.get("command.state-off"),
                "target", state.targetName() == null
                        ? Lang.get("command.target-none") : state.targetName()));
        // IR 模式 / usefilter 与 SA 锁定类型是**持久设置**（与当前选中的型号无关），
        // 单独两行显示，否则玩家看不到 /msl ir ... 与 /msl super_active ... 到底生效了没有
        player.sendMessage(Lang.get("command.status-ir",
                "ir", Lang.get(switch (state.irMode()) {
                    case PLAYER -> "mode.ir-player";
                    case ENTITY -> "mode.ir-entity";
                    default -> "mode.ir-default";
                }),
                "filter", Lang.get(state.irUseFilter() ? "command.state-on" : "command.state-off")));
        player.sendMessage(Lang.get("command.status-sa", "sa", SeekerListener.kindLabel(state.saKind())));
        // §1.2：SA 的 safilter 是独立名单，状态里要能看到它（否则 /msl super_active entity|player|filter 生效没生效无从判断）。
        // 直接复用 `filter list` 的那套输出，保证两处口径一致。
        if (state.type() == MissileType.SUPER_ACTIVE) {
            this.sendSaFilter(player, state);
        }
        player.sendMessage(Lang.get("command.status-switch", "switch",
                Lang.get(MissilePlugin.isPlayerEnabled(player.getUniqueId())
                        ? "command.switch-on" : "command.switch-off")));
        player.sendMessage(Lang.get("command.global-switch", "switch",
                Lang.get(MissilePlugin.isGlobalEnabled() ? "command.state-on" : "command.state-off")));
        List<String> filter = TargetFilter.describe(player.getUniqueId());
        if (!filter.isEmpty()) {
            player.sendMessage(filter.get(0));                 // 目标筛选模式（该行自带前缀）
        }
        player.sendMessage(Lang.get("command.usage-brief"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        boolean canUse = player.hasPermission(PERMISSION_USE);
        boolean canAdmin = player.hasPermission(PERMISSION_ADMIN);
        if (!canUse && !canAdmin) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            if (canUse) {
                options.addAll(TYPE_ARGS);
                options.add("filter");
                options.add("default");
                options.addAll(SWITCHES);
                options.add("status");
            }
            if (canAdmin) {
                options.add("super_active");
                options.add("global");
                options.add("reload");
            }
        } else if (args.length == 2) {
            String head = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
            switch (head) {
                case "filter" -> {
                    if (canUse) {
                        options.addAll(FILTER_HEADS);
                    }
                }
                case "ir" -> {
                    if (canUse) {
                        options.addAll(IR_MODES);
                        options.addAll(IR_FILTER_ARGS);
                    }
                }
                case "super_active" -> {
                    if (canAdmin) {
                        options.addAll(SA_MODES);
                    }
                }
                case "global" -> {
                    if (canAdmin) {
                        options.addAll(SWITCHES);
                    }
                }
                default -> {
                    // 无补全
                }
            }
        } else if (args.length == 3) {
            String head = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
            String second = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
            if (canUse && "filter".equals(head) && (TYPE_ENTITY.equals(second) || TYPE_PLAYER.equals(second))) {
                options.addAll(FILTER_MODES);
            } else if (canUse && "ir".equals(head) && IR_MODES.contains(second)) {
                options.addAll(IR_FILTER_ARGS);
            } else if (canAdmin && "super_active".equals(head)) {
                // §1.2：filter → list|clear；entity/player → 动词（1.0.3）或完整注册键 / 在线玩家名
                if ("filter".equals(second)) {
                    options.addAll(SA_FILTER_ARGS);
                } else if (TYPE_ENTITY.equals(second)) {
                    options.addAll(SA_LIST_MODES);
                    options.addAll(entityIds());
                } else if (TYPE_PLAYER.equals(second)) {
                    options.addAll(SA_LIST_MODES);
                    this.onlineNames(player, options);
                }
            }
        } else if (args.length == 4) {
            String head = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
            String second = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
            String third = args[2] == null ? "" : args[2].trim().toLowerCase(Locale.ROOT);
            boolean removing = "remove".equals(third);
            // 1.0.3：remove 列出**名单里已有的条目**（"能删什么"看得见），set/add 仍给全集
            if (canUse && "filter".equals(head) && ("set".equals(third) || "add".equals(third) || removing)) {
                if (TYPE_PLAYER.equals(second)) {
                    if (removing) {
                        listedPlayerNames(TargetFilter.entries(player.getUniqueId(), false), options);
                    } else {
                        this.onlineNames(player, options);
                    }
                } else if (TYPE_ENTITY.equals(second)) {
                    if (removing) {
                        options.addAll(TargetFilter.entries(player.getUniqueId(), true));
                    } else {
                        options.addAll(entityIds());
                    }
                }
            } else if (canAdmin && "super_active".equals(head)) {
                SeekerListener.SeekerState state = this.plugin.seekers() == null
                        ? null : this.plugin.seekers().stateIfPresent(player);
                if (TYPE_ENTITY.equals(second) && ("set".equals(third) || "add".equals(third) || removing)) {
                    options.addAll(removing ? saEntityEntries(state) : entityIds());
                } else if (TYPE_PLAYER.equals(second) && ("set".equals(third) || "add".equals(third) || removing)) {
                    if (removing) {
                        if (state != null) {
                            options.addAll(state.saPlayerNames());
                        }
                    } else {
                        this.onlineNames(player, options);
                    }
                }
            }
        }
        List<String> matches = new ArrayList<>();
        StringUtil.copyPartialMatches(args[args.length - 1] == null ? "" : args[args.length - 1], options, matches);
        return matches;
    }

    /**
     * 实体 ID 补全候选：**服务端实体注册表的完整注册键**（{@code minecraft:zombie} 这种，§2.2）。
     *
     * <p>懒加载一次，与 {@code TargetFilter} 校验用的是同一个集合 ——
     * 所以"补全里能选到的"必定"填进去能用"；无服务端环境下会回退到
     * {@code EntityType.values()}（键集合一致）。
     */
    private static List<String> entityIds() {
        if (entityIds == null) {
            List<String> ids = new ArrayList<>();
            try {
                for (String id : TargetFilter.registryIdList()) {
                    if (!"minecraft:player".equals(id) && !id.isEmpty()) {
                        ids.add(id);          // 玩家走 player 类，不作为实体 ID 补全
                    }
                }
            } catch (Throwable throwable) {
                // 兜底：补全列表绝不因注册表问题抛异常
            }
            Collections.sort(ids);
            entityIds = ids;
        }
        return entityIds;
    }

    /** 在线玩家名（补全用；按服务端给出的顺序）。 */
    private void onlineNames(Player player, List<String> options) {
        for (Player online : this.plugin.getServer().getOnlinePlayers()) {
            options.add(online.getName());
        }
    }

    /**
     * 白名单里**已添加的玩家名**（{@code remove} 的补全用）。
     *
     * <p>名单里存的是小写名；能对上在线玩家的按**当前真实大小写**显示（好认），
     * 离线玩家直接给存档里的小写名 —— 命令按名字匹配，小写名照样能删掉。
     */
    private static void listedPlayerNames(List<String> names, List<String> options) {
        for (String name : names) {
            Player online = Bukkit.getPlayerExact(name);
            options.add(online == null ? name : online.getName());
        }
    }

    /** safilter 实体类里已添加的条目（{@code remove} 的补全用；没有导引头状态时为空）。 */
    private static List<String> saEntityEntries(SeekerListener.SeekerState state) {
        return state == null ? new ArrayList<>() : new ArrayList<>(state.saEntityIds());
    }

    /**
     * 干扰物的显示名。语言包约定 key 为 {@code decoy.<材质名小写、下划线转连字符>}
     * （{@code BLAZE_POWDER} → {@code decoy.blaze-powder}），
     * 所以以后换干扰物只需改语言包，不必再改这里。
     */
    private static String decoyName(MissileType type) {
        if (type.decoyChance() <= 0.0D) {
            // decoy-chance 写 0 = 免疫干扰，别在状态里把 AIR 之类的占位材质显示出来
            return Lang.get("decoy.immune");
        }
        return Lang.get("decoy." + type.decoyMaterial().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
