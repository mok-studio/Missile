package com.missile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
 *       {@code /msl on|off}、{@code /msl status}</li>
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
    private static final List<String> FILTER_HEADS = List.of("entity", "player", "clear", "on", "off", "list");
    private static final List<String> FILTER_MODES = List.of("set", "add", "clear");
    private static final List<String> SWITCHES = List.of("on", "off");

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
     *   <li>{@code ir [default|player|entity] [usefilter]} —— 缺省不改动</li>
     *   <li>{@code super_active [default|entity|player]}</li>
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
                // §1.1 的可选参数（entity/player 后面的 ID、filter 后面的 list|clear）
                String second = args.length > 2 && args[2] != null
                        ? args[2].trim() : null;
                switch (first) {
                    case "entity" -> this.applySaEntity(player, state, second);
                    case "player" -> this.applySaPlayer(player, state, second);
                    case "filter" -> this.applySaFilter(player, state, second);
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
     * {@code /msl super_active entity [<完整注册ID>]}（§1.1 / §1.2）。
     *
     * <p>不带 ID = 除玩家外的任意实体（**实体优先、不做玩家优先两段式**，拍板④），并清空 safilter；
     * 带 ID = 只锁该实体，ID 走与全局 filter **同一套**规范化 + 组别名展开，且拒绝 {@code minecraft:player}。
     *
     * <p>前缀容错：{@code entity} 本身合法，所以即使后面的 ID 非法，**模式照旧生效**（与
     * {@code /msl filter entity set <非法ID>} 的行为一致）。
     */
    private void applySaEntity(Player player, SeekerListener.SeekerState state, String raw) {
        state.saMode(SaProfile.Mode.ENTITY);
        if (raw == null || raw.isBlank()) {
            state.clearSaFilter();
            player.sendMessage(Lang.msg("command.sa-entity"));
            return;
        }
        String id = TargetFilter.normalizeId(raw);
        if ("minecraft:player".equals(id)) {
            player.sendMessage(Lang.msg("filter.error-player-as-entity"));
            return;
        }
        if (TargetFilter.isRegistryId(id)) {
            state.addSaEntityId(id);
            player.sendMessage(Lang.msg("command.sa-filter-entity", "id", id));
            return;
        }
        List<String> expanded = TargetFilter.expandAliasForCommand(id);
        if (expanded.isEmpty()) {
            player.sendMessage(Lang.msg("filter.unknown-id", "input", raw));
            return;
        }
        expanded.forEach(state::addSaEntityId);
        player.sendMessage(Lang.msg("command.sa-filter-alias",
                "input", raw, "count", expanded.size()));
    }

    /**
     * {@code /msl super_active player [<在线玩家名>]}（§1.1 / §1.2）。
     *
     * <p>不带名字 = 除**自己**外的任意玩家，并清空 safilter；带名字 = 只锁该玩家
     * （只接受服务器上在线的玩家：解析 UUID + 名字兜底）。找不到玩家时**不改状态**。
     */
    private void applySaPlayer(Player player, SeekerListener.SeekerState state, String raw) {
        state.saMode(SaProfile.Mode.PLAYER);
        if (raw == null || raw.isBlank()) {
            state.clearSaFilter();
            player.sendMessage(Lang.msg("command.sa-player"));
            return;
        }
        Player target = this.plugin.getServer().getPlayerExact(raw);
        if (target == null) {
            player.sendMessage(Lang.msg("filter.unknown-player", "input", raw));
            return;
        }
        state.addSaPlayer(target.getUniqueId(), target.getName());
        player.sendMessage(Lang.msg("command.sa-filter-player", "name", target.getName()));
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
            if (canUse && "filter".equals(head) && ("entity".equals(second) || "player".equals(second))) {
                options.addAll(FILTER_MODES);
            } else if (canUse && "ir".equals(head) && IR_MODES.contains(second)) {
                options.addAll(IR_FILTER_ARGS);
            } else if (canAdmin && "super_active".equals(head)) {
                // §1.2：filter → list|clear；entity → 完整注册键；player → 在线玩家名
                if ("filter".equals(second)) {
                    options.addAll(SA_FILTER_ARGS);
                } else if ("entity".equals(second)) {
                    options.addAll(entityIds());
                } else if ("player".equals(second)) {
                    for (Player online : this.plugin.getServer().getOnlinePlayers()) {
                        options.add(online.getName());
                    }
                }
            }
        } else if (args.length == 4) {
            String head = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
            String second = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
            String third = args[2] == null ? "" : args[2].trim().toLowerCase(Locale.ROOT);
            if (canUse && "filter".equals(head) && ("set".equals(third) || "add".equals(third))) {
                if ("player".equals(second)) {
                    for (Player online : this.plugin.getServer().getOnlinePlayers()) {
                        options.add(online.getName());
                    }
                } else if ("entity".equals(second)) {
                    options.addAll(entityIds());
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
