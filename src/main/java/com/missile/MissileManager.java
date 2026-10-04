package com.missile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** 活动导弹注册表与每 tick 推进器。 */
public final class MissileManager {

    /** 同时存在的导弹上限，超出时丢弃最旧的一发。 */
    private static final int MAX_ACTIVE = 64;

    private final MissilePlugin plugin;
    private final List<Missile> missiles = new ArrayList<>();

    MissileManager(MissilePlugin plugin) {
        this.plugin = plugin;
    }

    /** 发射一发导弹，默认锁定玩家。 */
    public Missile launch(Player owner, MissileType type, LivingEntity target, Location origin, Vector direction) {
        return this.launch(owner, type, target, origin, direction, MissileType.TargetKind.PLAYER);
    }

    /** 发射一发导弹；驾束型号走 SemiLOSMissile，其余走 Missile。 */
    public Missile launch(Player owner, MissileType type, LivingEntity target, Location origin, Vector direction,
                          MissileType.TargetKind targetKind) {
        while (this.missiles.size() >= MAX_ACTIVE) {
            this.missiles.remove(0).discard();
        }
        Missile missile = type.beamRiding()
                ? new SemiLOSMissile(this.plugin, type, owner, origin, direction, target, targetKind)
                : new Missile(this.plugin, type, owner, origin, direction, target, targetKind);
        this.missiles.add(missile);
        return missile;
    }

    /** 推进所有导弹；返回 false 的实例已结束并从注册表移除。 */
    public void tick() {
        for (int index = this.missiles.size() - 1; index >= 0; index--) {
            Missile missile = this.missiles.get(index);
            if (!missile.tick()) {
                this.missiles.remove(index);
            }
        }
    }

    /** 通过弹体实体反查导弹，用于 ProjectileHitEvent。 */
    public Missile forProjectile(Entity entity) {
        for (Missile missile : this.missiles) {
            if (missile.projectile().equals(entity)) {
                return missile;
            }
        }
        return null;
    }

    public int activeCount() {
        return this.missiles.size();
    }

    public List<Missile> all() {
        return Collections.unmodifiableList(this.missiles);
    }

    /** 关服 / reload 清场，不产生爆炸。 */
    public void shutdown() {
        for (Missile missile : new ArrayList<>(this.missiles)) {
            missile.discard();
        }
        this.missiles.clear();
    }
}
