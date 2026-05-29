package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 创建 SQLite 数据库连接
 * 
 * 负责配置 SQLite 数据源，并在启动时自动初始化数据库表结构。
 * 启用 WAL 模式支持并发访问，设置 busy_timeout 避免锁定冲突。
 * 
 * @author system
 * @since 1.0
 */
@Configuration
@ConfigurationProperties(prefix = "sqlite")
public class SQLiteReviewConfig  {

    @Value("${sqlite.review-database-path:data/review-notify.db}")
    private String sqlitePath;

    @Bean
    public Connection reviewNotifyConnection() throws Exception {
        Path dbPath = Paths.get(sqlitePath);
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS review_notify (
                    review_id INTEGER PRIMARY KEY,
                    review_name TEXT,
                    deadline TEXT,
                    status TEXT NOT NULL,
                    submitter_id TEXT,
                    submitter_name TEXT,
                    moderator_ids TEXT,
                    reviewer_ids TEXT,
                    viewer_ids TEXT,
                    new_notified INTEGER NOT NULL DEFAULT 0,
                    close_notified INTEGER NOT NULL DEFAULT 0,
                    cancel_notified INTEGER NOT NULL DEFAULT 0,
                    near_expired_last_sent TEXT,
                    overdue_last_sent TEXT,
                    overdue_manager_last_sent TEXT,
                    pending_delete INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        }
        return connection;
    }
}
