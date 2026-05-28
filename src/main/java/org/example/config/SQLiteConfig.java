package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

@Configuration
public class SQLiteConfig {

    @Value("${review.sqlite-path:./data/review-notify.db}")
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
                    moderator_names TEXT,
                    reviewer_ids TEXT,
                    reviewer_names TEXT,
                    viewer_ids TEXT,
                    viewer_names TEXT,
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
