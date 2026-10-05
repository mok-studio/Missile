# Missile —— Paper 1.21.11 制导导弹插件

手持 TNT 右键开启导引头并锁定视线内目标，再右键发射。提供五种导弹：**红外**、**半主动雷达寻的**、**主动雷达寻的**、**半自动指令瞄准线（semiLOS，驾束 / 线导）**、**超级主动弹（super_active，默认仅 OP 可用）**。

受害者侧有两套告警：**RWR 雷达告警**（被半主动 / 雷达制导照射或锁定）与 **MAWS 导弹逼近告警**（40 格内有**接近率 > 10 m/s** 的箭 / 导弹逼近你），都是 BossBar + 方位箭头 + 可选告警音。

- 插件名：`Missile`　主类：`com.missile.MissilePlugin`　`api-version`：`1.21`
- 目标平台：**Paper 1.21.11**（Java 21）
- 实现方式：纯 Paper API，无 NMS、无反射（*验证程序*在 `dist/`，不属于插件产物）
- 全部玩家可见文案走 `lang/<语言>.yml`，内置 `zh_cn` 与 `en_us`，可用 `config.yml` 的 `messages:` 逐条覆盖
- 配置文件**两份**：`config.yml`（通用项）+ `msl_config.yml`（导弹参数，全部 gameplay 数值都在这里）

---

## 1. 环境要求

| 项目 | 要求 |
|---|---|
| 服务端 | Paper 1.21.11（用 `1.21.11-R0.1-SNAPSHOT` API 编译） |
| Java | 21（Paper 1.21.9+ 强制要求） |
| 构建 | Maven 3.9+ |
| 可选插件 | **PlaceholderAPI**（软依赖：装了才注册 `msl` 占位符扩展；不装也能正常运行） |

## 2. 构建

```bash
cd E:\ser_plugins\Missile
mvn -s .mvn/local-repo-settings.xml -B clean package
```

产物：`target/Missile-1.0.2.jar`

> **为什么带 `-s .mvn/local-repo-settings.xml`？**
> 本工作区默认的 Maven 本地仓库（`%USERPROFILE%\.m2\repository`）不可写，直接 `mvn package` 会以 `AccessDeniedException ... resolver-status.properties` 失败。仓库内附带的这份 settings 把本地仓库指向工作区内的 `.m2repo/`。若你的环境 `%USERPROFILE%\.m2` 可写，可直接用 `mvn -B package`。
>
> PlaceholderAPI 依赖需要仓库 id 就叫 `placeholderapi`（见 `pom.xml` 注释）——换成别的 id 会让 Maven 认定本地缓存"来自不可用仓库"从而构建失败。

## 3. 安装

1. 把 `target/Missile-1.0.2.jar` 复制到服务端 `plugins/` 目录。
2. 启动或重启服务端。
3. 控制台出现 `Missile 已启用：手持 TNT 右键开启导引头，再右键发射` 即为加载成功。
4. 首次启动会在 `plugins/Missile/` 生成**两份**配置：`config.yml` 与 `msl_config.yml`（语言文件释放到 `plugins/Missile/lang/`）。
5. 装了 PlaceholderAPI 时会额外打印 `已注册 PlaceholderAPI 扩展 msl：…`。

权限：`missile.use`（默认 `true`，控制命令与 TNT 导引头）、`missile.admin`（默认 `op`，控制 `super_active` / `global` / `reload`）。

## 4. 快速上手

| 步骤 | 操作 |
|---|---|
| 1 | `/msl ir`（或 `semi` / `active` / `semiLOS`）选型号；`/msl super_active` 需管理员 |
| 2 | **主手**手持 **TNT**，右键 → 开启导引头并锁定视线内目标 |
| 3 | 再右键 → 发射；每次发射消耗 1 个 TNT（创造模式不消耗） |

锁定期间动作栏实时显示（模板见 `lang/*.yml` 的 `seeker.status`，可覆盖）：

```
§7红外导弹 §7| §f目标: 玩家 §7| §a锁定 §fSteve §7| §f按右键发射
```

要点：

- 右键**不会**放置 TNT 方块：`PlayerInteractEvent` 里用 `setUseInteractedBlock(DENY)` + `setUseItemInHand(DENY)` 拦截。
- 锁定判定：先读准星射线命中的目标（`RayTraceResult#getHitEntity()`），再并入准星 **10° 锥角**内、无遮挡、**128 格**内的候选目标，取**距离最近者**（三项都是 `msl_config.yml` 的 `launcher.*`）。
- 导引头开启期间每 **2 tick** 刷新一次锁定；换掉主手 TNT 会自动关闭。
- **半主动雷达**在无锁定目标时**拒绝发射**并提示；**semiLOS 不需要锁定目标**（跟随准星）。
- 型号只能通过命令选择；右键不做型号循环，也不判定副手物品。
- **个人开关**：`/msl on|off` 只对你自己生效（默认开启，仅存内存）。关闭后你手持 TNT 右键**完全走原版逻辑**，已开启的导引头会被清空；其他玩家不受影响。**注意：关掉后你也收不到 RWR / MAWS 告警**（见第 12 节）。
- **目标筛选**：`/msl filter ...` 限定导引头只锁白名单内的目标，详见第 11 节。

## 5. 五种导弹

下表是 `msl_config.yml` 里 `types:` 的**出厂默认值**，全部可在配置里改（改完 `/msl reload` 生效）。

| 参数 | 红外 `ir` | 半主动 `semi` | 主动 `active` | 半自动指令瞄准线 `semiLOS` | 超级主动弹 `super_active` |
|---|---|---|---|---|---|
| 配置键 | `types.infrared` | `types.semi-active` | `types.active` | `types.semi-los` | `types.super-active` |
| 权限 | `missile.use` | `missile.use` | `missile.use` | `missile.use` | **`missile.admin`** |
| 初速 → 最大速度 | 5 → 25 m/s | 5.2 → 30 m/s | 6 → 40 m/s | 5.2 → 30 m/s | **8 → 850 m/s** |
| 加速度 | 1.0 m/s 每 tick | 1.2 | 1.5 | 1.2 | **4.0** |
| 转弯率 | 6°/tick | 4°/tick | 7°/tick | 12°/tick | **30°/tick** |
| 制导方式 | 自主，纯追踪 | 发射者准星持续照射 | 自主，比例导引（打提前量） | 驾束：跟随发射者视线 | 同主动雷达 |
| 发射后需持续瞄准 | 否 | **是** | 否 | **是**（持续给视线） | 否 |
| 自主（重）截获 | 55 格 / 30° | 128 格 / 10°（照射） | 70 格 / 40° | 无（不锁目标） | **100 格 / 45°** |
| 干扰物 | 烈焰棒 | 铁粒 | 铁粒 | 无 | **无（免疫）** |
| 脱锁概率 / 判定周期 | 15% / 1 秒 | 5% / 1 秒 | 2.5% / 1 秒 | 无 | **0（免疫）** |
| 脱锁后果 | 改锁并追踪干扰者 | 照射链路**永久**中断 | 记 3 秒惯性后自主重截获 | 无 | 不会脱锁 |
| 触发敌方 RWR | 否 | 是 | 是 | 否 | **是** |
| 战斗部威力 | 3.0 | 3.5 | 4.5 | 3.5 | **10.0** |
| 尾焰 / 尾烟粒子 | `FLAME` / `LARGE_SMOKE` | `SMALL_FLAME` / `CAMPFIRE_COSY_SMOKE` | `COPPER_FIRE_FLAME` / `WHITE_SMOKE` | `SOUL_FIRE_FLAME` / `SMOKE` | `COPPER_FIRE_FLAME` / `WHITE_SMOKE` |

速度换算：`1 m/s = 1 格/秒 = 0.05 格/tick`，插件按住 tick 设置弹体速度，因此游戏内速度严格等于上表数值。

### 制导细节

- **红外**：发射后完全自主，纯追踪目标当前位置（无提前量）；目标死亡或丢失时在前方 55 格 / 30° 锥内重新截获最近目标。
- **半主动**：每 tick 用发射者准星重新指定目标（128 格 / 10° 锥内最近者），准星附近多个目标时自动切换；移开视线即失去制导，重新瞄准可恢复。
- **主动**：比例导引——按目标**实测位移**解算提前量（最大预测 60 tick），并保留 5 秒惯性记忆指向最后已知位置；脱锁 3 秒后自主重截获。单 tick 位移超过阈值（防作弊）时不外推。
- **半自动指令瞄准线（semiLOS）**：不锁定目标，每 tick 读发射者视线，`rayTraceBlocks` 取视线前方 200 格处的瞄准点（被方块挡住时取命中点、下限 8 格），以 12°/tick 收敛。不切换目标、不判定干扰、**无近炸引信**（只在弹体命中时引爆）。发射者离线 / 换维度后沿最后方向继续飞直至命中或 30 秒自毁。
- **超级主动弹（super_active）**：与主动雷达**共用同一套制导**（`MissileType#radarHoming()` 统一判定）；区别在参数（100 格 / 45° 重截获、30°/tick、威力 10）与 **`missile.admin` 权限**。
  - **免疫一切干扰**：`decoyChance = 0`，`Missile#checkDecoy()` 直接返回。
  - 会触发敌方 RWR（跟踪 + 导弹两级）与 MAWS。
  - 注意：**开导引头那一下的初始锁定**仍走全型号共用的 128 格 / 10° 锥（`launcher.lock-range/lock-cone`）；100 格 / 45° 只用于**弹上自主重截获**。
  - 物理注意：**850 m/s = 42.5 格/tick**，3 格近炸引信可能被"跳过"，实际引爆主要靠弹体扫掠命中。`msl_config.yml` 的 `super-active` 段里已就此写了提示（可调低 `max-speed` 或把 `model.teleport-duration-ticks` 设为 0）。

### 干扰（对抗）机制

- 判定对象：**主手或副手**持有对应干扰物的玩家，位于导弹 `flight.decoy-search-range`（默认 48）格内、且在导引头**前方**（方向点积 > 0）；排除发射者本人与旁观模式玩家。
- 判定频率：`flight.decoy-interval-ticks`（默认 20 tick = 1 秒）一次，每次独立按概率判定（按 tick 判定会让 15% 被放大成近乎必中）。
- 红外被干扰后**改锁干扰者**；主动脱锁后飞向惯性记忆点，`flight.reacquire-delay-ticks`（默认 60 tick）后自主重截获。
- semiLOS 与 super_active **不参与**干扰判定。
- 每次触发都会通过动作栏通知发射者（文案 `missile.decoy-*`）。

## 6. 命令与权限

| 命令 | 权限 | 说明 |
|---|---|---|
| `/msl`、`/msl status` | `missile.use` | 查看状态：型号 + 参数、锁定目标类型、导引头、个人开关、全服开关、IR 模式、SA 模式 + **safilter 内容**、筛选模式 |
| `/msl ir [default\|player\|entity] [usefilter\|filteroff]` | `missile.use` | 红外 + 工作模式 + 白名单通道（见下） |
| `/msl semi`、`/msl active`、`/msl semiLOS` | `missile.use` | 仅切换型号（不支持额外参数，多余参数按容错规则忽略） |
| `/msl super_active [default\|entity [ID]\|player [名字]\|filter <list\|clear>]` | `missile.admin` | 超级主动弹 + **safilter**（独立名单，见第 11.5 节） |
| `/msl filter <entity\|player\|clear\|on\|off\|list> [set\|add\|clear] [ID...]` | `missile.use` | 目标筛选（第 11 节） |
| `/msl on` / `/msl off` | `missile.use` | 个人导弹开关（默认开，仅存内存） |
| `/msl global <on\|off>` | `missile.admin` | 全服开关；**会把新值写回 `msl_config.yml`**（只替换那一行、保留注释），所以重启后仍然记得 |
| `/msl reload` | `missile.admin` | 重读 `config.yml` + `msl_config.yml` + 语言文件，并把筛选数据落盘 |

别名 `/msl` ≡ `/missile`。**`plugin.yml` 故意不给命令声明 `permission`**（否则只带 `missile.admin` 的管理员会被 `missile.use` 挡住）；权限由代码按子命令校验。

### 参数容错（全局规则）

从左往右解析，**遇第一个非法参数即停止，只执行非法参数之前的部分**（被忽略的部分静默处理）：

- `/msl super_active entity <乱写>` → 模式生效、ID 被拒绝（前缀容错）；`/msl super_active player 114514` ≡ `/msl super_active player`
- `/msl ir 114514` ≡ `/msl ir`（不改变任何设置）
- `/msl ir entity usefilter filteroff` → 只执行到 `usefilter`（第 4 个参数被忽略）

### `/msl ir` 的模式与白名单通道（**相互独立**）

| 输入 | 工作模式 | usefilter |
|---|---|---|
| `/msl ir` | 不变 | 不变 |
| `/msl ir player` | 玩家优先 | 不变 |
| `/msl ir entity` | 玩家优先，丢失玩家后转任意实体 | 不变 |
| `/msl ir default` | 恢复默认（玩家优先） | 不变 |
| `/msl ir player usefilter` | 玩家优先 | **开** |
| `/msl ir player filteroff` | 玩家优先 | **关** |
| `/msl ir usefilter` / `/msl ir filteroff` | 不变 | 开 / 关 |

- **`usefilter` 决定"能锁谁"（候选目标集合换成 `/msl filter list` 的白名单），不改变"怎么锁"**：128 格 / 10° 锥 / 视线可达规则照旧生效。
- 白名单为空 = 谁都不能锁。
- 这两个设置是**会话级**的（退出登录或重启即恢复默认），与 `/msl on|off` 一致。

### 型号别名

`ir` / `infrared` / `hongwai` / `红外` / `1`；`semi` / `sarh` / `semi-active` / `semi_active` / `半主动` / `2`；`active` / `arh` / `主动` / `3`；`semiLOS` / `semi_los` / `semi-los` / `los` / `line` / `指令` / `驾束` / `线导` / `4`；`super_active` / `superactive` / `super-active` / `super` / `超级主动` / `5`。

### Tab 补全

按位置给出全集并按权限过滤：位置 1 = 型号 / `on` / `off` / `filter` / `status`（管理员另有 `super_active` / `global` / `reload`）；位置 2 = 按类型给模式、或 `set|add|clear|list`、或 `on|off`；位置 3 = `set|add|clear` / `usefilter|filteroff`；位置 4 = 在线玩家名或全部实体 ID。

## 7. 配置

### 7.1 `plugins/Missile/config.yml`（通用项）

| 板块 | 内容 |
|---|---|
| `language` | 语言文件（内置 `zh_cn` / `en_us`） |
| `messages` | **按 key 覆盖** `lang/<语言>.yml` 里的任意文案（嵌套或扁平写法都支持，扁平写法要加引号） |
| `filter.storage` | 筛选数据持久化：`type`（`JSON` / `MYSQL`）、`auto-save-interval-seconds`、`json-file`、`mysql.*`（host / port / database / user / password / table / use-ssl） |

```yaml
language: zh_cn
messages:
  # "seeker.status": "&7%msl% &7| &f目标: %msl_target_kind% &7| &a锁定 &f%msl_entity_name% &7| &f按右键发射"
  # "maws.bossbar": "&c⚠ MAWS: %msl_maws%"
filter:
  storage:
    type: JSON
```

- 筛选数据（含 `on`/`off` 模式）**会持久化**：JSON 默认零依赖；MySQL 需服务端自备 `mysql-connector-j`，**检测不到驱动或连接失败会告警并自动回退 JSON**（本次运行内不再重试）。
- 玩家个人的 `/msl on|off` 开关、`/msl ir` 模式都不会写配置文件。
- **全服开关**（`/msl global on|off`）默认会写回 `msl_config.yml`：只替换 `missile.global-enabled` 那一行的值，**注释、空行、键顺序、CRLF/LF 全部保留**（不用 `saveConfig()`，那会重写整个文件并丢掉所有注释）；写盘用"临时文件 + 原子替换"。想回到"只存内存、重启回配置初值"的旧行为，把 `missile.persist-global-switch` 设为 `false`。

### 7.2 `plugins/Missile/msl_config.yml`（导弹参数）

| 板块 | 键数 | 内容 |
|---|---|---|
| `missile` | 2 | `global-enabled`（全服开关值，`/msl global` 会原地改写它）、`persist-global-switch`（是否写回，默认 `true`） |
| `launcher` | 6 | `lock-range`、`lock-cone`、`refresh-interval-ticks`、`lock-padding`（锁定时 ActionBar 两端包裹段，默认 `" &f&k1"`）、`muzzle-offset`、`max-active` |
| `flight` | 4 | `max-life-ticks`、`proximity-fuse`、`inertial-memory-ticks`、`reacquire-delay-ticks` |
| `beam` | 2 | `length`、`min-length`（驾束） |
| `particles` | 15 | `viewer-range` + 尾焰 / 内焰 / 尾烟 / 云 各 3 项（数量、扩散、附加速度）+ `back-offset`、`smoke-back-multiplier` |
| `explosion` | 1 | `break-blocks`（`false` = 只伤害实体不破坏地形） |
| `types` | 5 × 14 | 五种型号的全部性能参数（见第 5 节）+ **`color`**（型号名颜色，`%msl%` / `{missile}` 输出时自带） |
| `rwr` | 10 | `enabled`、`alert-range`、`sound.enabled`、`sound.track-interval-ticks`（默认 4 = 0.2s）、`sound.missile-interval-ticks`（默认 2 = 0.1s）、音量与音调 ×4 |
| `maws` | 6 | `enabled`、`range`（默认 40）、`closing-speed`（默认 10 m/s）、`include-own`（默认 false）、`sound.enabled`（默认 false）、`sound.interval-ticks` |

- **删掉某一行 = 该项回落到插件内置默认值**，不会报错（有自动化测试覆盖）。
- 数值会做防呆夹取（速度 / 距离 ≥ 0、概率夹 `[0,1]`、锥角夹 `[0,180]`、粒子数量 ≥ 0…）。
- 材质 / 粒子名写错会在控制台**告警一次**并回落默认值。
- 改完用 `/msl reload` 热重载，**不必重启**。

## 8. 行为与限制

- **实体方案**：`SmallFireball` 作为弹体（承担运动、碰撞与 `ProjectileHitEvent`，`yield = 0`、`incendiary = false`），**不挂任何外形实体**——末地烛 `BlockDisplay` 模型已按要求移除（`model` 段随之从配置里删除），现在导弹 = 弹体（小的火球实体本体）+ 尾焰尾烟粒子。
- **粒子**：尾焰在弹尾 `particles.back-offset`（默认 0.55）格、尾烟在其 `smoke-back-multiplier`（默认 3）倍处，每 tick 生成；仅当 `particles.viewer-range`（默认 96）格内有玩家时才生成。
- **近炸引信**：距锁定目标 `flight.proximity-fuse`（默认 3）格内自动引爆；**semiLOS 无目标，不适用近炸**。
- **寿命**：单发最长 `flight.max-life-ticks`（默认 600 tick = 30 秒），超时自毁。
- **同屏上限**：`launcher.max-active`（默认 64）发，超出时丢弃最旧的一发。
- **关服 / reload**：在飞导弹被静默移除，不产生爆炸。

## 9. 调参索引

**所有 gameplay 数值都在 `msl_config.yml`（服主用 `/msl reload` 热重载即可，不需要改代码 / 重新编译）。**

| 想改什么 | 位置 |
|---|---|
| 初速、最大速度、加速度、转弯率、干扰物与概率、威力、截获距离/锥角、粒子类型、自主与重锁 | `msl_config.yml` → `types.<型号>.*` |
| 锁定距离、准星锥角、刷新周期、出膛前移、同屏上限 | `msl_config.yml` → `launcher.*` |
| 寿命、近炸半径、惯性记忆、干扰判定周期与搜索半径、重截获延迟 | `msl_config.yml` → `flight.*` |
| 驾束束长与下限 | `msl_config.yml` → `beam.*` |
| 尾焰尾烟数量 / 扩散 / 可见距离 / 偏移 | `msl_config.yml` → `particles.*` |
| ~~导弹外形~~ | 末地烛 `BlockDisplay` 模型**已按要求移除**（现在只有 `SmallFireball` 弹体 + 尾焰尾烟粒子），`model` 段已从配置里删除 |
| 是否破坏地形 | `msl_config.yml` → `explosion.break-blocks` |
| 告警总开关、距离、声音间隔 / 音量 / 音调 | `msl_config.yml` → `rwr.*` |
| MAWS 开关、触发距离、**接近率阈值**、是否算自己射的、告警音 | `msl_config.yml` → `maws.*` |
| 语言、文案覆盖、筛选数据存储 | `config.yml` |
| 全部显示文案（含 RWR / MAWS 方位箭头 `rwr.dir-0` … `rwr.dir-7`） | `src/main/resources/lang/zh_cn.yml`、`lang/en_us.yml`（也可用 `config.yml` 的 `messages:` 覆盖） |
| 型号是否按雷达制导走（提前量 / 惯性记忆 / 触发 RWR 的归类） | 代码 `MissileType#radarHoming()`；使用点在 `Missile.java` 与 `RwrManager.java`（**这两处不含数值，只含归类**） |
| 防作弊阈值（单 tick 位移上限）与模型刷新阈值 | 代码 `Missile.java` 的 `MAX_OBSERVED_STEP_SQUARED` / `MODEL_REFRESH_DOT`（**有意不开放**，属内部安全 / 性能阈值） |
| 目标筛选的命中规则、实体 ID 校验 | 代码 `TargetFilter.java` 的 `canTarget` / `isWhitelisted` / `parse` / `describe` |

> 只有"出厂默认值"留在 `MissileType` 枚举里当兜底：配置里删掉某一行不会崩，会回到枚举里的数值。

## 10. 故障排查

| 现象 | 原因与处理 |
|---|---|
| 构建报 `AccessDeniedException ...\.m2\...` | 本地仓库不可写，改用 `mvn -s .mvn/local-repo-settings.xml -B package` |
| 构建报 `placeholderapi ... cached from a remote repository ID that is unavailable` | `pom.xml` 里那个仓库的 `id` 被改过了，必须保持 `placeholderapi` |
| 插件未加载 / `UnsupportedClassVersionError` | 必须 Java 21 + Paper 1.21.11 |
| 升级后导弹参数"变回默认了" | `msl_config.yml` 是新文件，老 `config.yml` 里的 `missile` / `model` / `types` / `rwr` / `explosion` **不会自动迁移**，需要手工对照搬过来 |
| 右键放出了 TNT 方块 | 你自己 `/msl off` 过；或没拿**主手** TNT；或没有 `missile.use` 权限 |
| 半主动弹发射不了 | 准星锥内没有合法目标（或视线被挡 / 白名单为空）—— 先看向目标再右键 |
| 锁不到生物 | `/msl ir entity` 或 `/msl super_active entity`（`entity` = 非玩家生物；盔甲架等用 Entity 管线但需白名单/safilter 显式列出） |
| 用了 usefilter 之后什么都锁不上 | `/msl filter list` 看白名单是不是空的；`/msl ir filteroff` 关掉白名单通道 |
| 想把船 / 矿车打掉却锁不上 | 载具默认不在基线里：`/msl filter entity set oak_boat`（或组别名 `boat`）显式列出后就能锁 |
| 加了 `phantom` 却连幻翼都锁不上 | 已在 2026-10-05 修复：筛选模式打开时白名单启用的类别优先。若还不行，看 `/msl filter list` 里"启用类型"是否为 `entity`、且模式是 `仅锁定筛选白名单内目标` |
| `/msl filter on` 提示"白名单是空的" | 这是故意的：空白名单 + 筛选模式 = 什么都锁不上。先 `/msl filter entity <ID>` 或 `/msl filter player <名字>` |
| 导弹中途脱锁 | 附近有人手持烈焰棒 / 铁粒，属预期抗干扰；换抗干扰更强的型号，或让导弹免疫干扰 |
| 导弹直飞不追踪 | 用的是 `semiLOS`（本来就是手动驾束）；或目标已死 / 前方锥内无目标 |
| 导弹没直接命中也炸了 | 红外 / 半主动 / 主动 / 超主动都有近炸引信（`flight.proximity-fuse`）；semiLOS 例外 |
| 地形被炸 | `msl_config.yml` 的 `explosion.break-blocks` 改为 `false` |
| 完全没有告警 | 需要**同时**满足：有 `missile.use` 或 `missile.admin`、自己 `/msl on`、背包里有指南针；另外红外与 semiLOS **不触发 RWR**（但会触发 MAWS） |
| 箭朝我飞来也报警了 | 设计如此：MAWS 认**箭 / 三叉戟 / 任何导弹**，只要接近率 > 10 m/s |
| 自己打出去后自己也报警 | 已修：默认排除**你自己射出**的箭 / 导弹（`maws.include-own: false`），且只在"正在逼近"时才报 |
| `/papi` 里看不到 `msl` | 服务端没装 PlaceholderAPI，或该 identifier 被别的扩展占用（看控制台告警） |
| 改配置没生效 | 用 `/msl reload`（需要 `missile.admin`）；`language` 改变需要 reload 后客户端重连才完全生效 |
| `/msl global off` 后重启又变回开启 | 检查 `msl_config.yml` 的 `missile.persist-global-switch`（`false` = 只存内存）；或看控制台有没有"未能把全服导弹开关写回"的告警 |

## 11. 目标筛选系统

每个玩家一份**白名单**，决定导引头能锁定哪些目标（**会持久化**，见第 7.1 节）。

数据模型：

| 字段 | 含义 |
|---|---|
| `enabled` | 筛选模式开关。`off` = 自由锁定（筛选数据保留但被忽略） |
| `entity` 类 | `entityTypeIncluded`（"任意生物"）+ 实体 ID 白名单 |
| `player` 类 | `playerTypeIncluded` + 玩家白名单；**名单为空 = 允许所有玩家**，非空 = 只允许名单内玩家 |

命令：

| 命令 | 说明 |
|---|---|
| `/msl filter entity [set\|add\|clear] [<实体ID...>]` | 实体类白名单（`zombie` 与 `minecraft:zombie` 均可） |
| `/msl filter player [set\|add\|clear] [<玩家名...>]` | 玩家类白名单（只接受**当前在线**名字，同时记 UUID + 名字） |
| `/msl filter clear` | 清空全部筛选数据（**不动** `on`/`off`） |
| `/msl filter on` / `off` | 启用 / 停用筛选模式 |
| `/msl filter list` | 只读查看模式与明细 |

规则：

- `set` = **先清空全部**筛选数据再写入本次类型与 ID；`add` 与"直接跟 ID"都是追加。
- `set` / `add` 缺后续必选 ID → 该 `set` / `add` **整体无效**（不清空、不写入）。
- 成功写入类型或 ID 会**自动把模式置为 `on`**（否则命令看起来"没生效"）。
- **`on` 而白名单全空 = 什么都锁不上**，所以这条状态被从三个方向堵住了：① 空白名单时 `/msl filter on` **拒绝开启**并说明原因；② `clear` / `entity clear` / `player clear` 把数据清空时会**自动把模式关回 `off`** 并提示；③ 读盘时若发现老存档是"`on` + 空名单"也会纠正为自由锁定。**不变量：`enabled = true` ⟹ 白名单非空。**
- `minecraft:player` 不能作为实体 ID（有专门报错）。
- 已发射导弹的**弹上重截获**同样遵守发射者的筛选（`TargetSelector#selectEntity` 与 `#acquireEntityInCone` 两个入口都判定）。
- 与 `/msl ir ... usefilter` 的关系：`usefilter` 打开时**无视 `on`/`off` 开关**，直接把白名单当作候选集合（空 = 谁都锁不上）。

### 非生物实体：指定 ID 才能锁（需求 3.9）

默认基线是"友好 / 中立 / 敌对生物"——**载具、末地水晶、盔甲架、展示实体等一律锁不上**。但只要在 `entity` 类白名单里**显式写了它的实体 ID**，就无视这条限制：

```
/msl filter entity set oak_boat        # 现在可以锁橡木船了
/msl filter entity set end_crystal     # 末地水晶
/msl filter entity set armor_stand     # 盔甲架（默认也算"非生物基线"）
/msl filter entity add item_frame      # 展示框
```

- **组别名**：`/msl filter entity set boat` 等价于"所有船"（`oak_boat` / `spruce_boat` / … / `bamboo_raft` 以及各自的 `chest` 变体），`minecart` 组别名 `minecarts` 同理。存在的理由：**1.21.11 已经没有 `EntityType.BOAT`**（1.19 起船按木材拆开，`boat` 只剩实体标签），需求里举的例子 `minecraft:boat` 在 1.21.11 并不是一个实体类型，所以插件把 `boat` 做成**按后缀动态匹配当前注册表**的组别名（Mojang 以后加新木材也不用改代码）。写入时展开成实际 ID，回执会提示"已展开为 N 个实体 ID"。
- **筛选模式打开时，"能锁哪一类"以白名单启用的类别为准**（用户 2026-10-05 反馈后定稿）：`/msl filter entity add phantom` 之后，就算当前型号是"只锁玩家"的红外默认模式，**幻翼也能锁上**；玩家类没启用时玩家仍不可锁（这是白名单的正常语义）。**筛选关闭时**才回到导弹自己的锁定类型（`/msl ir` 的 default/player/entity）。两类都启用时按"**玩家优先**"两段式锁定。
- 制导细节：非生物目标没有"眼睛"，瞄准点改用**碰撞箱中心**；生物仍然用眼睛位置，所以原有手感不变。

### 11.5 `super_active` 的 safilter（独立名单）

超级主动弹（SA）有一套**与全局 filter 完全独立**的名单，叫 **safilter**：它的设置互不影响、也**不持久化**（会话级，退出登录即回到默认）。

```
/msl super_active default             # 回到"任意目标"（并清空 safilter）
/msl super_active entity              # 只锁非玩家生物（清空 safilter）
/msl super_active entity zombie       # 只锁僵尸（写进 safilter，可多次追加）
/msl super_active entity boat         # 组别名同样可用（展开成所有船）
/msl super_active player              # 只锁玩家（除自己）
/msl super_active player Steve        # 只锁 Steve（只接受在线玩家）
/msl super_active filter list         # 查看 safilter（模式 + 实体 + 玩家）
/msl super_active filter clear        # 清空 safilter 并自动退回 default
```

- **不带 ID/名字 = 不限制**（除玩家外的任意实体 / 除自己外的任意玩家），所以"想换成另一个 ID"要先 `filter clear` 或直接再来一次 `entity <新ID>`（会**追加**）。
- **`minecraft:player` 不能当实体 ID**（玩家请用 `player` 参数）。
- **非生物**（船 / 末地水晶 / 盔甲架…）即使在 `default`（任意）下也要**在 safilter 里显式列出**才能锁。
- SA 的三种模式**不看全局 filter**；`/msl status` 里能看到当前模式与 safilter 内容。

### 11.6 实体 ID 用**完整注册 ID**

- 白名单/safilter 里存的、Tab 补全给的都是**服务端实体注册表的完整键**，例如 `minecraft:oak_boat`、`minecraft:zombie`（来源是 Paper 的 `RegistryAccess`，取不到时回退 `EntityType.values()`，两者键集合一致）。
- 输入**允许简写**：写 `zombie`、`ZOMBIE`、`minecraft:zombie` 都行，写入时统一规范化成 `minecraft:zombie`。
- **升级兼容**：老存档（`filters.json` / MySQL）里存的裸 ID（`oak_boat`）会在**读取时自动迁移**成完整键，不需要手工改数据。
## 12. RWR + MAWS 告警系统

**开机条件（RWR 与 MAWS 完全相同，三条全满足）**：

1. 拥有 `missile.use` **或** `missile.admin`（admin 默认拥有所有权限，不需要再单独授予 `use`）；
2. 你自己用 `/msl on` 开了导弹模式（**每 tick** 判定，`/msl off` 立即静默）；
3. 背包里（任意槽位）有**指南针 `minecraft:compass`**（每 5 tick 复查一次）。

> 语义后果：**受害者自己 `/msl off` 之后就收不到任何告警**（已与需求方确认接受）。取出指南针同样立即静默。

| 告警 | 触发条件 | 显示 | 声音 |
|---|---|---|---|
| **RWR 敌跟踪** | 某玩家开着导引头，型号为半主动 / 雷达制导，当前锁定目标是你（尚未发射） | **黄色** BossBar：`⚠ 敌跟踪 →` | 高 / 低音交替，每 **0.2s**（4 tick） |
| **RWR 敌导弹** | 某发在飞的半主动 / 雷达制导导弹，其锁定目标是你 | **红色** BossBar：`⚠ 敌导弹 ↘ ×2` | 急促高音，每 **0.1s**（2 tick） |
| **MAWS** | 附近（**40 格**内）有**接近率 > 10 m/s** 的**箭 / 光灵箭 / 三叉戟**或**任何在飞的导弹**（不限型号，也不要求以你为目标）；你自己射出的不报警 | **红色** BossBar：`⚠ MAWS: ↑` | 默认**关**（`maws.sound.enabled`），打开后复用敌导弹音色 |

- **接近率** = 该实体**相对你**的速度，投影到"它 → 你"方向上的分量（格/秒）：正 = 正在逼近才告警，负（远离）不告警。所以**发射者自己刚打出去、正在飞离的弹不会报自己**；调 `maws.closing-speed: 0` 就退回"只要在范围内就报"。
- `maws.include-own: true` 可以让**自己射的**弹也报警（默认 `false`，因为发射后弹就从你身上飞出去，报警会很吵）。
- MAWS 覆盖**所有**在飞导弹（含红外与 semiLOS）与**所有箭类**（含骷髅射的箭、发射器射的箭、三叉戟）；RWR 仍只认半主动 / 雷达制导。

- 方位以**你的朝向为正前方（↑）**，按 45° 扇区映射到 8 档：`↑` 正前 / `↗` 右前 / `→` 正右 / `↘` 右后 / `↓` 正后 / `↙` 左后 / `←` 正左 / `↖` 左前（对应 12点钟 / 1:30 / 3点钟 / 4:30 / 6点钟 / 7:30 / 9点钟 / 10:30）。箭头文案在 `rwr.dir-0` … `rwr.dir-7`，想换成文字只需改这 8 个值。
- **RWR 与 MAWS 是两条独立的 BossBar**，可以同时显示（不会互相顶掉）；RWR 内部敌导弹优先于敌跟踪，多威胁时取**最近者**并附 `×数量`。
- **会触发 RWR** 的型号：半主动、主动、超级主动弹；**红外与 semiLOS 不触发**（但会触发 MAWS）。
- RWR 距离上限 `rwr.alert-range`（默认 256 格）；MAWS 距离 `maws.range`（默认 40 格）+ 接近率 `maws.closing-speed`（默认 10 m/s）。
- **MAWS 会为箭报警**（骷髅的箭、发射器的箭、三叉戟）：只要它相对你的接近率大于 10 m/s 且在 40 格内 —— 这也是"被远程压制"时最实用的预警。
- 显示用 BossBar 而非动作栏，避免与导引头状态提示互相覆盖；退出 / 死亡 / 重生会立即清理。
- 实现上**轮询**现有状态（导引头状态 + 在飞导弹列表），不侵入制导逻辑，方位取导弹**实时位置**。

## 13. 多语言与语言文件

- 位置：`plugins/Missile/lang/<language>.yml`，由 `config.yml` 的 `language` 指定（默认 `zh_cn`）。
- 内置 `zh_cn` 与 `en_us`（**key 数 113 : 113，逐条对齐**）；首次启动释放到数据目录，可直接改，也可复制一份做新语言（`lang/ja_jp.yml` + `language: ja_jp`）。
- 文案优先级：`config.yml` 的 `messages:` 覆盖 → `lang/<语言>.yml` → 返回 key 本身（并在控制台告警一次，便于发现漏翻）。
- 颜色：`&` 代码（`&6` `&c` `&l` …）+ 十六进制 `&#RRGGBB`（也支持原版 `&x&R&R&G&G&B&B` 写法）。
- `{name}` 是 Lang 的占位符；`%msl_xxx%` 是 PlaceholderAPI 占位符（见第 14 节），两者互不干扰。
- 硬编码例外只有两类：Lang 自身的诊断日志（缺 key / 加载失败，走 Lang 会循环依赖）与命令**输入**别名（`红外` / `半主动` / `指令` 等属命令关键字）。

## 14. PlaceholderAPI 占位符

装了 PlaceholderAPI 就**自动注册**（identifier `msl`，`persist(true)`），扩展随插件打包，**不需要手动装**。

| 占位符 | 含义 |
|---|---|
| `%msl%` | 当前型号显示名（`红外导弹` / `主动雷达寻的` …） |
| `%msl_type%` | 当前型号的配置 id（`infrared` / `semi-active` / `super-active` …） |
| `%msl_entity_name%` | 导引头锁定的目标名（玩家显示玩家 ID）；**无锁定时显示"搜索中"** |
| `%msl_locked%` | 当前是否有锁定（`true` / `false`） |
| `%msl_lock%` | 整句锁定状态（"锁定 X" / "搜索中"） |
| `%msl_target_kind%` | 「目标」字段：`混合` / `任意` / `实体` / `玩家`（按**实际生效类别**；SA 看 safilter） |
| `%msl_maws%` | MAWS 最近威胁的方位箭头；无威胁时空串 |
| `%msl_armed%` | 导引头是否已开启 |
| `%msl_on%` | 该玩家个人导弹开关 |
| `%msl_global%` | 全服导弹开关 |
| `%msl_active%` | 当前在飞导弹数量 |

- **TAB 插件**：`placeholder-output-replacements` 只是对占位符**输出值**做字符串替换，扩展注册成功后直接写 `%msl%` 之类即可，无需额外配置。
- 导引头 ActionBar 的模板本身就用了这套占位符（`seeker.status`），但**由插件内部替换**，所以没装 PlaceholderAPI 的服务器也照常显示；两边取值逻辑同一份代码，不会出现两套口径。
- MAWS 告警条文本里的 `%msl_maws%` 同样会被替换成方向箭头。

## 15. 当前验证状态

- 构建：`mvn -s .mvn/local-repo-settings.xml -B clean package` → `BUILD SUCCESS`。
- **自动化验证：`dist/` 下 13 个一次性验证程序，共 599 项断言全绿**（它们不属于插件产物，`dist/` 已 gitignore；另有早期的 `ColorCheck` / `RotationCheck` / `storagecheck` 可复跑）：

| 程序 | 项数 | 覆盖 |
|---|---|---|
| `TypeConfigCheck` | 161 | 型号参数配置化：出厂配置与枚举默认值逐项一致、改配置生效、夹取、坏值回落 |
| `GameplayCheck` | 53 | `launcher`（含 `lock-padding`）/ `flight` / `beam` / `particles` / `decoy`（含 `require-thrown`/`ttl-ticks`/`lockout-ticks`）/ `maws` / `explosion` 的读取、改值、夹取、缺段默认值 + 源码里旧常量 0 残留 + **锁定包裹纯函数 `padLocked` 四种形态** |
| `PersistCheck` | 38 | **全服开关写回配置**：只动一行、注释/空行/键顺序/CRLF 逐字节保留、幂等、缺键插入、无 `missile:` 段则拒写 |
| `VehicleCheck` | 43 | **非生物实体锁定 + 筛选类别优先级**：显式 ID 才放行、组别名 `boat`/`minecarts` 展开、**筛选打开时白名单类别说了算（含"add phantom 后幻翼可锁"的回归用例）**、瞄准点（生物=眼睛 / 非生物=碰撞箱中心） |
| `PlaceholderCheck` | 43 | 占位符取值、ActionBar 渲染、**「目标:」文案（混合/任意/实体/玩家）与实际生效类别口径**、**驱动 PAPI 真实 `CharsReplacer` 分发** |
| `MawsCheck` | 30 | **MAWS 接近率**：迎面/远离/切向/相对速度的数学、距离与阈值门限（含「刚好 10 不算」）、自己射出的箭/导弹排除、配置默认值与夹取 |
| `SaCheck` | 47 | **SA 判定与 safilter**：三模式 × 空/非空名单、非生物需显式列出、锁不到自己、名字兜底、**快照不可变**、会话状态增删、旧 `saKind` 折算、**SA 不受全局 filter 影响**、**弹上重新截获走快照**（假世界驱动真实 `acquireEntityInCone`） |
| `DecoyCheck` | 28 | **干扰机制**：掉落物打标与 TTL（含"刚好等于 ttl 仍有效"、过期条目被清理、未打标不算）、干扰源→投掷者解析、脱锁保护、型号干扰物接线、**`findDecoy` 的掉落物优先 / `require-thrown=false` 手持回退 / 正后方被几何过滤 / 自己丢的不算** |
| `ModeCheck` | 32 | IR 三模式 × usefilter × SA 类型的 `lockKind()` / `whitelistOnly()` 整张映射表 |
| `FilterCheck` | 51 | **`filter on` 与空白名单**：拒绝开启、清空自动关模式、部分清空不误关、老存档纠正、`set` 未知 ID 的前缀容错、**完整注册 ID 规范化 + 老存档裸 ID 迁移 + 补全来源** |
| `RwrCheck` | 26 | RWR/MAWS 开机三条件真值表（含 `missile.admin` 等效）、`rwr:` 板块读取与夹取 |
| `ArgsCheck` | 38 | `/msl ir …` 与 **`/msl super_active default\|entity [ID]\|player [名字]\|filter list\|clear`** 的参数解析（含 `filteroff`、前缀容错、组别名、拒绝 `minecraft:player`），用假 `Player` 反射调真实 `applyTypeArgs` |
| `LangCheck` | 9 | semiLOS ActionBar、`/msl status` 新行、`%msl_maws%` 替换，逐字比对 |

- `javap` 核对过的 1.21.11 API：`Material#matchMaterial`、`Particle#valueOf`、`ConfigurationSection#getString/getDouble/getBoolean`、`JavaPlugin#getLogger/saveResource`、`Player#playSound(Location, Sound, float, float)`、`Bukkit#createBossBar`、`BossBar#setTitle/setColor/addPlayer/removePlayer/removeAll`、`RayTraceResult#getHitEntity`、`PlaceholderExpansion` / `PlaceholderHook#onRequest` / `CharsReplacer#apply` 等。
- **已确认移除的 API**：`org.bukkit.util.EntityHitResult`（改用 `RayTraceResult#getHitEntity()`）、`Entity#setCollidable`（本项目未使用）；`Sound` 在 1.21.11 **已由枚举改为 interface**（`Sound.valueOf` 不可用，常量仍在）。
- **尚未在真实服务端运行验证**：加载 / 注册 / 完整发射链路 / BossBar 实际显示 / 告警音 / PAPI 注册与 TAB 替换都未做运行时烟测。

## 16. 目录结构

```
E:\ser_plugins\Missile
├── pom.xml                            # paper-api + placeholderapi(provided)
├── .mvn/local-repo-settings.xml       # 构建用：把 Maven 本地仓库指向工作区内
├── docs/
│   ├── README.md                      # 本文档（服主 / 开发者）
│   ├── 玩家手册.md                     # 面向玩家
│   ├── 服务端联调清单.md                # P2 第 9 项：真实服务端要验什么（可勾选）
│   └── 总需求文档.md                   # 需求 + 实现状态 + 决策记录（会话交接用）
├── dist/                              # 一次性验证程序与发布包（已 gitignore，不进 jar）
│   ├── TypeConfigCheck.java  GameplayCheck.java  PlaceholderCheck.java
│   ├── ModeCheck.java  RwrCheck.java  ArgsCheck.java  LangCheck.java
│   ├── PersistCheck.java  FilterCheck.java  VehicleCheck.java  MawsCheck.java  SaCheck.java  DecoyCheck.java
│   ├── cp.txt                         # 上述程序要用的 classpath（含 placeholderapi jar）
│   ├── ColorCheck.java  ColorCheck2.java  RotationCheck.java   # 早期验证程序（颜色转换 / 模型四元数）
│   ├── storagecheck/                  # 早期验证程序（筛选数据 JSON 往返）
│   └── Missile-1.0.2-src.zip          # 源码发布包（.gitignore + pom.xml + .mvn + docs + scripts + src）
├── scripts/
│   ├── publish-to-github.ps1          # 一键推送（鉴权预检 + 非空远端中止）
│   └── check-line-endings.ps1         # 行尾自查：全仓文本文件必须是纯 LF（-Fix 就地还原）
├── 开发历史/                          # 历史构建产物（*.jar 已 gitignore）
├── src/main/java/com/missile/
│   ├── MissilePlugin.java             # 主类：注册监听器/命令/占位符扩展、加载配置与语言、每 tick 任务
│   ├── MissileCommand.java            # /msl 命令实现（型号 / on|off / filter / status / global / reload）
│   ├── MissileType.java               # 五种型号的出厂默认参数 + radarHoming() + TargetKind（玩家/生物/不限）
│   ├── Missile.java                   # 单发导弹：制导钩子、干扰（丢出的诱饵）、粒子、引爆
│   ├── SemiLOSMissile.java            # 半自动指令瞄准线：驾束制导子类
│   ├── MissileManager.java            # 在飞导弹注册表与发射分流
│   ├── SeekerListener.java            # 右键开导引头/锁定/发射、命中处理、导引头状态
│   ├── TargetSelector.java            # 视线锁定与准星锥角最近者选择 + 白名单通道
│   ├── TargetFilter.java              # 目标筛选：白名单数据 / 命令解析 / canTarget / isWhitelisted
│   ├── FilterStorage.java             # 筛选数据持久化（JSON 默认 / MySQL 可选，异步 + 脏检查）
│   ├── Settings.java                  # config.yml + msl_config.yml 的唯一读取入口（可热重载）
│   ├── Lang.java                      # 多语言文案表（lang/<语言>.yml + messages 覆盖）
│   ├── MissilePlaceholders.java       # PlaceholderAPI 扩展（identifier msl）+ ActionBar 内部替换
│   ├── RwrManager.java                # RWR + MAWS：开机判定、威胁轮询、8 方位、告警音
│   ├── RwrDisplay.java                # 告警显示层：按槽位（RWR / MAWS）各一条 BossBar
│   └── RwrListener.java               # 退出 / 死亡 / 重生清理
├── src/main/resources/
│   ├── plugin.yml                     # 命令、权限、softdepend: [PlaceholderAPI]
│   ├── config.yml                     # 通用项：语言 / 文案覆盖 / 筛选存储
│   ├── msl_config.yml                 # 导弹参数：全服开关 / 导引头 / 飞行 / 驾束 / 粒子 / 模型 / 战斗部 / 型号 / RWR / MAWS
│   └── lang/
│       ├── zh_cn.yml                  # 简中文案（默认）
│       └── en_us.yml                  # 英文文案
└── target/Missile-1.0.2.jar           # 构建产物
```
