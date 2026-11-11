package com.quizmate.auth;

import com.quizmate.common.QuizmatePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 新注册账号第一次登录前创建自己的数据库，并复制共享的官方专业/课程目录。
 * 题库、学科、统计等业务表从空开始。
 */
@Slf4j
@Service
public class UserDatabaseService {

    public void createIfNeeded(long userId) {
        if (userId <= 1) {
            return; // 1 号账号沿用旧 data/quiz.db
        }
        Path db = QuizmatePaths.userDatabase(userId);
        if (Files.exists(db)) {
            ensureColumns(db);
            return;
        }
        try {
            Files.createDirectories(db.toAbsolutePath().getParent());
            String url = "jdbc:sqlite:" + db.toAbsolutePath() + "?journal_mode=WAL&busy_timeout=5000";
            try (Connection connection = DriverManager.getConnection(url)) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
                ensureColumns(connection);
                copyCatalogs(connection);
            }
            log.info("已创建账号数据库 {}", db);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(db);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("创建账号数据库失败", e);
        }
    }

    /** 旧账号数据库可能缺少后来新增的列，登录时补一次。 */
    private void ensureColumns(Path db) {
        String url = "jdbc:sqlite:" + db.toAbsolutePath() + "?journal_mode=WAL&busy_timeout=5000";
        try (Connection connection = DriverManager.getConnection(url)) {
            ensureColumns(connection);
        } catch (Exception e) {
            log.warn("升级账号数据库列失败：{}", db, e);
        }
    }

    private void ensureColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "question", "qno", "INTEGER");
        addColumnIfMissing(connection, "subject", "major_id", "INTEGER");
        addColumnIfMissing(connection, "major", "province", "TEXT");
        addColumnIfMissing(connection, "major", "level", "TEXT");
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String type)
            throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("pragma table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement s = connection.createStatement()) {
            s.execute("alter table " + table + " add column " + column + " " + type);
            log.info("账号数据库迁移：{}.{} 已添加", table, column);
        }
    }

    /** 官方专业/课程目录是共享数据，新账号直接复制一份，避免首次打开目录为空。 */
    private void copyCatalogs(Connection target) {
        Path legacy = QuizmatePaths.data("quiz.db");
        if (!Files.exists(legacy)) {
            return;
        }
        String path = legacy.toAbsolutePath().toString().replace("'", "''");
        try (Statement s = target.createStatement()) {
            s.execute("attach database '" + path + "' as legacy");
            s.execute("""
                    insert into major_catalog
                      (province, level, code, name, source_url, version, note, updated_at, sort)
                    select province, level, code, name, source_url, version, note, updated_at, sort
                      from legacy.major_catalog
                    """);
            s.execute("""
                    insert into major_course
                      (province, level, major_code, major_name, course_code, course_name,
                       source_url, version, note, updated_at, sort)
                    select province, level, major_code, major_name, course_code, course_name,
                           source_url, version, note, updated_at, sort
                      from legacy.major_course
                    """);
            s.execute("detach database legacy");
        } catch (Exception e) {
            throw new IllegalStateException("复制专业目录失败", e);
        }
    }
}
