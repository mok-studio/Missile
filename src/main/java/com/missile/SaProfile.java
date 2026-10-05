package com.missile;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * 超级主动弹（{@code super_active}）的锁定配置**快照**。
 *
 * <p>为什么要有这个类：SA 的锁定规则比其它型号复杂（模式 + 独立名单），而且**发射时要一次性传给导弹**
 * 做弹上重新截获。用一个不可变值对象传参，可以保证"弹上拿到的配置"不会再被玩家的后续操作改掉
 * （{@code /msl super_active …} 是在会话状态里改的，导弹拿到的是当时的副本）。
 *
 * <p>规则见施工规格 §1.2 与决策 #27~#30：
 * <ul>
 *   <li>{@link Mode#ANY}（{@code default}）= 任意目标：玩家与非玩家生物都可以，取最近的；
 *       **不做"玩家优先"两段式**（"任意"就是任意）。</li>
 *   <li>{@link Mode#ENTITY}（{@code entity}）= 只锁非玩家生物；safilter 非空时只认列出的 ID。</li>
 *   <li>{@link Mode#PLAYER}（{@code player}）= 只锁玩家（除自己）；safilter 非空时只认列出的玩家。</li>
 *   <li>非生物（载具 / 末地水晶 / 盔甲架 / 展示实体…）在 ANY / ENTITY 下**必须被 safilter 显式列出**
 *       才放行（与决策 #22 同口径）。</li>
 *   <li>**safilter 与全局 filter 完全无关**：SA 任何模式都不查全局 filter（决策 #29）。</li>
 * </ul>
 */
public final class SaProfile {

    /** SA 工作模式，对应 {@code /msl super_active default|entity|player}。 */
    public enum Mode {
        /** 任意目标（{@code default}）。 */
        ANY,
        /** 只锁非玩家生物（{@code entity}）。 */
        ENTITY,
        /** 只锁玩家、锁不到自己（{@code player}）。 */
        PLAYER
    }

    /** 出厂默认快照：任意目标 + 空名单（等于 {@code /msl super_active default}）。 */
    public static final SaProfile DEFAULT = new SaProfile(Mode.ANY, Set.of(), Set.of(), Set.of());

    private final Mode mode;
    private final Set<String> entityIds;
    private final Set<UUID> playerIds;
    private final Set<String> playerNames;

    public SaProfile(Mode mode, Set<String> entityIds, Set<UUID> playerIds, Set<String> playerNames) {
        this.mode = mode == null ? Mode.ANY : mode;
        this.entityIds = copy(entityIds);
        this.playerIds = copy(playerIds);
        this.playerNames = copy(playerNames);
    }

    private static <T> Set<T> copy(Set<T> source) {
        return source == null || source.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    public Mode mode() {
        return this.mode;
    }

    /** safilter 里的实体 ID（**完整注册键**）；空集 = 不限制。 */
    public Set<String> entityIds() {
        return this.entityIds;
    }

    /** safilter 里的玩家 UUID；与 {@link #playerNames()} 都空 = 任意玩家。 */
    public Set<UUID> playerIds() {
        return this.playerIds;
    }

    /** safilter 里的玩家名（小写，作为 UUID 解析失败时的兜底）。 */
    public Set<String> playerNames() {
        return this.playerNames;
    }

    /** safilter 是否为空（三种模式下的"不限制"状态）。 */
    public boolean isEmpty() {
        return this.entityIds.isEmpty() && this.playerIds.isEmpty() && this.playerNames.isEmpty();
    }

    /** 本次模式是否允许锁玩家。 */
    public boolean allowsPlayers() {
        return this.mode == Mode.ANY || this.mode == Mode.PLAYER;
    }

    /** 本次模式是否允许锁非玩家生物。 */
    public boolean allowsCreatures() {
        return this.mode == Mode.ANY || this.mode == Mode.ENTITY;
    }

    /**
     * 非生物（载具 / 末地水晶 / 盔甲架…）是否放行：必须在 safilter 里**显式列出**其 ID。
     *
     * <p>与决策 #22 同口径：默认锁不上，写进名单才解锁；这里不看 {@code player}/{@code entity} 类开关，
     * 因为 SA 的名单就是这一份。
     */
    public boolean allowsNonLiving(String id) {
        return allowsCreatures() && !id.isEmpty() && this.entityIds.contains(id);
    }

    /** 生物 ID 是否被名单允许（名单空 = 不限制）。 */
    public boolean allowsEntityId(String id) {
        return this.entityIds.isEmpty() || this.entityIds.contains(id);
    }

    /** 玩家是否被名单允许（名单空 = 任意玩家）；名字按小写比较。 */
    public boolean allowsPlayer(Player player) {
        if (this.playerIds.isEmpty() && this.playerNames.isEmpty()) {
            return true;
        }
        if (this.playerIds.contains(player.getUniqueId())) {
            return true;
        }
        String name = player.getName();
        return name != null && this.playerNames.contains(name.toLowerCase(Locale.ROOT));
    }
}
