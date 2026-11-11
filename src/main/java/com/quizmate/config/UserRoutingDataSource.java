package com.quizmate.config;

import com.quizmate.common.CurrentUser;
import com.quizmate.common.QuizmatePaths;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机多账号：每个账号一个独立 SQLite 文件。
 * 1 号账号沿用旧 data/quiz.db，其他账号使用 data/users/u<id>/quiz.db。
 * 这样原有的业务 SQL 不需要全部加 user_id 条件，隔离边界也最彻底。
 */
public class UserRoutingDataSource extends AbstractRoutingDataSource {

    private final HikariDataSource legacy;
    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public UserRoutingDataSource(HikariDataSource legacy) {
        this.legacy = legacy;
        setTargetDataSources(Map.of(0L, legacy, 1L, legacy));
        setDefaultTargetDataSource(legacy);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return CurrentUser.id();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        long userId = CurrentUser.id();
        if (userId <= 1) {
            return legacy;
        }
        return pools.computeIfAbsent(userId, this::create);
    }

    private HikariDataSource create(long userId) {
        return DataSourceConfig.sqlite(QuizmatePaths.userDatabase(userId), 2, "u" + userId);
    }

    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
        legacy.close();
    }

    static HikariDataSource sqlite(Path path) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + path.toAbsolutePath()
                + "?journal_mode=WAL&busy_timeout=5000");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setPoolName("sqlite-u" + path.getParent().getFileName());
        return new HikariDataSource(config);
    }
}
