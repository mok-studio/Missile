# Missile — Paper 1.21.11 制导导弹插件

**此插件由DeepSeek Harness制作，感谢AI Agent提供的便利！**

手持 TNT 右键开启导引头并锁定视线内目标，再右键发射。5 种导弹可选；受害者侧有 **RWR**（雷达告警）与 **MAWS**（导弹逼近告警）两套 BossBar 提示。

| 项目 | 值 |
|---|---|
| 插件名 / 主类 | `Missile` / `com.missile.MissilePlugin` |
| 版本 / 平台 | `1.0.3` / Paper 1.21.11（Java 21，`api-version: 1.21`） |
| 命令 | `/missile`，别名 `/msl` |
| 权限 | `missile.use`（默认 `true`）、`missile.admin`（默认 `op`） |
| 可选依赖 | PlaceholderAPI（软依赖，装了自动注册 `msl` 扩展） |
| 实现 | 纯 Paper API，不使用 NMS / 反射 |
| 配置文件 | `config.yml`（通用项）+ `msl_config.yml`（全部导弹参数） |
| 文案 | `lang/zh_cn.yml` / `lang/en_us.yml`（key 121 : 121），可用 `config.yml` 的 `messages:` 按 key 覆盖 |

## 1. 构建与安装

| 步骤 | 操作 |
|---|---|
| 构建 | `mvn -s .mvn/local-repo-settings.xml -B clean package`（本地仓库不可写时必须带 `-s`） |
| 产物 | `target/Missile-1.0.3.jar` |
| 安装 | 把 jar 放进服务端 `plugins/` 后重启 |
| 首次启动 | `plugins/Missile/` 生成 `config.yml`、`msl_config.yml`，语言文件释放到 `lang/` |
| 校验 | 控制台出现 `Missile 已启用：…`；装了 PAPI 另有 `已注册 PlaceholderAPI 扩展 msl` |

## 2. 命令与权限

| 命令 | 权限 | 说明 |
|---|---|---|
| `/msl`、`/msl status` | `use` | 型号与参数、锁定目标、导引头、个人/全服开关、IR 模式、SA 模式 + safilter、筛选模式 |
| `/msl ir [default\|player\|entity] [usefilter\|filteroff]` | `use` | 红外型号 + 工作模式 + 白名单通道（两者独立，缺省不改动） |
| `/msl semi`、`/msl active`、`/msl semiLOS` | `use` | 切换型号，无附加参数 |
| `/msl super_active default` | `admin` | 任意目标，并清空 safilter |
| `/msl super_active entity\|player [set\|add\|remove\|clear] [值...]` | `admin` | 按类编辑 safilter；不写动词等同 `add` |
| `/msl super_active filter <list\|clear>` | `admin` | 查看 / 清空 safilter |
| `/msl filter <entity\|player\|clear\|on\|off\|list> [set\|add\|remove\|clear] [值...]` | `use` | 目标筛选白名单 |
| `/msl on` / `/msl off` | `use` | 个人导弹开关（会话级，默认开） |
| `/msl default` | `use` | 把自己的设置恢复默认；**保留所选型号与 `on\|off` 开关** |
| `/msl global <on\|off>` | `admin` | 全服开关，写回 `msl_config.yml` |
| `/msl reload` | `admin` | 重读两份配置与语言文件，并落盘筛选数据 |

型号别名：`ir`/`infrared`/`红外`/`1`、`semi`/`sarh`/`半主动`/`2`、`active`/`arh`/`主动`/`3`、`semiLOS`/`semi-los`/`los`/`驾束`/`线导`/`4`、`super_active`/`super`/`超级主动`/`5`。

参数解析：遇第一个非法参数即停止，只执行它之前的部分。`remove` 例外——"合法但不在名单里"的条目只提示、不中断。Tab 按位置给全集并按权限过滤，`remove` 之后列出名单里已有的条目。

## 3. 五种导弹

下表是 `msl_config.yml` 中 `types:` 的出厂默认值，全部可改，改完 `/msl reload` 生效。

| 参数 | `ir` 红外 | `semi` 半主动 | `active` 主动 | `semiLOS` 驾束 | `super_active` 超主动 |
|---|---|---|---|---|---|
| 配置键 | `types.infrared` | `types.semi-active` | `types.active` | `types.semi-los` | `types.super-active` |
| 权限 | `missile.use` | `missile.use` | `missile.use` | `missile.use` | **`missile.admin`** |
| 初速 → 最大速度 (m/s) | 5 → 25 | 5.2 → 30 | 6 → 40 | 5.2 → 30 | **8 → 850** |
| 加速度 (m/s·tick) | 1.0 | 1.2 | 1.5 | 1.2 | **4.0** |
| 转弯率 (°/tick) | 6 | 4 | 7 | 12 | **30** |
| 制导方式 | 自主纯追踪 | 发射者准星持续照射 | 自主比例导引（打提前量） | 驾束：跟随发射者视线 | 同主动 |
| 发射后需持续瞄准 | 否 | **是** | 否 | **是** | 否 |
| 自主（重）截获 | 55 格 / 30° | 128 格 / 10° | 70 格 / 40° | 无（不锁目标） | **100 格 / 45°** |
| 干扰物（**丢出**才生效） | 烈焰粉 | 铁粒 | 铁粒 | 无 | 无（免疫） |
| 脱锁概率 / 判定周期 | 15% / 1 秒 | 5% / 1 秒 | 2.5% / 1 秒 | 无 | 0（免疫） |
| 脱锁后果 | 改锁那件掉落的烈焰粉，5 秒内不回锁投掷者 | 照射链路永久中断 | 记 3 秒惯性后自主重截获 | 无 | 不会脱锁 |
| 触发敌方 RWR | 否 | 是 | 是 | 否 | 是 |
| 战斗部威力 | 3.0 | 3.5 | 4.5 | 3.5 | **10.0** |
| 尾焰 / 尾烟粒子 | `FLAME` / `LARGE_SMOKE` | `SMALL_FLAME` / `CAMPFIRE_COSY_SMOKE` | `COPPER_FIRE_FLAME` / `WHITE_SMOKE` | `SOUL_FIRE_FLAME` / `SMOKE` | `COPPER_FIRE_FLAME` / `WHITE_SMOKE` |

- 速度换算 `1 m/s = 1 格/秒`；弹体是 `SmallFireball`（`yield = 0`），外加尾焰尾烟粒子，不挂外形实体。
- super_active 的 850 m/s ≈ 42.5 格/tick，可能跳过 3 格近炸引信，主要靠弹体扫掠命中。

## 4. 玩法要点

- **锁定**：准星 10° 锥角、128 格内、视线不被挡住、非自己，多个候选取最近者；每 2 tick 刷新。
- **发射**：主手必须持 TNT，再右键发射并消耗 1 个（创造模式不消耗）；换掉主手 TNT 立即关闭导引头。
- **半主动**在无锁定目标时拒绝发射；**semiLOS** 不锁目标，跟随准星前方 200 格瞄准点。
- **锁定时的动作栏**两端各有一段 `&f&k` 乱码（`launcher.lock-padding`，默认 `&f&k1`），与正文之间各留一个空格；未锁定不显示。
- **目标**字段按实际生效类别显示：`玩家` / `实体` / `混合`（两类都允许）/ `任意`（名单里没有具体目标）。超级主动弹看 safilter 名单内容，两类条目都有即 `混合`。
- **干扰物必须丢出**（默认 `decoy.require-thrown: true`）且 3 秒内有效；拿在手上无效，发射者自己丢的无效。
- **近炸引信** 3 格（semiLOS 无）；单发最长 30 秒；同屏上限 64 发。
- `/msl default` 重置 IR 模式、usefilter、SA 模式与 safilter、导引头、筛选白名单；型号与 `on|off` 不受影响。

## 5. 目标筛选

每个玩家一份白名单，决定导引头能锁谁（会持久化）。`off` = 自由锁定；`on` 而白名单为空 = 什么都锁不上（插件会拒绝开启或自动关回 `off`）。

| 命令 | 效果 |
|---|---|
| `/msl filter entity [set\|add\|remove\|clear] [ID...]` | 实体类：`zombie` 与 `minecraft:zombie` 均可；`boat` / `minecrafts` 为组别名 |
| `/msl filter player [set\|add\|remove\|clear] [名字...]` | 玩家类：`set`/`add` 只接受在线玩家；`remove` 离线也能删 |
| `/msl filter on` / `off` / `clear` / `list` | 启用 / 停用 / 清空全部 / 查看明细 |

- `set` = 先清空全部筛选数据再写入；`add` 与直接跟 ID = 追加；`remove` 只摘该类条目（不改类别开关、不改筛选模式）。
- 写入类型或 ID 会自动把模式置为 `on`；清空时会自动关回 `off`。
- **载具、末地水晶、盔甲架等非生物默认锁不上**，必须在实体类名单里显式写出其 ID。
- 筛选模式打开（或 `usefilter`）时，"能锁哪一类"以白名单启用的类别为准；两类都启用按玩家优先两段式锁定。
- 实体 ID 一律存服务端注册表的完整键（`minecraft:oak_boat`），老存档的裸 ID 在读取时自动迁移。

### 5.1 super_active 的 safilter

与全局 filter 完全独立的名单，**会话级、不持久化**。

| 命令 | 效果 |
|---|---|
| `/msl super_active default` | 任意目标，并清空 safilter |
| `/msl super_active entity` / `player` | 只锁生物 / 只锁玩家（除自己），并清空 safilter |
| `/msl super_active entity\|player set\|add\|remove\|clear [值...]` | 覆盖 / 追加 / 移除 / 清空该类条目 |
| `/msl super_active filter list` / `clear` | 查看 / 清空 safilter 并退回 `default` |

`safilter` 为空 = 该类不限制；非生物同样要显式列出；`minecraft:player` 不能当实体 ID。

## 6. RWR 与 MAWS

开机条件三条全满足：有 `missile.use` 或 `missile.admin` + 自己 `/msl on` + 背包含**指南针**。自己 `off` 或取出指南针立即静默。

| 告警 | 触发条件 | 显示 | 声音 |
|---|---|---|---|
| RWR 敌跟踪 | 有人开着半主动 / 雷达制导导引头并锁定你（未发射） | 黄色 BossBar `⚠ 敌跟踪 →` | 高低音交替，0.2 秒 |
| RWR 敌导弹 | 在飞的半主动 / 雷达制导导弹锁定你 | 红色 BossBar `⚠ 敌导弹 ↘ ×2` | 急促高音，0.1 秒 |
| MAWS | 40 格内有**接近率 > 10 m/s** 的箭 / 光灵箭 / 三叉戟 / 任何导弹（自己射出的不算） | 红色 BossBar `⚠ MAWS: ↑` | 默认关（`maws.sound.enabled`） |

- 方位以你的朝向为正前方，8 档箭头：`↑ ↗ → ↘ ↓ ↙ ← ↖`（`rwr.dir-0` … `rwr.dir-7`）。
- RWR 与 MAWS 是两条独立 BossBar，可同时显示；多威胁取最近者并附 `×数量`。
- 红外与 semiLOS 不触发 RWR，但会触发 MAWS。

## 7. 配置项

| 文件 | 板块 | 内容 |
|---|---|---|
| `config.yml` | `language` | 语言文件（`zh_cn` / `en_us`） |
| | `messages` | 按 key 覆盖 `lang/<语言>.yml` 的任意文案 |
| | `filter.storage` | `type`（`JSON` / `MYSQL`）、保存周期、JSON 路径、MySQL 七项 |
| `msl_config.yml` | `missile` | `global-enabled`、`persist-global-switch` |
| | `launcher` | `lock-range`、`lock-cone`、`refresh-interval-ticks`、`lock-padding`、`muzzle-offset`、`max-active` |
| | `flight` | `max-life-ticks`、`proximity-fuse`、`inertial-memory-ticks`、`reacquire-delay-ticks` |
| | `decoy` | `interval-ticks`、`search-range`、`require-thrown`、`ttl-ticks`、`lockout-ticks` |
| | `beam` | `length`、`min-length` |
| | `particles` | 可见距离 + 尾焰 / 内焰 / 尾烟 / 云各 3 项 + 偏移 |
| | `explosion` | `break-blocks` |
| | `types` | 5 型号 × 全部性能参数 + `color` |
| | `rwr` / `maws` | 开关、距离、声音间隔 / 音量 / 音调；MAWS 另有 `closing-speed`、`include-own` |

- 删掉某一行 = 该项回落到内置默认值，不报错；数值有防呆夹取，材质 / 粒子名写错会告警一次并回落默认值。
- `/msl global on|off` 会原地改写 `msl_config.yml` 里 `missile.global-enabled` 那一行（保留注释），可用 `missile.persist-global-switch: false` 关闭回写。
- 筛选数据默认存 `plugins/Missile/data/filters.json`；MySQL 缺驱动或连接失败会告警并回退 JSON。
- 玩家个人的 `/msl on|off`、`/msl ir` 模式、safilter 均为会话级，不落盘。

## 8. PlaceholderAPI 占位符

装了 PAPI 自动注册（identifier `msl`，`persist(true)`）。

| 占位符 | 含义 |
|---|---|
| `%msl%` | 当前型号显示名（自带型号色） |
| `%msl_type%` | 当前型号配置 id（`infrared` / `semi-active` …） |
| `%msl_entity_name%` | 锁定目标名；无锁定显示"搜索中" |
| `%msl_locked%` / `%msl_lock%` | 是否有锁定 / 整句锁定状态 |
| `%msl_target_kind%` | 目标字段：`混合` / `任意` / `实体` / `玩家` |
| `%msl_maws%` | MAWS 最近威胁方位箭头，无威胁为空 |
| `%msl_armed%` / `%msl_on%` / `%msl_global%` / `%msl_active%` | 导引头 / 个人开关 / 全服开关 / 在飞数量 |

导引头 ActionBar 模板 `seeker.status` 也用同一套占位符，由插件内部替换，不依赖 PAPI。

## 9. 故障排查

| 现象 | 处理 |
|---|---|
| 构建报 `AccessDeniedException ...\.m2\...` | 改用 `mvn -s .mvn/local-repo-settings.xml -B package` |
| 构建报 `placeholderapi ... cached from a remote repository ID that is unavailable` | `pom.xml` 里该仓库的 `id` 必须是 `placeholderapi` |
| 右键放出了 TNT / 没反应 | 自己 `/msl off` 过、没拿主手 TNT、或缺 `missile.use` 权限 |
| 半主动发射不了 | 准星锥内没有合法目标，或视线被挡、白名单为空 |
| 锁不上生物 / 载具 | `/msl ir entity`；载具等非生物需 `/msl filter entity set oak_boat`（或 `boat`） |
| 开了 usefilter 什么都锁不上 | `/msl filter list` 看白名单是否为空，或 `/msl ir filteroff` |
| 导弹中途脱锁 / 直飞不追 | 附近有人丢出干扰物；或用的是 semiLOS；或目标已死 |
| 完全没有告警 | 需同时满足权限 + `/msl on` + 背包有指南针；红外与 semiLOS 不触发 RWR |
| 改配置没生效 | `/msl reload`（需 `missile.admin`）；`language` 改动需重连客户端 |

## 10. 目录结构

```
.
├── README.md
├── pom.xml                            # paper-api + placeholderapi(provided)
├── .mvn/local-repo-settings.xml       # 构建用的本地仓库设置（工作区内 .m2repo）
├── scripts/                           # publish-to-github.ps1、check-line-endings.ps1
├── dist/                              # 验证程序与发布包（已 gitignore，不进 jar）
└── src/main/
    ├── java/com/missile/
    │   ├── MissilePlugin.java         # 主类：注册监听器 / 命令 / 占位符，每 tick 任务
    │   ├── MissileCommand.java        # /msl 命令（型号 / on|off / default / filter / status / global / reload）
    │   ├── MissileType.java           # 5 型号默认参数 + radarHoming() + TargetKind
    │   ├── Missile.java               # 单发导弹：制导、干扰、粒子、引爆
    │   ├── SemiLOSMissile.java        # 驾束制导子类
    │   ├── MissileManager.java        # 在飞导弹注册表与发射分流
    │   ├── SeekerListener.java        # 右键开导引头 / 锁定 / 发射、导引头与 safilter 状态
    │   ├── SaProfile.java             # 超级主动弹锁定配置快照
    │   ├── TargetSelector.java        # 视线锁定与准星锥角最近者选择 + 白名单通道
    │   ├── TargetFilter.java          # 目标筛选：数据、命令解析、命中判定
    │   ├── FilterStorage.java         # 筛选数据持久化（JSON / MySQL，异步 + 脏检查）
    │   ├── Settings.java              # 两份配置的唯一读取入口（可热重载）
    │   ├── Lang.java                  # 多语言文案 + 颜色转换
    │   ├── MissilePlaceholders.java   # PAPI 扩展 + ActionBar 内部替换
    │   ├── RwrManager.java            # RWR + MAWS 判定与轮询
    │   ├── RwrDisplay.java            # 按槽位各一条 BossBar
    │   └── RwrListener.java           # 退出 / 死亡 / 重生清理
    └── resources/
        ├── plugin.yml                 # 命令、权限、softdepend: [PlaceholderAPI]
        ├── config.yml                 # 通用项
        ├── msl_config.yml             # 导弹参数
        └── lang/zh_cn.yml, en_us.yml  # 文案
```