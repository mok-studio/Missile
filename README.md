# Missile — Guided Missile Plugin for Paper 1.21.11

English | [简体中文](README.zh-CN.md)

**This plugin was made with DeepSeek Harness — thanks to the AI agent for the convenience it provides!**

Right-click with TNT in your main hand to arm the seeker and lock a target in your line of sight, then right-click again to fire. Five missile types are available; victims get two BossBar warnings — **RWR** (radar warning receiver) and **MAWS** (missile approach warning system).

| Item | Value |
|---|---|
| Name / main class | `Missile` / `com.missile.MissilePlugin` |
| Version / platform | `1.0.3` / Paper 1.21.11 (Java 21, `api-version: 1.21`) |
| Command | `/missile`, alias `/msl` |
| Permissions | `missile.use` (default `true`), `missile.admin` (default `op`) |
| Optional dependency | PlaceholderAPI (soft dependency; the `msl` expansion registers automatically when installed) |
| Implementation | Pure Paper API, no NMS and no reflection |
| Config files | `config.yml` (general) + `msl_config.yml` (all missile parameters) |
| Messages | `lang/zh_cn.yml` / `lang/en_us.yml` (121 : 121 keys), overridable per key via `messages:` in `config.yml` |

## 1. Build & Install

| Step | Action |
|---|---|
| Build | `mvn -s .mvn/local-repo-settings.xml -B clean package` (`-s` is required when the default local repository is not writable) |
| Artifact | `target/Missile-1.0.3.jar` |
| Install | Drop the jar into the server `plugins/` folder and restart |
| First start | `plugins/Missile/` gets `config.yml` and `msl_config.yml`; language files are extracted to `lang/` |
| Verify | Console prints `Missile enabled: …`; with PAPI it also prints `已注册 PlaceholderAPI 扩展 msl` |

## 2. Commands & Permissions

| Command | Permission | Description |
|---|---|---|
| `/msl`, `/msl status` | `use` | Type and stats, current target, seeker state, personal/global switch, IR mode, SA mode + safilter, filter mode |
| `/msl ir [default\|player\|entity] [usefilter\|filteroff]` | `use` | Infrared type, working mode and whitelist channel (independent; omitted arguments change nothing) |
| `/msl semi`, `/msl active`, `/msl semiLOS` | `use` | Switch type; no extra arguments |
| `/msl super_active default` | `admin` | Any target, and clears the safilter |
| `/msl super_active entity\|player [set\|add\|remove\|clear] [values...]` | `admin` | Edit one safilter class; a bare value means `add` |
| `/msl super_active filter <list\|clear>` | `admin` | Show / clear the safilter |
| `/msl filter <entity\|player\|clear\|on\|off\|list> [set\|add\|remove\|clear] [values...]` | `use` | Target filter whitelist |
| `/msl on` / `/msl off` | `use` | Personal missile switch (session-only, on by default) |
| `/msl default` | `use` | Reset your own settings to default; keeps the **selected missile type** and the `on\|off` switch |
| `/msl global <on\|off>` | `admin` | Global switch, written back to `msl_config.yml` |
| `/msl reload` | `admin` | Reload both config files and the language files, and flush filter data to disk |

Type aliases: `ir`/`infrared`/`红外`/`1`, `semi`/`sarh`/`半主动`/`2`, `active`/`arh`/`主动`/`3`, `semiLOS`/`semi-los`/`los`/`驾束`/`线导`/`4`, `super_active`/`super`/`超级主动`/`5`.

Argument parsing stops at the first invalid argument and only applies what came before it. `remove` is the exception — entries that are valid but not in the list are only reported, never aborting the rest. Tab completion offers the full set per position, filtered by permission, and lists the existing entries after `remove`.

## 3. Missile Types

The table below lists the factory defaults of `types:` in `msl_config.yml`. Everything is configurable and applies after `/msl reload`.

| Parameter | `ir` Infrared | `semi` Semi-Active | `active` Active | `semiLOS` Beam-riding | `super_active` Super Active |
|---|---|---|---|---|---|
| Config key | `types.infrared` | `types.semi-active` | `types.active` | `types.semi-los` | `types.super-active` |
| Permission | `missile.use` | `missile.use` | `missile.use` | `missile.use` | **`missile.admin`** |
| Initial → max speed (m/s) | 5 → 25 | 5.2 → 30 | 6 → 40 | 5.2 → 30 | **8 → 850** |
| Acceleration (m/s·tick) | 1.0 | 1.2 | 1.5 | 1.2 | **4.0** |
| Turn rate (°/tick) | 6 | 4 | 7 | 12 | **30** |
| Guidance | Autonomous pure pursuit | Shooter keeps the crosshair on the target | Autonomous proportional navigation (lead) | Beam-riding: follows the shooter's line of sight | Same as Active |
| Must keep aiming after launch | No | **Yes** | No | **Yes** | No |
| Autonomous (re)acquisition | 55 blocks / 30° | 128 blocks / 10° | 70 blocks / 40° | None (no lock) | **100 blocks / 45°** |
| Countermeasure (must be **dropped**) | Blaze powder | Iron nugget | Iron nugget | None | None (immune) |
| Break-lock chance / interval | 15% / 1 s | 5% / 1 s | 2.5% / 1 s | — | 0 (immune) |
| Result of break-lock | Re-locks the dropped blaze powder, cannot re-lock the thrower for 5 s | Illumination link is permanently broken | Keeps 3 s of inertia, then re-acquires | — | Never breaks lock |
| Triggers enemy RWR | No | Yes | Yes | No | Yes |
| Warhead power | 3.0 | 3.5 | 4.5 | 3.5 | **10.0** |
| Flame / smoke particles | `FLAME` / `LARGE_SMOKE` | `SMALL_FLAME` / `CAMPFIRE_COSY_SMOKE` | `COPPER_FIRE_FLAME` / `WHITE_SMOKE` | `SOUL_FIRE_FLAME` / `SMOKE` | `COPPER_FIRE_FLAME` / `WHITE_SMOKE` |

- Speed conversion: `1 m/s = 1 block/s`. The projectile is a `SmallFireball` (`yield = 0`) plus flame/smoke particles — no display entity is attached.
- The super active missile at 850 m/s travels ≈ 42.5 blocks/tick and can skip the 3-block proximity fuse, so it mostly detonates by sweeping through the target.

## 4. Gameplay

- **Locking**: 10° crosshair cone, 128 blocks, line of sight not blocked, never yourself; the nearest candidate wins. Refreshed every 2 ticks.
- **Firing**: hold TNT in your main hand and right-click; one TNT is consumed per shot (not in creative). Swapping the main-hand item closes the seeker immediately.
- **Semi-active** refuses to fire without a valid lock; **semiLOS** locks nothing and follows a point 200 blocks along your crosshair.
- **While locked**, the action bar is wrapped on both sides by the `&f&k` padding (`launcher.lock-padding`, default `&f&k1`) with exactly one space between the padding and the text. Nothing is shown when there is no lock.
- The **Target** field shows the effective classes: `player`, `entity`, `Mixed` (both allowed) or `Any` (no concrete target in the list). Super active reads its safilter contents, so entries of both classes show `Mixed`.
- **Countermeasures must be dropped** (`decoy.require-thrown: true` by default) and stay valid for 3 seconds; holding them does nothing, and your own drops never affect your own missile.
- Proximity fuse is 3 blocks (semiLOS has none); a missile lives at most 30 seconds; at most 64 missiles fly at once.
- `/msl default` resets the IR mode, usefilter, SA mode and safilter, the seeker and the filter whitelist; the type and the `on|off` switch are left alone.

## 5. Target Filter

Every player owns a whitelist that decides what the seeker may lock (persisted across restarts). `off` means free targeting; `on` with an empty whitelist locks nothing, so the plugin refuses to enable it or turns itself back `off`.

| Command | Effect |
|---|---|
| `/msl filter entity [set\|add\|remove\|clear] [ID...]` | Entity class: `zombie` and `minecraft:zombie` both work; `boat` and `minecarts` are group aliases |
| `/msl filter player [set\|add\|remove\|clear] [name...]` | Player class: `set`/`add` accept online players only; `remove` also works offline |
| `/msl filter on` / `off` / `clear` / `list` | Enable / disable / clear everything / show details |

- `set` clears the whole filter before writing; `add` and a bare ID append; `remove` only drops entries of that class (it neither toggles the class nor changes the filter mode).
- Writing a class or an ID turns the filter mode `on`; emptying the whitelist turns it back `off`.
- **Vehicles, end crystals, armor stands and other non-living entities cannot be locked by default** — list their ID explicitly in the entity class.
- When filter mode (or `usefilter`) is active, the enabled whitelist classes decide what can be locked; two classes mean a two-stage "players first" lock.
- Entity IDs are always stored as full registry keys (`minecraft:oak_boat`); bare IDs from old saves are migrated on load.

### 5.1 safilter for super_active

An entirely separate list, **session-only and never persisted**.

| Command | Effect |
|---|---|
| `/msl super_active default` | Any target, and clears the safilter |
| `/msl super_active entity` / `player` | Creatures only / players only (excluding yourself), clearing the safilter |
| `/msl super_active entity\|player set\|add\|remove\|clear [values...]` | Replace / append / drop / clear that class |
| `/msl super_active filter list` / `clear` | Show the safilter / clear it and fall back to `default` |

An empty safilter means "unrestricted" for that class; non-living entities still have to be listed explicitly, and `minecraft:player` cannot be used as an entity ID.

## 6. RWR & MAWS

All three conditions must hold: you have `missile.use` or `missile.admin`, you ran `/msl on`, and you carry a **compass** anywhere in your inventory. Turning the switch off or removing the compass silences both immediately.

| Warning | Trigger | Display | Sound |
|---|---|---|---|
| RWR tracked | Someone has a semi-active / radar-guided seeker armed and locked on you (not fired yet) | Yellow BossBar `⚠ TRACKED →` | Alternating high/low tone every 0.2 s |
| RWR missile | An in-flight semi-active / radar-guided missile is locked on you | Red BossBar `⚠ MISSILE ↘ ×2` | Rapid high tone every 0.1 s |
| MAWS | An arrow / spectral arrow / trident / any missile within 40 blocks with a **closing speed > 10 m/s** (your own projectiles excluded) | Red BossBar `⚠ MAWS: ↑` | Off by default (`maws.sound.enabled`) |

- Bearings are relative to your facing, mapped to 8 arrows: `↑ ↗ → ↘ ↓ ↙ ← ↖` (`rwr.dir-0` … `rwr.dir-7`).
- RWR and MAWS are two independent BossBars and can be shown at the same time; with several threats the nearest one is shown with a `×count`.
- Infrared and semiLOS never trigger RWR, but they do trigger MAWS.

## 7. Configuration

| File | Section | Contents |
|---|---|---|
| `config.yml` | `language` | Language file (`zh_cn` / `en_us`) |
| | `messages` | Per-key overrides for any message in `lang/<language>.yml` |
| | `filter.storage` | `type` (`JSON` / `MYSQL`), save interval, JSON path, seven MySQL options |
| `msl_config.yml` | `missile` | `global-enabled`, `persist-global-switch` |
| | `launcher` | `lock-range`, `lock-cone`, `refresh-interval-ticks`, `lock-padding`, `muzzle-offset`, `max-active` |
| | `flight` | `max-life-ticks`, `proximity-fuse`, `inertial-memory-ticks`, `reacquire-delay-ticks` |
| | `decoy` | `interval-ticks`, `search-range`, `require-thrown`, `ttl-ticks`, `lockout-ticks` |
| | `beam` | `length`, `min-length` |
| | `particles` | Viewer range plus count/spread/speed for flame, inner flame, smoke and cloud, and the offsets |
| | `explosion` | `break-blocks` |
| | `types` | All performance values per type plus `color` |
| | `rwr` / `maws` | Toggles, ranges, sound intervals/volume/pitch; MAWS also has `closing-speed` and `include-own` |

- Deleting a line falls back to the built-in default without errors; numbers are clamped, and an unknown material or particle name warns once and falls back.
- `/msl global on|off` rewrites the `missile.global-enabled` line in `msl_config.yml` (comments preserved); set `missile.persist-global-switch: false` to disable the write-back.
- Filter data defaults to `plugins/Missile/data/filters.json`; MySQL falls back to JSON with a warning when the driver or the connection is unavailable.
- The personal `/msl on|off` switch, the `/msl ir` mode and the safilter are session-only and never saved.

## 8. PlaceholderAPI Placeholders

Installing PlaceholderAPI registers the expansion automatically (identifier `msl`, `persist(true)`).

| Placeholder | Meaning |
|---|---|
| `%msl%` | Display name of the current type (with its colour) |
| `%msl_type%` | Config id of the current type (`infrared` / `semi-active` …) |
| `%msl_entity_name%` | Name of the locked target; `searching` when unlocked |
| `%msl_locked%` / `%msl_lock%` | Whether a target is locked / the full lock sentence |
| `%msl_target_kind%` | Target field: `Mixed` / `Any` / `entity` / `player` |
| `%msl_maws%` | Bearing arrow of the nearest MAWS threat, empty when clear |
| `%msl_armed%` / `%msl_on%` / `%msl_global%` / `%msl_active%` | Seeker state / personal switch / global switch / missiles in flight |

The seeker ActionBar template `seeker.status` uses the same placeholders and is substituted internally, so it works without PlaceholderAPI.

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| Build fails with `AccessDeniedException ...\.m2\...` | Use `mvn -s .mvn/local-repo-settings.xml -B package` |
| Build fails with `placeholderapi ... cached from a remote repository ID that is unavailable` | The repository `id` in `pom.xml` must stay `placeholderapi` |
| Right-click places TNT / does nothing | You ran `/msl off`, or TNT is not in your main hand, or you lack `missile.use` |
| Semi-active will not fire | No valid target inside the crosshair cone, line of sight blocked, or the whitelist is empty |
| Cannot lock creatures / vehicles | Use `/msl ir entity`; vehicles and other non-living entities need `/msl filter entity set oak_boat` (or `boat`) |
| Nothing locks with usefilter on | Check `/msl filter list` for an empty whitelist, or run `/msl ir filteroff` |
| Missile breaks lock mid-flight / flies straight | Someone nearby dropped a countermeasure; or you use semiLOS; or the target died |
| No warnings at all | Requires permission + `/msl on` + a compass; infrared and semiLOS never trigger RWR |
| Config changes have no effect | Run `/msl reload` (needs `missile.admin`); a `language` change also needs a client reconnect |

## 10. Project Layout

```
.
├── README.md                          # this file (English)
├── README.zh-CN.md                    # Chinese version
├── pom.xml                            # paper-api + placeholderapi(provided)
├── .mvn/local-repo-settings.xml       # local repository settings for the build
├── scripts/                           # publish-to-github.ps1, check-line-endings.ps1
├── dist/                              # verification programs and release packages (gitignored, never in the jar)
└── src/main/
    ├── java/com/missile/
    │   ├── MissilePlugin.java         # main class: listeners, command, placeholders, tick task
    │   ├── MissileCommand.java        # /msl command (types / on|off / default / filter / status / global / reload)
    │   ├── MissileType.java           # factory defaults of the five types + radarHoming() + TargetKind
    │   ├── Missile.java               # one missile: guidance, countermeasures, particles, detonation
    │   ├── SemiLOSMissile.java        # beam-riding subclass
    │   ├── MissileManager.java        # registry of missiles in flight and launch dispatch
    │   ├── SeekerListener.java        # right-click arming / locking / firing, seeker and safilter state
    │   ├── SaProfile.java             # immutable lock profile of the super active missile
    │   ├── TargetSelector.java        # line-of-sight locking, nearest-in-cone selection, whitelist channel
    │   ├── TargetFilter.java          # target filter: data, command parsing, hit rules
    │   ├── FilterStorage.java         # filter persistence (JSON / MySQL, async + dirty check)
    │   ├── Settings.java              # single entry point for both config files (hot reloadable)
    │   ├── Lang.java                  # messages and colour conversion
    │   ├── MissilePlaceholders.java   # PAPI expansion and internal ActionBar substitution
    │   ├── RwrManager.java            # RWR + MAWS evaluation and polling
    │   ├── RwrDisplay.java            # one BossBar per slot
    │   └── RwrListener.java           # cleanup on quit / death / respawn
    └── resources/
        ├── plugin.yml                 # command, permissions, softdepend: [PlaceholderAPI]
        ├── config.yml                 # general options
        ├── msl_config.yml             # missile parameters
        └── lang/zh_cn.yml, en_us.yml  # messages
```

The player manual and the requirements document are not part of this repository (see `docs/latest/`).
