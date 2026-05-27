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
                    project_id INTEGER,
                    target_state TEXT NOT NULL,
                    enter_state_time DATETIME NOT NULL,
                    last_notify_time DATETIME
                )
                """;
            stmt.execute(createTable1);

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

        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite database", e);
        }
    }
}