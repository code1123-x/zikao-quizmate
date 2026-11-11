package com.quizmate.config;

import com.quizmate.common.QuizmatePaths;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

@Configuration
public class DataSourceConfig {

    @Bean(name = "authDataSource")
    public DataSource authDataSource() {
        return sqlite(QuizmatePaths.data("auth.db"), 2, "auth");
    }

    @Bean(name = "authJdbc")
    public JdbcTemplate authJdbc(@Qualifier("authDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 业务默认 JdbcTemplate；必须显式标记 primary，否则会被 authJdbc 抢占。 */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(destroyMethod = "close")
    @Primary
    public DataSource dataSource() {
        HikariDataSource legacy = sqlite(QuizmatePaths.data("quiz.db"), 4, "legacy");
        // 自定义路由数据源时不要依赖 Spring Boot 给默认库建表，这里显式保证旧库有 schema。
        try (Connection connection = legacy.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("初始化数据库失败", e);
        }
        return new UserRoutingDataSource(legacy);
    }

    static HikariDataSource sqlite(Path path, int poolSize, String name) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("创建数据目录失败：" + path, e);
        }
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + path.toAbsolutePath()
                + "?journal_mode=WAL&busy_timeout=5000");
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setPoolName("sqlite-" + name);
        return new HikariDataSource(config);
    }
}
