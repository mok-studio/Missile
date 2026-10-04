# Missile —— Paper 1.21.11 制导导弹插件

手持 TNT 右键开启导引头并锁定视线内目标，再右键发射。提供五种导弹：**红外**、**半主动雷达寻的**、**主动雷达寻的**、**半自动指令瞄准线（semiLOS，驾束 / 线导）**、**超级主动弹（super_active，默认仅 OP 可用）**，各自有独立的速度、制导律、抗干扰能力与尾焰/尾烟粒子。锁定目标类型（玩家 / 生物）由命令参数决定。

被半主动 / 主动雷达锁定的玩家，只要背包里带着**指南针**就会收到 **RWR 雷达告警**（BossBar 显示威胁方位 + 告警音）。全部玩家可见文案走 `lang/<语言>.yml`，内置 `zh_cn` 与 `en_us`。

- 插件名：`Missile`
- 主类：`com.missile.MissilePlugin`
- `api-version`：`1.21`
- 目标平台：**Paper 1.21.11**（Java 21）
- 实现方式：纯 Paper API，无 NMS、无反射

---

## 1. 环境要求

| 项目 | 要求 |
|---|---|
| 服务端 | Paper 1.21.11（`1.21.11-R0.1-SNAPSHOT` API 编译） |
| Java | 21（Paper 1.21.9+ 强制要求） |
| 构建 | Maven 3.9+ |

## 2. 构建

```bash
cd E:\ser_plugins\Missile
mvn -s .mvn/local-repo-settings.xml -B package
```

产物：`target/Missile-1.0.0.jar`

> **为什么带 `-s .mvn/local-repo-settings.xml`？**
> 本工作区默认的 Maven 本地仓库（`%USERPROFILE%\.m2\repository`）不可写，直接 `mvn package` 会以 `AccessDeniedException ... resolver-status.properties` 失败。仓库内附带的这份 settings 把本地仓库指向工作区内的 `.m2repo/`。若你的环境 `%USERPROFILE%\.m2` 可写，可直接用 `mvn -B package`。

## 3. 安装

1. 把 `target/Missile-1.0.0.jar` 复制到服务端 `plugins/` 目录。
2. 启动或重启服务端。
3. 控制台出现 `Missile 已启用：手持 TNT 右键开启导引头，再右键发射` 即为加载成功。
4. 首次启动会在 `plugins/Missile/config.yml` 生成默认配置。

权限节点 `missile.use`（默认 `true`）同时控制命令与 TNT 导引头。

## 4. 快速上手

| 步骤 | 操作 |
|---|---|
| 1 | 用 `/missile <ir\|semi\|active\|semiLOS> <player\|entity>` 选择型号与锁定目标类型（两个参数都可缺省：型号默认红外，目标类型默认玩家） |
| 2 | 主手手持 **TNT**，右键 → 开启导引头并锁定视线内目标 |
| 3 | 再右键 → 发射；每次发射消耗 1 个 TNT（创造模式不消耗） |

锁定期间动作栏实时显示：

```
§6红外导弹 §7| §f目标:玩家 §7| §a锁定 §fSteve §7| §f再右键发射
```

要点：

- 右键**不会**放置 TNT 方块：插件在 `PlayerInteractEvent` 中用 `setUseInteractedBlock(DENY)` + `setUseItemInHand(DENY)` 拦截。
- 锁定判定：先读准星射线命中的目标（`RayTraceResult#getHitEntity()`），再并入准星 **10° 锥角**内、无遮挡、128 格内的候选目标，取**距离最近者**。
- **锁定目标类型**由命令参数 2 决定：`player`（只锁玩家）或 `entity`（只锁生物，即非玩家 `LivingEntity`，排除盔甲架等导弹外形实体）。
- 导引头开启期间每 2 tick 刷新一次锁定；换掉主手 TNT 会自动关闭。
- **半主动雷达**在无锁定目标时**拒绝发射**并提示，避免浪费弹药；**semiLOS 不需要锁定目标**，只要有视线方向即可发射。
- 型号只能通过命令选择；右键不做型号循环，也不判定副手物品。
- **个人开关**：`/missile on|off` 只对**你自己**生效（默认开启，仅存内存）。关闭后你手持 TNT 右键**完全走原版逻辑**（可正常放置 TNT），已开启的导引头会被清空；其他玩家不受影响。
- **目标筛选**：`/missile filter ...` 可限定导引头只锁某些目标（如 `/missile filter entity zombie !creeper`、`/missile filter !player`），详见第 11 节。

## 5. 五种导弹

| 参数 | 红外 INFRARED | 半主动 SEMI_ACTIVE | 主动 ACTIVE | 半自动指令瞄准线 SEMI_LOS | 超级主动弹 SUPER_ACTIVE |
|---|---|---|---|---|---|
| 命令参数 | `ir` | `semi` | `active` | `semiLOS` | `super_active`（需 `missile.superactive`） |
| 初速 → 最大速度 | 5 → 25 m/s | 5.2 → 30 m/s | 6 → 40 m/s | 5.2 → 30 m/s | **8 → 850 m/s** |
| 加速度 | 1.0 m/s 每 tick | 1.2 m/s 每 tick | 1.5 m/s 每 tick | 1.2 m/s 每 tick | **4.0 m/s 每 tick** |
| 转弯率 | 6°/tick | 4°/tick | 7°/tick | 12°/tick | **30°/tick** |
| 制导方式 | 自主，纯追踪 | 发射者准星持续照射 | 自主，比例导引（带提前量） | 驾束 / 线导：跟随发射者视线方向 | **同主动雷达**（比例导引 + 惯性记忆） |
| 发射后需持续瞄准 | 否 | **是** | 否 | **是**（持续给出视线） | 否 |
| 自主（重）截获 | 55 格 / 30° 锥 | 128 格 / 10° 锥（由发射者指定） | 70 格 / 40° 锥 | 无（不锁定目标） | **100 格 / 45° 锥** |
| 干扰物 | 烈焰棒 | 铁粒 | 铁粒 | 无 | **无（免疫干扰）** |
| 脱锁概率 | 15% | 5% | 2.5% | 无 | **0（免疫）** |
| 脱锁后果 | 改锁并追踪干扰者 | 照射链路永久中断 | 脱锁 3 秒后自主重新截获 | 无 | 不会脱锁 |
| 触发敌方 RWR | 否 | 是 | 是 | 否 | **是** |
| 战斗部威力 | 3.0 | 3.5 | 4.5 | 3.5 | **10.0** |
| 尾焰粒子 | `FLAME` | `SMALL_FLAME` | `COPPER_FIRE_FLAME` | `SOUL_FIRE_FLAME` | `COPPER_FIRE_FLAME` |
| 尾烟粒子 | `LARGE_SMOKE` | `CAMPFIRE_COSY_SMOKE` | `WHITE_SMOKE` | `SMOKE` | `WHITE_SMOKE` |

速度单位换算：`1 m/s = 1 格/秒 = 0.05 格/tick`，插件按住 tick 设置弹体速度，因此游戏内速度严格等于上表数值。

### 制导细节

- **红外**：发射后完全自主，纯追踪目标当前位置（无提前量）；目标死亡或丢失时在前方 55 格 / 30° 锥内重新截获最近目标。
- **半主动**：每 tick 用发射者的准星重新指定目标（128 格 / 10° 锥内最近者），准星附近多个目标时自动切换；发射者移开视线即失去制导，重新瞄准可恢复照射。
- **主动**：比例导引——按目标**实测位移**解算提前量（最大预测 60 tick），并保留 5 秒惯性记忆指向最后已知位置；脱锁后可自主重新截获。
- **半自动指令瞄准线（semiLOS）**：发射后不锁定任何目标，每 tick 读取发射者当前视线，`rayTraceBlocks` 取视线前方 200 格处的瞄准点（被方块挡住时取命中点、下限 8 格），导弹以 12°/tick 的转弯率向该点收敛——即“持续向玩家准星 / 视线方向飞行”。不切换目标、不判定干扰。发射者离线或换维度后失去束轴更新，导弹沿最后方向继续飞行直至命中或 30 秒自毁。
- **超级主动弹（super_active）**：与主动雷达**完全同一套制导**——代码里由 `MissileType#radarHoming()` 统一判定，`Missile` 的提前量与惯性记忆、`RwrManager` 的威胁统计都以它为准。具体为：按目标实测位移打提前量（上限 60 tick）、脱锁后保留 5 秒惯性记忆飞向最后已知点、脱锁 3 秒后按 **100 格 / 45° 锥**自主重截获、转弯率 **30°/tick**、战斗部威力 **10.0**。
  - **免疫一切干扰**：`decoyChance = 0`，`Missile#checkDecoy()` 在概率 ≤ 0 时直接返回，烈焰棒 / 铁粒都无效，不存在“脱锁”。
  - **会触发敌方 RWR**（锁定阶段“敌跟踪”、发射后“敌导弹”）。
  - 注意：**开导引头那一下的初始锁定**仍走全型号共用的 **128 格 / 10° 锥**（`SeekerListener.LOCK_RANGE/LOCK_CONE`）；100 格 / 45° 只用于**弹上自主（重）截获**。
  - 物理注意：**850 m/s = 42.5 格/tick**，一 tick 跨越 42.5 格，3 格近炸引信可能被“跳过”，实际引爆主要靠弹体扫掠命中；出膛后约 10.5 秒加速到满速，30 秒寿命内理论射程极大（受区块加载限制）。

### 干扰（对抗）机制

- 判定对象：**主手或副手**持有对应干扰物的玩家，位于导弹 **48 格**内、且在导引头**前方**（方向点积 > 0）；排除发射者本人与旁观模式玩家。
- 判定频率：**每 20 tick（1 秒）一次**，每次独立按上表概率判定。按 tick 判定会让 15% 被放大成近乎必中，因此不采用。
- 红外被干扰后**改锁干扰者**（“转向追踪该烈焰棒”即锁定其持有者，红外导引头视其为热源），不会回头。
- 主动脱锁后的 3 秒内飞向惯性记忆点，之后按 70 格 / 40° 锥自主重截获。
- **半自动指令瞄准线不参与干扰判定**（`decoyChance = 0`，且 `SemiLOSMissile` 重写 `checkDecoy` 为空实现）。
- **超级主动弹免疫一切干扰**（同样是 `decoyChance = 0`，走 `Missile#checkDecoy` 的概率守卫直接返回），不会被烈焰棒 / 铁粒影响。
- 每次触发都会通过动作栏通知发射者（如 `§c红外导引头脱锁，转向烈焰棒热源: Steve`）。

## 6. 命令与权限

| 命令 | 说明 |
|---|---|
| `/missile` | 等价于 `/missile ir player`：红外导弹 + 锁定玩家 |
| `/missile ir` | 红外导弹，锁定目标类型缺省为 `player` |
| `/missile active entity` | 主动雷达寻的，锁定**生物**（非玩家 `LivingEntity`） |
| `/missile semiLOS` | 半自动指令瞄准线，跟随准星方向 |
| `/missile super_active` | 超级主动弹（**需 `missile.superactive`**，默认仅 OP；无权限时提示且不切换型号） |
| `/missile on` | **为你自己开启**导弹系统（默认状态，仅存内存，不写配置） |
| `/missile off` | **为你自己关闭**导弹系统：你的 TNT 恢复原版放置行为（其他玩家不受影响） |
| `/missile filter ...` | 目标筛选（见第 11 节）：`all` / `clear` / `list` / `entity` / `player` / `!player` / `zombie` / `!zombie` / `entity zombie !creeper` |
| `/missile status` | 查看当前型号、锁定目标类型、速度、干扰物与脱锁概率、导引头开关、个人开关、当前锁定 |

语法：`/missile <ir|semi|active|semiLOS|super_active> <player|entity>`（参数 1 缺省 `infrared`，参数 2 缺省 `player`）、`/missile <on|off>`、`/missile filter <条件...>`。**`on` / `off` 与 `filter` 会被优先识别**，与型号参数互斥（写这些关键字时不会当作型号解析）。

别名：`/msl`。型号参数还接受 `infrared` / `sarh` / `semi-active` / `arh` / `semilos` / `los` / `line` / `super_active` / `superactive` / `super-active` / `super` / `红外` / `半主动` / `主动` / `指令` / `驾束` / `线导` / `超级主动` / `1` `2` `3` `4` `5`；目标类型参数还接受 `p` / `mob` / `mobs` / `玩家` / `生物` / `实体`。

| 权限 | 默认 | 说明 |
|---|---|---|
| `missile.use` | `true` | 使用导引头、发射导弹、使用 `/missile` 命令 |
| `missile.superactive` | `op` | 选择 **超级主动弹**（`/missile super_active`）；Tab 补全也只对有权限的玩家提示该型号 |

## 7. 配置

`plugins/Missile/config.yml`：

```yaml
# 语言文件：读取 lang/<language>.yml（内置 zh_cn 与 en_us）
language: zh_cn

explosion:
  # true  = 战斗部爆炸破坏地形并伤害实体（默认）
  # false = 只伤害实体、不破坏方块（保护型服务器建议改为 false）
  break-blocks: true
```

配置改动后重启服务端生效。**导弹开关与目标筛选都是玩家个人状态**（`/missile on|off`、`/missile filter ...`），只存内存、不写配置文件，服务器重启后所有玩家恢复默认（开关开启、自由锁定）。语言文件的结构与自定义方式见第 13 节。

## 8. 行为与限制

- **实体方案**：`SmallFireball` 作为弹体（承担运动、碰撞与 `ProjectileHitEvent`，`yield = 0`、`incendiary = false`），marker `ArmorStand`（手持 TNT）每 tick 同步到弹体位置作为外形；命中事件被拦截，改由插件统一引爆。
- **粒子**：尾焰在弹尾 0.55 格、尾烟在弹尾 1.65 格，**每 tick** 生成；仅当 96 格内有玩家时才生成以节省开销。
- **近炸引信**：距锁定目标 3 格内自动引爆；**semiLOS 无目标，故不使用近炸**，只在弹体命中时引爆。
- **寿命**：单发最长 30 秒，超时自毁。
- **同屏上限**：64 发，超出时丢弃最旧的一发。
- **关服 / reload**：在飞导弹被静默移除，不产生爆炸。

## 9. 调参索引

| 想改什么 | 位置 |
|---|---|
| 初速、最大速度、加速度、转弯率、干扰物与概率、战斗部威力、截获距离/锥角、粒子类型、型号与目标类型别名 | `src/main/java/com/missile/MissileType.java` 枚举常量与 `parse` |
| 型号是否按雷达制导走（提前量 / 惯性记忆 / 触发 RWR 的归类） | `src/main/java/com/missile/MissileType.java` 的 `radarHoming()`；使用点在 `Missile.java`（3 处）与 `RwrManager.java`（2 处） |
| 寿命、干扰判定周期、主动重截获延迟、惯性记忆时长、干扰检测距离、近炸半径、粒子可见距离 | `src/main/java/com/missile/Missile.java` 顶部常量 |
| 制导钩子（选目标 / 转向与速度 / 引爆条件 / 干扰判定） | `src/main/java/com/missile/Missile.java` 的 `selectTarget` / `steer` / `shouldDetonate` / `checkDecoy` |
| 驾束束长与下限 | `src/main/java/com/missile/SemiLOSMissile.java` 的 `BEAM_LENGTH` / `MIN_BEAM_LENGTH` |
| 同屏导弹上限 | `src/main/java/com/missile/MissileManager.java` 的 `MAX_ACTIVE` |
| 锁定距离、准星锥角、出膛前移量 | `src/main/java/com/missile/SeekerListener.java` 的 `LOCK_RANGE` / `LOCK_CONE` / `MUZZLE_OFFSET` |
| RWR 指南针检查周期、告警音周期 / 音高 / 音量、告警距离上限、扇区角度 | `src/main/java/com/missile/RwrManager.java` 顶部常量 |
| RWR 告警条颜色与样式 | `src/main/java/com/missile/RwrDisplay.java` 的 `BarColor` / `BarStyle` |
| 所有显示文案（含 RWR 方位箭头 `rwr.dir-0` … `rwr.dir-7`） | `src/main/resources/lang/zh_cn.yml`、`lang/en_us.yml`；语言选择在 `config.yml` 的 `language` |
| 玩家个人开关的存储与判断 | `src/main/java/com/missile/MissilePlugin.java` 的 `playerMissileEnabled` / `isPlayerEnabled` / `setPlayerEnabled` |
| 目标筛选的命中规则、实体 ID 校验、`all` / `clear` 语义 | `src/main/java/com/missile/TargetFilter.java` 的 `canTarget` / `parse` / `describe` |

## 10. 故障排查

| 现象 | 原因与处理 |
|---|---|
| 构建报 `AccessDeniedException ...\.m2\...` | 本地仓库不可写，改用 `mvn -s .mvn/local-repo-settings.xml -B package` |
| 插件未加载 / `UnsupportedClassVersionError` | 必须 Java 21 + Paper 1.21.11 |
| 右键放出了 TNT 方块 | 确认是**主手**持有 TNT；若其他插件在更高优先级抢先处理了右键，需调整优先级 |
| 导弹直飞不追踪 | `/missile status` 确认型号；半主动必须**持续看向目标**；红外/主动需目标在前方锥角内 |
| 锁不到生物 | 参数 2 要为 `entity`（如 `/missile active entity`）；`entity` 只锁非玩家 `LivingEntity`，盔甲架等导弹外形实体被排除 |
| 导弹中途脱锁 | 有玩家手持烈焰棒或铁粒在附近，属预期抗干扰机制；可改用抗干扰更强的型号（主动 2.5% 最低）或 semiLOS（无干扰判定） |
| semiLOS 不跟随视线 | 需要发射者在线、存活且与导弹同维度；失去束轴更新后导弹沿最后方向继续飞 |
| 地形被炸 | 把 `config.yml` 的 `explosion.break-blocks` 改为 `false` |

## 11. 目标筛选系统

每个玩家可以配置自己的**目标筛选**，决定导引头能锁定哪些目标（仅存内存，重启清空）。它有两种模式：

| 模式 | 条件 | 锁定行为 |
|---|---|---|
| **自由锁定**（默认） | `useAll = true` | 完全走原有锁定逻辑，筛选列表被**忽略但保留** |
| **筛选模式** | `useAll = false` | 只锁定筛选列表内的目标（排除项优先） |

命令：`/missile filter <条件...>`（别名 `/msl filter ...`）

| 命令 | 说明 |
|---|---|
| `/missile filter all` | 切回**自由锁定**（`useAll = true`），**保留**已配置的筛选列表 |
| `/missile filter clear` | **清空**筛选列表并恢复自由锁定 |
| `/missile filter list` | 查看当前模式与 include / exclude 明细 |
| `/missile filter entity` | 仅锁定生物（非玩家 `LivingEntity`） |
| `/missile filter player` | 仅锁定玩家 |
| `/missile filter !player` | 排除玩家（其余目标仍可锁） |
| `/missile filter zombie` | 仅锁定僵尸 |
| `/missile filter !zombie` | 排除僵尸 |
| `/missile filter entity zombie !creeper` | 锁定生物 + 包含僵尸 + 排除苦力怕 |

规则：

- **包含项之间是「或」**：命中任一包含类型或包含 ID 即可锁定；**排除项优先于包含项**。
- **只写排除项时不限制其余目标**（如 `filter !player` 后，除玩家外全部可锁）。
- 实体 ID 支持 `zombie` 与 `minecraft:zombie` 两种写法；**非法 ID 会报错**且不改动原配置（用 `EntityType` 注册表校验）。
- `entity` 与 `player` **互斥**：同时出现在同一侧（包含或排除）会报错并保持原配置不变，需先 `/missile filter clear`。
- 筛选条件**累加**（不是覆盖）；`all` 只切换模式、`clear` 才清空列表。
- `all` / `clear` 是**独占关键字**，不能与其它条件写在同一条命令里。
- 已发射导弹的**弹上重新截获**同样遵守发射者的筛选（`TargetSelector` 的 `select` 与 `acquireInCone` 两处入口都做了判定）。
- 与 `/missile <型号> <player|entity>` 的目标类型参数是**两个独立条件，取交集**：例如型号选 `entity`（只锁生物）而筛选写 `player`，则什么也锁不到。

## 12. RWR 雷达告警系统

**开机条件**：玩家背包内（任意槽位）有**指南针 `COMPASS`**，RWR 即自动开机；取出指南针立即静默。

| 告警级别 | 触发条件 | 显示 | 声音 |
|---|---|---|---|
| **敌跟踪** | 玩家 A 已开启导引头、型号为**半主动 / 主动**、当前锁定目标是你（尚未发射） | 黄色 BossBar：`⚠ 敌跟踪 →`（文字 + 方位箭头） | 高音 / 低音**交替**，每 20 tick（1 秒）一次 |
| **敌导弹** | 某发**在飞的半主动 / 主动导弹**，其锁定目标是你 | 红色 BossBar：`⚠ 敌导弹 ↘ ×2`（文字 + 方位箭头 + 数量） | **急促**高音，每 5 tick（0.25 秒）一次 |

- 方位以**你的朝向为正前方（↑）**，按 45° 扇区映射到 8 个方位并显示为箭头：`↑` 正前 / `↗` 右前 / `→` 正右 / `↘` 右后 / `↓` 正后 / `↙` 左后 / `←` 正左 / `↖` 左前（依次对应 12点钟 / 1:30 / 3点钟 / 4:30 / 6点钟 / 7:30 / 9点钟 / 10:30）。箭头文案在语言文件 `rwr.dir-0` … `rwr.dir-7`，想换回文字写法只需替换这 8 个值。
- 同时存在多个威胁时显示**距离最近者**的方位，并在文案后附 `×数量`。
- 敌导弹优先级高于敌跟踪；威胁消失后 BossBar 自动隐藏。
- **会触发** RWR 的型号：半主动（单独判定）、主动与**超级主动弹**（判定为 `MissileType#radarHoming()`）；**红外与 semiLOS 不触发**（前者被动红外无辐射，后者不属雷达制导），符合“雷达告警”的语义。
- 显示用 BossBar 而非动作栏，避免与导引头的动作栏状态提示互相覆盖；玩家退出 / 死亡 / 重生会立即清理告警条。
- 实现上 RWR **轮询**现有状态（`SeekerListener` 的导引头状态 + `MissileManager` 的在飞导弹列表），不侵入导弹制导逻辑，因此方位取的是导弹**实时位置**而非发射点，也不会漏事件。
- 告警距离上限 256 格。

## 13. 多语言与语言文件

- 语言文件位置：`plugins/Missile/lang/<language>.yml`，由 `config.yml` 的 `language` 指定（默认 `zh_cn`）。
- 插件内置 `lang/zh_cn.yml` 与 `lang/en_us.yml`；首次启动把所选语言释放到数据目录，可直接改文案，也可复制一份做新语言（如 `lang/ja_jp.yml` + `language: ja_jp`）。
- 占位符写 `{name}`（如 `&7| {lock} &7|`），颜色代码用 `&`（如 `&6`、`&c`），加载时自动转成 `§`。
- 缺 key 时返回 key 本身并在控制台告警一次，便于发现漏翻。
- 保留硬编码的两类例外：Lang 自身的诊断日志（缺 key 告警、加载失败——走 Lang 会形成循环依赖）与命令**输入**别名（`红外` / `半主动` / `指令` 等，属命令关键字而非显示文案）。

## 14. 当前验证状态

- 已通过：Maven 构建（`BUILD SUCCESS`），并用 `javap` 对 Paper 1.21.11 API 逐个核对了所用签名（`RegionAccessor#spawn`、`World#rayTraceEntities/rayTraceBlocks/createExplosion/spawnParticle`、`Server#getEntity(UUID)`、`LivingEntity#getTargetEntity/getEyeLocation/hasLineOfSight`、`Fireball#setDirection/setYield/setIsIncendiary`、`ArmorStand`、`Player#sendActionBar` 等）。
- 已验证移除的 API：`org.bukkit.util.EntityHitResult` 在 1.21.11 **已不存在**，视线判定改用 `RayTraceResult#getHitEntity()`（语义等价）；`Entity#setCollidable` 亦已移除，本项目未使用。
- semiLOS 与「锁定目标类型 player / entity」为增量改造，分 3 轮（每轮 ≤3 文件）实现，每轮均单独 `mvn package` 通过，0 次编译失败。
- RWR 与多语言为增量改造，分 4 步（1a 框架 / 1b 迁移 / 1c 英文包 / 2 RWR，每步 ≤4 文件）实现，每步单独 `mvn package` 通过，0 次编译失败。
- `/missile on|off` 开关为增量改造（先实现全局、后按需求改为玩家个人），命令与文案分步实现，均 `mvn package` 通过。
- **开关访问器命名有被迫偏离**：`JavaPlugin` 已把 `isEnabled()` 与 `setEnabled(boolean)` 声明为 **final 实例方法**（插件自身启用标志），同名声明会直接编译失败，因此实现为 `MissilePlugin.isPlayerEnabled(UUID)` / `setPlayerEnabled(UUID, boolean)`；右键侧的 `PlayerInteractListener` 在本工程并不存在，等价位置是 `SeekerListener#onInteract`。
- 目标筛选系统分 2 轮（第 1 轮：`TargetFilter` 框架 + 主类初始化；第 2 轮：命令接入 + `TargetSelector` 判定 + 中英文案，每轮 ≤4 文件），第 2 轮出现 1 次编译失败（`FilterData` 字段为 private，外部类不可访问）并已修复。
- 超级主动弹（`super_active`）为增量新增：枚举 + 命令解析 + `missile.superactive` 权限 + 中英文案；随后按需求把「RWR 触发」与「制导对齐主动雷达」收口到 `MissileType#radarHoming()`（`Missile` 3 处、`RwrManager` 2 处判断），转弯率改为 30°/tick、自主截获改为 100 格 / 45°。均 `mvn package` 通过。
- `TargetFilter` 静态初始化崩溃（`EntityType.UNKNOWN.getKey()` 抛 `IllegalArgumentException`）已修：`collectValidIds()` 逐条 `try/catch` 跳过并告警，`idOf()` 同样包住，静态初始化不再可能失败。
- 新增 API 亦已 `javap` 核对：`Bukkit#createBossBar(String, BarColor, BarStyle, BarFlag...)`、`BossBar#setTitle/setColor/setProgress/addPlayer/removePlayer/removeAll`、`Sound`（1.21.11 已由枚举改为 interface + 静态常量）`BLOCK_NOTE_BLOCK_PLING` / `BLOCK_NOTE_BLOCK_BIT`、`Player#playSound(Location, Sound, float, float)`、`Inventory#contains(Material)`、`YamlConfiguration#loadConfiguration(File)`、`JavaPlugin#getResource/saveResource`。
- **尚未在真实服务端运行验证**（加载、注册、完整发射链路未做运行时烟测）。

## 15. 目录结构

```
E:\ser_plugins\Missile
├── pom.xml
├── .mvn/local-repo-settings.xml      # 构建用：把 Maven 本地仓库指向工作区内
├── docs/README.md                    # 本文档
├── src/main/java/com/missile/
│   ├── MissilePlugin.java            # 主类：注册监听器/命令、加载语言、每 tick 任务
│   ├── MissileCommand.java           # /missile <型号> <player|entity> 命令实现
│   ├── MissileType.java              # 五种型号的性能/制导参数 + radarHoming() + TargetKind（玩家/生物）
│   ├── Missile.java                  # 单发导弹：制导钩子、干扰、粒子、引爆
│   ├── SemiLOSMissile.java           # 半自动指令瞄准线：驾束制导子类
│   ├── MissileManager.java           # 在飞导弹注册表与发射分流
│   ├── SeekerListener.java           # 右键开导引头/锁定/发射、命中处理
│   ├── TargetSelector.java           # 视线锁定与准星锥角最近者选择（玩家/生物）+ 筛选判定
│   ├── TargetFilter.java             # 目标筛选：模式 / 包含 / 排除 与 canTarget 判定
│   ├── Lang.java                     # 多语言文案表（lang/<语言>.yml）
│   ├── RwrManager.java               # RWR：开机判定、威胁轮询、8 方位、告警音
│   ├── RwrDisplay.java               # RWR：BossBar 显示层
│   └── RwrListener.java              # RWR：退出/死亡/重生清理
├── src/main/resources/
│   ├── plugin.yml
│   ├── config.yml
│   └── lang/
│       ├── zh_cn.yml                 # 简中文案（默认）
│       └── en_us.yml                 # 英文示例
└── target/Missile-1.0.0.jar          # 构建产物
```
