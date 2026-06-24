package org.example.db.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite数据库配置类
 *
 * 负责配置SQLite数据源，并在启动时自动初始化数据库表结构。
 * 启用WAL模式支持并发访问，设置busy_timeout避免锁定冲突。
 *
 * @author system
 * @since 1.0
 */
@Configuration
public class SQLiteConfig {

    /**
     * SQLite数据库文件路径，默认值为 data/notification.db
     */
    @Value("${sqlite.database-path:data/notification.db}")
    private String databasePath;

    /**
     * 创建SQLite数据源Bean
     *
     * 配置JDBC连接并初始化数据库表结构。
     * 启用WAL模式和busy_timeout以支持并发访问。
     *
     * @return SQLite数据源
     */
    @Bean
    public DataSource sqliteDataSource() {
        // 确保数据库目录存在
        ensureDirectoryExists();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        // 启用WAL模式和busy_timeout，URL参数形式
        dataSource.setUrl("jdbc:sqlite:" + databasePath + "?journal_mode=WAL&busy_timeout=30000");

        // 初始化数据库表
        initDatabase(dataSource);

        return dataSource;
    }

    /**
     * 确保数据库文件目录存在
     *
     * 如果目录不存在则自动创建。
     */
    private void ensureDirectoryExists() {
        File dbFile = new File(databasePath);
        File parentDir = dbFile.getParentFile();

        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (created) {
                System.out.println("创建数据库目录: " + parentDir.getAbsolutePath());
            }
        }
    }

    /**
     * 初始化数据库表结构
     *
     * 创建两张表：
     * 1. item_state_record - 条目状态记录表，用于即时通知的状态跟踪
     * 2. notify_log - 通知发送日志表，记录每次通知发送的结果
     *
     * 同时确保WAL模式已启用。
     *
     * @param dataSource 数据源
     * @throws RuntimeException 如果初始化失败
     */
    private void initDatabase(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 确保WAL模式启用（支持并发读写）
            stmt.execute("PRAGMA journal_mode=WAL");

            // 设置busy_timeout为30秒（当数据库锁定时等待）
            stmt.execute("PRAGMA busy_timeout=30000");

            // 创建表1: item_state_record（条目状态记录表）
            // 用于记录进入目标状态的条目，供定时通知调度器查询
            String createTable1 = """
                CREATE TABLE IF NOT EXISTS item_state_record (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id INTEGER NOT NULL UNIQUE,
                    item_name TEXT,
                    tracker_id INTEGER,
                    tracker_type TEXT,
                    project_id INTEGER,
                    target_state TEXT NOT NULL,
                    enter_state_time DATETIME NOT NULL,
                    last_notify_time DATETIME
                )
                """;
            stmt.execute(createTable1);

            // 兼容旧数据库：添加 tracker_type 列（如果不存在）
            try {
                stmt.execute("ALTER TABLE item_state_record ADD COLUMN tracker_type TEXT");
            } catch (SQLException e) {
                // 列已存在，忽略错误
            }

            // 创建表2: notify_log（通知发送日志表）
            // 用于记录每次钉钉通知发送的结果，便于追踪和排查问题
            String createTable2 = """
                CREATE TABLE IF NOT EXISTS notify_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id INTEGER NOT NULL,
                    send_time DATETIME NOT NULL,
                    receiver_userid TEXT NOT NULL,
                    notify_type TEXT NOT NULL,
                    send_result TEXT NOT NULL
                )
                """;
            stmt.execute(createTable2);

            // 创建表3: org_cache（组织架构缓存表）
            // 用于缓存员工与科长/部长的关系，减少钉钉API调用
            String createTable3 = """
                CREATE TABLE IF NOT EXISTS org_cache (
                    userid TEXT PRIMARY KEY,
                    manager_userid TEXT,
                    director_userid TEXT,
                    dept_id TEXT,
                    last_sync_time DATETIME
                )
                """;
            stmt.execute(createTable3);

            // 创建表4: config_meta（配置元数据表）
            // 用于记录初始化状态、YAML配置变更检测、配置存储
            String createTable4 = """
                CREATE TABLE IF NOT EXISTS config_meta (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    initialized BOOLEAN DEFAULT FALSE,
                    yaml_content TEXT,
                    yaml_modified_time DATETIME,
                    last_loaded_time DATETIME,
                    updated_by TEXT
                )
                """;
            stmt.execute(createTable4);

            // 创建表5: instant_notify_record（批量即时通知记录表）
            // 存储粒度：Tracker-state-通知人级别
            // 发送粒度：Tracker级别（按Project→Tracker→notify_userid分组聚合）
            // 发送内容：tracker链接（不需要列出条目）
            // 第二天00:00清空（批量通知只在当天有效）

            // 兼容旧表结构：删除包含item_id字段的旧表
            try {
                // 检查旧表是否存在item_id列（如果存在则说明是旧结构）
                var rs = stmt.executeQuery("PRAGMA table_info(instant_notify_record)");
                boolean hasItemIdColumn = false;
                while (rs.next()) {
                    String columnName = rs.getString("name");
                    if ("item_id".equals(columnName)) {
                        hasItemIdColumn = true;
                        break;
                    }
                }
                rs.close();

                if (hasItemIdColumn) {
                    // 删除旧表，重新创建符合新结构
                    stmt.execute("DROP TABLE IF EXISTS instant_notify_record");
                    System.out.println("删除旧表instant_notify_record（包含item_id字段），重新创建新结构");
                }
            } catch (SQLException e) {
                // 表不存在或查询失败，忽略
            }

            String createTable5 = """
                CREATE TABLE IF NOT EXISTS instant_notify_record (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tracker_id INTEGER NOT NULL,
                    tracker_type TEXT,
                    project_id INTEGER NOT NULL,
                    target_state TEXT NOT NULL,
                    notify_userid TEXT NOT NULL,
                    notify_date DATE NOT NULL,
                    notify_time DATETIME,
                    notify_success BOOLEAN DEFAULT FALSE,
                    UNIQUE(tracker_id, target_state, notify_userid, notify_date)
                )
                """;
            stmt.execute(createTable5);

            // 创建索引：提高查询性能
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_notify_date ON instant_notify_record(notify_date, notify_success)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_notify_userid ON instant_notify_record(notify_userid)");

            // 兼容旧数据库：添加缺失的列（如果不存在）
            try {
                stmt.execute("ALTER TABLE config_meta ADD COLUMN yaml_content TEXT");
            } catch (SQLException e) {
                // 列已存在，忽略错误
            }
            try {
                stmt.execute("ALTER TABLE config_meta ADD COLUMN updated_by TEXT");
            } catch (SQLException e) {
                // 列已存在，忽略错误
            }

            // 初始化 config_meta 表：插入默认记录（如果不存在）
            stmt.execute("INSERT OR IGNORE INTO config_meta (id, initialized, yaml_modified_time, last_loaded_time) VALUES (1, FALSE, NULL, NULL)");

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite database", e);
        }
    }
}