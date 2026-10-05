package com.missile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * 目标筛选数据的持久化：默认 JSON，可选 MySQL；读写全部走**异步**线程。
 *
 * <p>线程约定（重要）：
 * <ul>
 *   <li>{@link #snapshotRows()} 读的是 {@link TargetFilter} 的内存数据，**只在主线程调用**，
 *       立刻转成不可变的 {@link Row} 列表；异步线程只接触这份拷贝，避免边改边序列化。</li>
 *   <li>写盘 / SQL 在异步线程执行；载入完成后回到主线程才写入 {@link TargetFilter}。</li>
 *   <li>脏数据检测靠序列化结果比对：每 {@code auto-save-interval-seconds} 秒在主线程打一次快照，
 *       与上次写入内容不同才落盘，因此任何修改路径（命令、将来的批量导入）都会自动被保存。</li>
 * </ul>
 *
 * <p>MySQL 只用 JDK 的 {@code java.sql}：不引第三方依赖、不 shade 驱动。
 * 若服务端没有 MySQL 驱动或连接失败，会**告警并自动回退 JSON**（本次运行内不再重试）。
 */
final class FilterStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String MYSQL_DRIVER_NEW = "com.mysql.cj.jdbc.Driver";
    private static final String MYSQL_DRIVER_OLD = "com.mysql.jdbc.Driver";

    private final MissilePlugin plugin;
    private String lastJson = "";
    private boolean mysqlBroken;
    private BukkitTask autoSaveTask;

    FilterStorage(MissilePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动：异步载入 + 定时脏检查保存。 */
    void start() {
        this.checkMysqlDriver();
        this.loadAsync();
        long period = Math.max(20L, Settings.filterAutoSaveSeconds() * 20L);
        this.autoSaveTask = this.plugin.getServer().getScheduler()
                .runTaskTimer(this.plugin, this::autoSaveTick, period, period);
    }

    /** 关服：停掉定时器并做一次同步落盘，避免丢数据。 */
    void stop() {
        if (this.autoSaveTask != null) {
            this.autoSaveTask.cancel();
            this.autoSaveTask = null;
        }
        List<Row> rows = this.snapshotRows();
        String json = GSON.toJson(rows);
        if (!json.equals(this.lastJson)) {
            this.lastJson = json;
            this.write(rows, json);          // 同步：关服时异步任务可能已被取消
        }
    }

    /** 立刻保存一次（{@code /msl reload} 等场景可调用）。 */
    void saveNow() {
        List<Row> rows = this.snapshotRows();
        String json = GSON.toJson(rows);
        this.lastJson = json;
        this.writeAsync(rows, json);
    }

    // ------------------------------------------------------------------ 脏检查与写入

    /** 主线程定时任务：内容有变化才异步落盘。 */
    private void autoSaveTick() {
        List<Row> rows = this.snapshotRows();
        String json = GSON.toJson(rows);
        if (json.equals(this.lastJson)) {
            return;
        }
        this.lastJson = json;
        this.writeAsync(rows, json);
    }

    private void writeAsync(List<Row> rows, String json) {
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> this.write(rows, json));
    }

    private void write(List<Row> rows, String json) {
        try {
            if (this.useMysql()) {
                this.writeMysql(rows);
            } else {
                this.writeJson(json);
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(Level.SEVERE, "[Missile] 筛选数据保存失败", throwable);
        }
    }

    // ------------------------------------------------------------------ 载入

    private void loadAsync() {
        this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, () -> {
            List<Row> rows = Collections.emptyList();
            String json = "[]";
            try {
                if (this.useMysql()) {
                    rows = this.readMysql();
                    json = GSON.toJson(rows);
                } else {
                    json = this.readJsonText();
                    rows = this.parseRows(json);
                }
            } catch (Throwable throwable) {
                this.plugin.getLogger().log(Level.SEVERE,
                        "[Missile] 筛选数据载入失败，本次以空数据启动（不影响插件运行）", throwable);
                rows = Collections.emptyList();
                json = "[]";
            }
            final List<Row> loaded = rows;
            final String loadedJson = json;
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.applyLoaded(loaded, loadedJson));
        });
    }

    /** 回到主线程才写入内存：已被本地修改过的玩家以本地为准，避免刚设好的筛选被载入覆盖。 */
    private void applyLoaded(List<Row> rows, String json) {
        Map<UUID, TargetFilter.FilterData> local = TargetFilter.snapshot();
        for (Row row : rows) {
            UUID playerId = row.uuid();
            if (playerId == null || local.containsKey(playerId)) {
                continue;
            }
            TargetFilter.restore(playerId, row.toFilterData());
        }
        this.lastJson = json;
    }

    // ------------------------------------------------------------------ JSON

    private File jsonFile() {
        return new File(this.plugin.getDataFolder(), Settings.filterJsonFile());
    }

    private void writeJson(String json) throws IOException {
        File file = this.jsonFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
    }

    private String readJsonText() throws IOException {
        File file = this.jsonFile();
        if (!file.isFile()) {
            return "[]";
        }
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return text.isBlank() ? "[]" : text;
    }

    private List<Row> parseRows(String json) {
        try {
            List<Row> rows = GSON.fromJson(json, new TypeToken<List<Row>>() { }.getType());
            return rows == null ? Collections.emptyList() : rows;
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(Level.WARNING, "[Missile] JSON 筛选数据解析失败，忽略该文件", throwable);
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ MySQL

    private boolean useMysql() {
        return !this.mysqlBroken && "MYSQL".equalsIgnoreCase(Settings.filterStorageType());
    }

    private void checkMysqlDriver() {
        if (!"MYSQL".equalsIgnoreCase(Settings.filterStorageType())) {
            return;
        }
        if (hasClass(MYSQL_DRIVER_NEW) || hasClass(MYSQL_DRIVER_OLD)) {
            return;
        }
        this.mysqlBroken = true;
        this.plugin.getLogger().warning("[Missile] 未找到 MySQL 驱动（com.mysql.cj.jdbc.Driver），"
                + "已自动回退 JSON 存储；如需 MySQL 请把 mysql-connector-j 放入服务端 lib/ 或改用 JSON。");
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private String tableName() {
        String table = Settings.mysqlTable();
        return table == null || table.isBlank() ? "missile_filters" : table;
    }

    private String jdbcUrl() {
        String ssl = Settings.mysqlUseSsl() ? "true" : "false";
        return "jdbc:mysql://" + Settings.mysqlHost() + ":" + Settings.mysqlPort() + "/" + Settings.mysqlDatabase()
                + "?useSSL=" + ssl + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC";
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(this.jdbcUrl(), Settings.mysqlUser(), Settings.mysqlPassword());
    }

    private void writeMysql(List<Row> rows) throws SQLException {
        String table = this.tableName();
        try (Connection connection = this.openConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                        + "uuid VARCHAR(36) PRIMARY KEY,"
                        + "enabled TINYINT(1) NOT NULL DEFAULT 0,"
                        + "entity_type TINYINT(1) NOT NULL DEFAULT 0,"
                        + "entity_ids TEXT,"
                        + "player_type TINYINT(1) NOT NULL DEFAULT 0,"
                        + "player_ids TEXT,"
                        + "player_names TEXT) DEFAULT CHARSET=utf8mb4");
            }
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table);
                 PreparedStatement upsert = connection.prepareStatement("INSERT INTO " + table
                         + " (uuid, enabled, entity_type, entity_ids, player_type, player_ids, player_names)"
                         + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                delete.executeUpdate();                    // 全量覆盖：数据量等于玩家数，很小
                for (Row row : rows) {
                    if (row.uuid() == null) {
                        continue;
                    }
                    upsert.setString(1, row.uuid().toString());
                    upsert.setInt(2, row.enabled ? 1 : 0);
                    upsert.setInt(3, row.entityType ? 1 : 0);
                    upsert.setString(4, String.join(",", row.entityIds));
                    upsert.setInt(5, row.playerType ? 1 : 0);
                    upsert.setString(6, String.join(",", row.playerIds));
                    upsert.setString(7, String.join(",", row.playerNames));
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private List<Row> readMysql() throws SQLException {
        String table = this.tableName();
        List<Row> rows = new ArrayList<>();
        try (Connection connection = this.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY,"
                    + "enabled TINYINT(1) NOT NULL DEFAULT 0,"
                    + "entity_type TINYINT(1) NOT NULL DEFAULT 0,"
                    + "entity_ids TEXT,"
                    + "player_type TINYINT(1) NOT NULL DEFAULT 0,"
                    + "player_ids TEXT,"
                    + "player_names TEXT) DEFAULT CHARSET=utf8mb4");
            try (ResultSet result = statement.executeQuery("SELECT uuid, enabled, entity_type, entity_ids,"
                    + " player_type, player_ids, player_names FROM " + table)) {
                while (result.next()) {
                    Row row = new Row();
                    row.uuid = result.getString("uuid");
                    row.enabled = result.getInt("enabled") != 0;
                    row.entityType = result.getInt("entity_type") != 0;
                    row.entityIds = split(result.getString("entity_ids"));
                    row.playerType = result.getInt("player_type") != 0;
                    row.playerIds = split(result.getString("player_ids"));
                    row.playerNames = split(result.getString("player_names"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            if (!token.isBlank()) {
                values.add(token.trim());
            }
        }
        return values;
    }

    // ------------------------------------------------------------------ 快照

    /** 主线程调用：把内存数据转成不可变行（异步线程只碰这份拷贝）。 */
    private List<Row> snapshotRows() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, TargetFilter.FilterData> entry : TargetFilter.snapshot().entrySet()) {
            rows.add(Row.of(entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    /** 存储用的一行数据：字段保持可变、无 final，便于 Gson 反射读写。 */
    static final class Row {

        private String uuid;
        private boolean enabled;
        private boolean entityType;
        private List<String> entityIds = new ArrayList<>();
        private boolean playerType;
        private List<String> playerIds = new ArrayList<>();
        private List<String> playerNames = new ArrayList<>();

        private static Row of(UUID playerId, TargetFilter.FilterData data) {
            Row row = new Row();
            row.uuid = playerId.toString();
            row.enabled = data.enabled;
            row.entityType = data.entityTypeIncluded;
            row.entityIds = new ArrayList<>(data.entityIds);
            row.playerType = data.playerTypeIncluded;
            row.playerIds = new ArrayList<>();
            for (UUID id : data.playerIds) {
                row.playerIds.add(id.toString());
            }
            row.playerNames = new ArrayList<>(data.playerNames);
            return row;
        }

        private UUID uuid() {
            if (this.uuid == null) {
                return null;
            }
            try {
                return UUID.fromString(this.uuid.trim());
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private TargetFilter.FilterData toFilterData() {
            TargetFilter.FilterData data = new TargetFilter.FilterData();
            data.enabled = this.enabled;
            data.entityTypeIncluded = this.entityType;
            data.entityIds.clear();
            if (this.entityIds != null) {
                // §2.2 的读取侧迁移：老存档里存的是裸 ID（`oak_boat`），一律规范化成完整注册键
                // （`minecraft:oak_boat`）再进内存，否则升级后老白名单会全部失配。
                for (String raw : this.entityIds) {
                    String id = TargetFilter.normalizeId(raw);
                    if (!id.isEmpty() && !data.entityIds.contains(id)) {
                        data.entityIds.add(id);
                    }
                }
            }
            data.playerTypeIncluded = this.playerType;
            data.playerIds.clear();
            data.playerNames.clear();
            if (this.playerIds != null) {
                for (String raw : this.playerIds) {
                    try {
                        data.playerIds.add(UUID.fromString(raw.trim()));
                    } catch (IllegalArgumentException ignored) {
                        // 忽略损坏的 UUID
                    }
                }
            }
            if (this.playerNames != null) {
                for (String name : this.playerNames) {
                    data.playerNames.add(name.toLowerCase(Locale.ROOT));
                }
            }
            // 老存档里可能存着"筛选模式 on + 白名单全空"（会变成什么都锁不上，风险 #7）：
            // 载入时按同一条不变量纠正为自由锁定（enabled = true ⟹ 白名单非空）
            if (data.enabled && data.isEmpty()) {
                data.enabled = false;
            }
            return data;
        }
    }
}
