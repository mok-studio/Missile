# Missile Player Manual

English | [简体中文](玩家手册.md)

**Hold TNT in your main hand → right-click (arm the seeker and lock on) → right-click again (fire).** TNT is the ammunition: one per shot in survival, free in creative.

Owners / developers: see [README.md](../README.md) for the build, configuration, commands, permissions and placeholders.

## 1. Controls & Locking

| Step | Action | On screen |
|---|---|---|
| 1 | `/msl ir` / `/msl semi` / `/msl active` / `/msl semiLOS` to pick a type | Chat shows the type and its stats |
| 2 | Hold TNT in your **main hand**, **right-click** | ActionBar `§f§k1 §7Infrared §7\| §fTarget: Any §7\| §aLocked §fSteve §7\| §fright-click to fire §f§k1` |
| 3 | **Right-click again** | ActionBar shows "Fired"; the missile leaves the tube and one TNT is consumed |

- **Locking**: inside a **10° crosshair cone**, within **128 blocks**, line of sight not blocked, and never yourself. The nearest candidate wins, refreshed every **2 ticks** while the seeker is armed.
- Swapping the main-hand item closes the seeker immediately; while armed, right-click **never** places a TNT block.
- When locked, the ActionBar is wrapped by an **`&f&k` obfuscated marker on both ends** (exactly one space from the text). It only appears while a target is locked.
- The **Target** field shows what you can actually lock: `player`, `entity`, `Mixed` (both classes allowed) or `Any` (no concrete target in the list). The super active missile reads its own safilter contents, so entity and player entries together show `Mixed`.
- **Semi-active** refuses to fire with no valid target; **semiLOS** locks nothing and follows a point 200 blocks along your crosshair.

## 2. The Five Missiles

| | Infrared `ir` | Semi-Active `semi` | Active `active` | Beam-riding `semiLOS` | Super Active `super_active` |
|---|---|---|---|---|---|
| Who can use it | Everyone | Everyone | Everyone | Everyone | **Admins only** |
| Initial → max speed | 5 → 25 m/s | 5.2 → 30 m/s | 6 → 40 m/s | 5.2 → 30 m/s | **8 → 850 m/s** |
| Agility (turn rate) | Medium (6°/tick) | Low (4°/tick) | High (7°/tick) | Very high (12°/tick) | **Extreme (30°/tick)** |
| Warhead power | 3.0 | 3.5 | **4.5** (close to TNT) | 3.5 | **10.0** |
| Keep aiming after launch? | No (it chases) | **Yes** (keep illuminating) | No (chases and leads the target) | **Yes** (you "fly" it with the crosshair) | No |
| Lead calculation | No (pure pursuit) | No | **Yes** (predicts your movement) | — | **Yes** |
| Target hides behind cover | Re-acquires within 55 blocks / 30° | Flies straight once illumination is lost; recoverable by looking again | Remembers the last position for 5 s, re-acquires within 70 blocks / 40° after 3 s | — | Remembers 5 s, re-acquires within **100 blocks / 45°** after 3 s |
| Countermeasure | **Blaze powder** (dropped, 15%/s) | Iron nugget (5%/s, **link breaks permanently**) | Iron nugget (2.5%/s, re-acquires after 3 s) | **None** | **Fully immune** |
| Triggers enemy RWR? | **No** | Yes (track + missile) | Yes (track + missile) | **No** | **Yes** |
| Triggers enemy MAWS? | Yes (within 40 blocks) | Yes | Yes | Yes | Yes |
| Flame colour | Orange-red | Pale orange | Bronze | Cyan-blue | Bronze |

The table lists the server defaults; owners can change anything in `msl_config.yml` (applies after `/msl reload`), so `/msl status` is always the final word.

## 3. Which One to Pick

- **Infrared** — the ambush pick: it never triggers the enemy RWR, so a shot from behind gives no warning (but it does trigger MAWS inside 40 blocks, so do not get too close). Downside: a dropped blaze powder pulls it away 15% per second, and it turns sluggishly.
- **Semi-Active** — for high ground or long range where you can keep watching the target, and you can switch targets mid-flight. One iron nugget breaks the link for good, it turns the worst, and it is a poor choice against someone circling you up close.
- **Active** — the strongest all-rounder: fast, agile, best countermeasure resistance, leads the target, high power. The price is that the enemy RWR screams at them.
- **Beam-riding (semiLOS)** — you fly it by hand: it goes wherever your crosshair points. It never triggers RWR and ignores every countermeasure, but it has no proximity fuse (only a direct hit detonates) and you must stay alive in the same dimension — if you die, change dimension or log out, it keeps flying straight.
- **Super Active** — admins only; fast, agile, power 10, immune to countermeasures, and it triggers every enemy warning.

## 4. Countermeasures & Survival

### Countermeasures (must be **dropped**)

| Item | Counters | Break-lock chance per second | Average time | Extra conditions |
|---|---|---|---|---|
| **Blaze powder** | Infrared | 15% | ~7 s | Valid for **3 s** after dropping; the missile must be within **48 blocks** and **in front of you** |
| **Iron nugget** | Semi-Active | 5% | ~20 s | Same |
| **Iron nugget** | Active | 2.5% | ~40 s | Same, and it re-acquires you after 3 s |

- **Holding it does nothing**: you must press the drop key, and the dropped item only counts for 3 seconds. Your own drops never affect your own missile.
- **Blaze powder "pulls" an infrared missile away**: the missile breaks lock and chases **the dropped blaze powder**, and it cannot re-lock the thrower for 5 seconds — a teammate's blaze powder can decoy an infrared missile for you.
- **Infrared ignores iron nuggets; semiLOS and super active ignore every countermeasure.**

### Using terrain

- **Infrared / Active / Super Active** seekers **do not care about block cover** — they re-grab you as long as you stay in their forward cone — and they carry a **3-block proximity fuse**, so a near miss still kills.
- **Semi-Active** depends on the shooter's line of sight: **breaking line of sight breaks the illumination** (the missile flies straight), but looking at you again restores it.
- **Active / Super Active** lead your movement — running in a straight line is the easiest way to die; **change direction often**.

## 5. Knowing You Are Being Shot At: RWR & MAWS

**All three conditions are required, otherwise you get no warning at all:**

1. You have `missile.use` (everyone does by default; `missile.admin` also works for admins);
2. **You** turned the missile system on with `/msl on` (`/msl off` silences everything immediately);
3. You carry a **compass** anywhere in your inventory.

Removing the compass or running `/msl off` silences the warnings at once.

| Warning | Trigger | Display | Sound |
|---|---|---|---|
| **RWR tracked** | Someone has a semi-active / active seeker armed and locked on you (**not fired yet**) | **Yellow** BossBar `⚠ TRACKED →` | Alternating high/low tone every **0.2 s** |
| **RWR missile** | A semi-active / active missile is in flight and locked on you | **Red** BossBar `⚠ MISSILE ↘ ×2` | Rapid high tone every **0.1 s** |
| **MAWS** | Something **closing in on you** (closing speed > 10 m/s) within **40 blocks**: **arrows / spectral arrows / tridents / any missile** (including infrared and semiLOS) | **Red** BossBar `⚠ MAWS: ↑` | Silent by default (owners can enable it) |

- **MAWS and RWR are two independent bars and can appear at the same time**; with several RWR threats the **nearest** one is shown with a `×count`, and missiles take priority over tracks.
- MAWS only warns about things that are **approaching** — **your own arrows and missiles never warn you** — but someone else's arrow flying at you does.
- **Bearings use your current facing as forward (↑)**, clockwise in 8 steps: `↑` ahead / `↗` front-right / `→` right / `↘` rear-right / `↓` behind / `↙` rear-left / `←` left / `↖` front-left (12 / 1:30 / 3 / 4:30 / 6 / 7:30 / 9 / 10:30 o'clock).
- Reading examples: `⚠ MISSILE ↓` means a missile is chasing you from directly behind; `⚠ TRACKED ↖` means someone is painting you with radar from the front-left; `⚠ MAWS: ↗` means a missile is within 40 blocks to your front-right (possibly aimed at someone else).
- **Infrared and semiLOS never trigger RWR**, but they **do** trigger MAWS — "no RWR warning" does not mean you are safe. RWR range is capped at 256 blocks (owner-configurable); dying, respawning or quitting clears the bars.

## 6. Command Reference

| Command | Description |
|---|---|
| `/msl`, `/msl status` | Type and stats, target kind, seeker, personal/global switch, IR mode, SA lock kind, filter mode |
| `/msl ir` | Infrared (with no arguments it **changes nothing** you already set) |
| `/msl ir player` / `entity` / `default` | IR working mode: players only / switch to any creature after losing a player / back to default |
| `/msl ir usefilter` / `filteroff` | Turn the whitelist channel on / off (lock only what `/msl filter list` allows) |
| `/msl semi` / `active` / `semiLOS` | Semi-active / active / beam-riding |
| `/msl on` / `off` | **Your own** missile switch (on by default). After `off`, TNT behaves like vanilla again and you **stop receiving RWR/MAWS warnings** |
| `/msl default` | Reset everything to default: IR mode, usefilter, SA mode and safilter, the seeker and the **target filter whitelist**; **the type and the `on\|off` switch are kept** |
| `/msl filter ...` | Target filter (below) |
| `/msl super_active [default\|entity\|player]` | Super active missile (**admins only**) |
| `/msl super_active entity\|player <set\|add\|remove\|clear> [values...]` | Edit safilter entries (**admins only**, below) |
| `/msl global on\|off`, `/msl reload` | Global switch, hot reload (**admins only**) |

- `/msl` is an alias of `/missile`; types also accept `infrared` / `sarh` / `arh` / `semilos` / `los` / `line` / `红外` / `半主动` / `主动` / `指令` / `驾束` / `线导` / `1` `2` `3` `4` `5`.
- A wrong argument never errors out: parsing runs left to right and **stops at the first invalid argument**, keeping what came before it (`/msl ir player 114514` equals `/msl ir player`). The exception is `remove`: entries that are valid but not in the list are only listed, without aborting.

### `/msl default`: start clean

| Reset | Kept |
|---|---|
| The mode set by `/msl ir`, the `usefilter` switch | **Your selected type** (infrared / semi-active / active / semiLOS / super active) |
| The super active mode and its **safilter** | **The `/msl on` / `/msl off` switch** |
| The seeker (closed immediately) | Anything owned by other players, and the global switch |
| **The target filter whitelist and filter mode** (all `/msl filter` data) | — |

Use `/msl filter clear` if you only want to wipe the filter.

### Target filter (whitelist)

| Command | Effect |
|---|---|
| `/msl filter entity zombie skeleton` | Lock only zombies and skeletons (`minecraft:zombie` also works) |
| `/msl filter entity` | Allow "any creature" (no ID given) |
| `/msl filter entity oak_boat` | **Boats become shootable**: vehicles, end crystals, armor stands and friends cannot be locked until you write their ID explicitly |
| `/msl filter entity boat` | Group alias for every boat (all wood types and chest variants); `minecarts` likewise |
| `/msl filter player Steve` | Lock only Steve (the name must be **online**) |
| `/msl filter player` | Allow every player |
| `/msl filter entity add zombie` | **Append** to the existing whitelist (`set` clears everything first) |
| `/msl filter entity remove zombie` | **Drop the zombie you added** (touches nothing else) |
| `/msl filter player remove Steve` | Drop Steve from the player list (**works while he is offline**) |
| `/msl filter entity clear` / `player clear` | Clear just that class |
| `/msl filter list` | Show the current mode and the whitelist |
| `/msl filter on` / `off` | Enable / disable filter mode (`off` = free targeting, whitelist kept) |
| `/msl filter clear` | Clear all filter data (leaves `on`/`off` alone) |

- The whitelist means **"only what is listed"**: an empty player list allows every player, while any entity ID means only those IDs.
- Writing a class or an ID turns filter mode **on** automatically; **filter mode with an empty whitelist locks nothing**, so `/msl filter on` is refused while empty and the mode switches back to `off` when you empty it.
- `set` / `add` / `remove` need at least one ID, otherwise the whole command does nothing (it will not wipe your data by accident).
- **`remove` only deletes entries and has no side effects**: it neither enables a class nor changes the filter mode; emptying a class is the same as "no restriction for that class". **Pressing Tab after `remove` lists the entries already in the list.**
- Filter data **is stored on the server** (by default `plugins/Missile/data/filters.json`) and survives restarts.
- **While filter mode is on, the enabled whitelist classes decide what you can lock**: after `/msl filter entity add phantom` even a player-only infrared default can **lock phantoms**; players stay unlockable unless the player class is enabled. Only turning the filter off hands control back to `/msl ir`.

### safilter of the super active missile (admins only)

A **separate list** that never interacts with `/msl filter`, and it **resets on logout**:

| Command | Effect |
|---|---|
| `/msl super_active default` | Any target (clears the safilter) |
| `/msl super_active entity` / `player` | Creatures only / players only (clears the safilter) |
| `/msl super_active entity add zombie` | **Append** a zombie to the entity class (`add` can be omitted: just write the ID) |
| `/msl super_active entity set zombie skeleton` | **Set** the entity class to these two (replaces the previous entity entries) |
| `/msl super_active entity remove zombie` | **Drop** the zombie from the entity class |
| `/msl super_active entity clear` / `player clear` | Clear that class (the other class stays) |
| `/msl super_active filter list` | Show the safilter mode and its entries |

## 7. FAQ

| Symptom | Cause / fix |
|---|---|
| Right-click placed the TNT | You ran `/msl off` at some point (`/msl on` restores it) |
| Right-click does nothing | TNT is not in your **main hand**, or you lack `missile.use` |
| Semi-active will not fire | No valid target in the crosshair cone (or line of sight is blocked, or the whitelist is empty) — look at the target first |
| Cannot lock a player | Beyond 128 blocks / outside the 10° cone / line of sight blocked / they are a spectator / not in your whitelist |
| Cannot lock boats, minecarts, end crystals | Non-living entities are **unlockable by default**: list them with `/msl filter entity set boat` (or `oak_boat`, `minecart`) |
| Added `phantom` but still cannot lock phantoms | Fixed: while filter mode is on, the enabled whitelist classes win |
| `/msl filter on` says the whitelist is empty | Intentional: empty whitelist + filter mode locks nothing; add something with `/msl filter entity <ID>` first |
| Nothing locks after enabling usefilter | Check `/msl filter list` for an empty whitelist, or run `/msl ir filteroff` |
| The missile turned away and chased someone else | Someone nearby dropped a countermeasure: blaze powder pulls infrared away, iron nuggets jam semi-active / active |
| The missile flies straight | You are using `semiLOS` (hand-flown by design), or the target died / nothing is in front |
| The missile exploded without a direct hit | Infrared / semi-active / active / super active all carry a **3-block proximity fuse** (semiLOS does not) |
| I hurt myself / the terrain got wrecked | Explosions hurt the shooter too — do not fire point blank; terrain damage depends on the owner's `explosion.break-blocks` |
| No warnings at all | Requires permission + `/msl on` + a **compass**; infrared and semiLOS never trigger RWR anyway |
| RWR works but MAWS does not (or vice versa) | MAWS only looks **40 blocks** out and can be disabled entirely (`maws.enabled`) |
| The messages are in a language I do not read | The owner set `language` to `zh_cn`; ask them to switch to `en_us` |
| The ActionBar / warning bars vanished | You swapped the main-hand item (seeker closed), the threat is gone, or you ran `/msl off` |
| The ActionBar shows gibberish on both ends | That is the "locked" marker (`launcher.lock-padding`), one space away from the text on each side; it does not appear without a lock |
| I want to wipe everything and start over | `/msl default`: resets IR mode / usefilter / SA mode and safilter / seeker / filter whitelist; **type and `/msl on\|off` are kept** |
| I added a wrong entry and want it gone | `/msl filter entity\|player remove <ID>` (Tab lists the existing entries); for the safilter use `/msl super_active entity\|player remove <value>` |

## 8. Ten Practical Tips

1. **Ambush with infrared**: it never triggers RWR; just stay outside 40 blocks or their MAWS lights up.
2. **Fight head-on with active**: fast, leads the target, and the best resistance to iron nuggets.
3. **When they drop blaze powder against your infrared, switch to semiLOS** — it ignores every countermeasure.
4. **Semi-active can switch targets mid-flight**: move your crosshair to someone else and the missile follows, which is great for breaking up a formation.
5. **Remember this is a line-of-sight weapon**: while the seeker is armed the target must be in the crosshair cone and **not behind blocks**.
6. **Active missiles are best against runners in a straight line**: when you flee, **turning** keeps you alive better than sprinting.
7. **Near misses still kill** (3-block fuse), so leave margin when dodging.
8. **When you hear "TRACKED", break line of sight**: semi-active illumination needs sight, so cover makes it fly straight.
9. **When you hear "MISSILE", drop a countermeasure at once**: blaze powder beats infrared, iron nuggets beat semi-active / active, and the missile must be **in front of you** (holding it does nothing).
10. **`/msl ir entity` is a monster-hunting trick**: it falls back to any creature after losing a player; use `default` if you want players only.
