package com.missile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/**
 * 命令实现：{@code /missile <ir|semi|active|semiLOS> <player|entity>}（别名 {@code /msl}）
 *
 * <p>参数 1 为导弹型号，缺省 infrared；参数 2 为锁定目标类型，缺省 player。
 * 另有 {@code /missile on|off}（个人开关）、{@code /missile filter ...}（目标筛选）
 * 与 {@code /missile status}（查看状态）。
 * 同时实现 {@link TabCompleter}：无 {@code missile.use} 权限时不返回任何补全项。
 */
public final class MissileCommand implements CommandExecutor, TabCompleter {

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
        if (!player.hasPermission("missile.use")) {
            player.sendMessage(Lang.msg("command.no-permission"));
            return true;
        }
        SeekerListener.SeekerState state = this.plugin.seekers().state(player);

        // on / off 优先识别，与原有的 <型号> <player|entity> 参数互斥
        if (args.length > 0 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            this.toggle(player, args[0].equalsIgnoreCase("on"));
            return true;
        }

        // filter 子命令：目标筛选，第 2 个参数起全部交给 TargetFilter 解析
        if (args.length > 0 && args[0].equalsIgnoreCase("filter")) {
            this.filter(player, args.length > 1
                    ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            this.sendStatus(player, state);
            return true;
        }

        MissileType type = args.length == 0 ? MissileType.INFRARED : MissileType.parse(args[0]);
        if (type == null) {
            player.sendMessage(Lang.msg("command.unknown-missile", "input", args[0]));
            return true;
        }
        // 超级主动弹需要 missile.superactive（plugin.yml 默认 op）
        if (type == MissileType.SUPER_ACTIVE && !player.hasPermission("missile.superactive")) {
            player.sendMessage(Lang.msg("super_active_no_perm"));
            return true;
        }
        MissileType.TargetKind kind = MissileType.TargetKind.PLAYER;
        if (args.length >= 2) {
            kind = MissileType.TargetKind.parse(args[1]);
            if (kind == null) {
                player.sendMessage(Lang.msg("command.unknown-kind", "input", args[1]));
                return true;
            }
        }

        state.type(type);
        state.targetKind(kind);
        state.updateLock(TargetSelector.select(player, SeekerListener.LOCK_RANGE, SeekerListener.LOCK_CONE, kind));

        String detail = type.beamRiding()
                ? Lang.get("command.detail-beam")
                : Lang.get("command.detail-decoy", "decoy", decoyName(type),
                        "chance", trim(type.decoyChance() * 100.0D));
        player.sendMessage(Lang.msg("command.selected",
                "missile", type.displayName(), "kind", SeekerListener.kindLabel(kind),
                "min", trim(type.initialSpeed()), "max", trim(type.maxSpeed()), "extra", detail));
        player.sendMessage(Lang.get("command.usage"));
        return true;
    }

    /**
     * Tab 补全：参数 1 补型号与 on / off；参数 2 仅在参数 1 是型号时补 player / entity。
     * 无 {@code missile.use} 权限的发送者不返回任何项。
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("missile.use") || args.length < 1 || args.length > 2) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("ir");
            options.add("semi");
            options.add("active");
            options.add("semiLOS");
            options.add("on");
            options.add("off");
            options.add("filter");
            if (sender.hasPermission("missile.superactive")) {
                options.add("super_active");   // 无权限不提示，避免给出用不了的参数
            }
        } else if (args[0].equalsIgnoreCase("filter")) {
            // filter 的第 2 个参数：模式关键字 + 常用类型与排除项
            options.add("all");
            options.add("clear");
            options.add("list");
            options.add("entity");
            options.add("player");
            options.add("!player");
            options.add("!entity");
        } else if (MissileType.parse(args[0]) != null) {
            // on / off 不接受第 2 个参数，只有型号才补目标类型
            options.add("player");
            options.add("entity");
        } else {
            return Collections.emptyList();
        }
        List<String> matches = new ArrayList<>();
        StringUtil.copyPartialMatches(args[args.length - 1], options, matches);
        return matches;
    }

    /** /missile filter ...：查看或修改**自己**的目标筛选。 */
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

    /** /msl on|off：只切换**发起者自己**的导弹开关（仅内存，不写配置）。 */
    private void toggle(Player player, boolean value) {
        MissilePlugin.setPlayerEnabled(player.getUniqueId(), value);
        player.sendMessage(Lang.msg(value ? "command.enabled" : "command.disabled"));
    }

    private void sendStatus(Player player, SeekerListener.SeekerState state) {
        MissileType current = state.type();
        String detail = current.beamRiding()
                ? Lang.get("command.detail-beam")
                : Lang.get("command.detail-decoy", "decoy", decoyName(current),
                        "chance", trim(current.decoyChance() * 100.0D));
        player.sendMessage(Lang.msg("command.status-missile",
                "missile", current.displayName(), "min", trim(current.initialSpeed()),
                "max", trim(current.maxSpeed()), "extra", detail));
        player.sendMessage(Lang.get("command.status-detail",
                "kind", SeekerListener.kindLabel(state.targetKind()),
                "seeker", state.armed() ? Lang.get("command.state-on") : Lang.get("command.state-off"),
                "target", state.targetName() == null
                        ? Lang.get("command.target-none") : state.targetName()));
        player.sendMessage(Lang.get("command.status-switch", "switch",
                Lang.get(MissilePlugin.isPlayerEnabled(player.getUniqueId())
                        ? "command.switch-on" : "command.switch-off")));
        player.sendMessage(Lang.get("command.usage-brief"));
    }

    private static String decoyName(MissileType type) {
        return switch (type.decoyMaterial()) {
            case BLAZE_ROD -> Lang.get("decoy.blaze-rod");
            case IRON_NUGGET -> Lang.get("decoy.iron-nugget");
            // SUPER_ACTIVE 用 AIR + decoyChance 0 表示"免疫干扰"，别在状态里显示成 AIR
            case AIR -> Lang.get("command.target-none");
            default -> type.decoyMaterial().name();
        };
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
