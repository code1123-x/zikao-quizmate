package com.quizmate.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻量迁移：schema.sql 只能 CREATE TABLE IF NOT EXISTS，加列只能在这里做。
 */
@Slf4j
@Component
@DependsOnDatabaseInitialization
public class DbMigration {

    private final JdbcTemplate jdbc;

    public DbMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("question", "qno", "INTEGER");
        addColumnIfMissing("subject", "major_id", "INTEGER");
        addColumnIfMissing("major", "province", "TEXT");
        addColumnIfMissing("major", "level", "TEXT");
        dedupeAnswerRecords();
        try {
            jdbc.execute("create index if not exists idx_subject_major on subject(major_id)");
            jdbc.execute("""
                    create table if not exists app_setting (
                      key text primary key,
                      value text,
                      updated_at text
                    )
                    """);
        } catch (Exception e) {
            log.warn("迁移失败：专业索引 / 应用设置表", e);
        }
        backfillMajor();
        backfillSetupFlag();
    }

    /**
     * 老用户已经有题库或已经填过考试日期，说明不是第一次使用，直接标记为已完成引导，
     * 避免升级后被新向导拦在首页外面。
     */
    private void backfillSetupFlag() {
        try {
            Integer done = jdbc.queryForObject(
                    "select count(*) from app_setting where key='setup_done'", Integer.class);
            if (done != null && done > 0) {
                return;
            }
            Integer questions = jdbc.queryForObject("select count(*) from question", Integer.class);
            Integer planned = jdbc.queryForObject(
                    "select count(*) from subject where exam_date is not null and exam_date <> ''",
                    Integer.class);
            boolean legacy = (questions != null && questions > 0) || (planned != null && planned > 0);
            if (legacy) {
                jdbc.update("insert into app_setting(key, value, updated_at) values ('setup_done','1',?)",
                        LocalDateTime.now().toString());
                log.info("迁移：检测到已有学习数据，跳过首次设置向导");
            }
        } catch (Exception e) {
            log.warn("迁移失败：首次设置标记", e);
        }
    }

    /** 老数据没有专业：建一个默认专业，把所有学科挂进去。 */
    private void backfillMajor() {
        try {
            Integer count = jdbc.queryForObject("select count(*) from major", Integer.class);
            if (count == null || count == 0) {
                jdbc.update("""
                        insert into major(code, name, color, sort, created_at)
                        values (?,?,?,?,?)
                        """, "2080901", "计算机科学与技术", "#5B5BE0", 0, LocalDateTime.now().toString());
                log.info("迁移：已创建默认专业「计算机科学与技术」");
            }
            Long id = jdbc.queryForObject(
                    "select id from major order by sort, id limit 1", Long.class);
            if (id != null) {
                int n = jdbc.update("update subject set major_id = ? where major_id is null", id);
                if (n > 0) {
                    log.info("迁移：{} 门学科已归入默认专业", n);
                }
            }
        } catch (Exception e) {
            log.warn("迁移失败：专业归属", e);
        }
    }

    /**
     * 旧版本没有 (session_id, question_id) 唯一约束，快速双击/重试可能留下重复作答。
     * 先保留每组最新一条，再加唯一索引；索引让 answer() 的 upsert 能安全去重。
     */
    private void dedupeAnswerRecords() {
        try {
            int removed = jdbc.update("""
                    delete from answer_record
                     where id not in (
                       select max(id) from answer_record group by session_id, question_id
                     )
                    """);
            jdbc.execute("""
                    create unique index if not exists uk_answer_session_question
                      on answer_record(session_id, question_id)
                    """);
            if (removed > 0) {
                log.info("迁移：清理 {} 条重复作答记录", removed);
            }
        } catch (Exception e) {
            log.warn("迁移失败：作答记录唯一索引", e);
        }
    }

    private void addColumnIfMissing(String table, String column, String type) {
        try {
            List<String> cols = jdbc.query("pragma table_info(" + table + ")",
                    (rs, i) -> rs.getString("name"));
            Set<String> existing = new HashSet<>(cols);
            if (!existing.contains(column)) {
                jdbc.execute("alter table " + table + " add column " + column + " " + type);
                log.info("迁移：{}.{} 已添加", table, column);
            }
        } catch (Exception e) {
            log.warn("迁移失败 {}.{}", table, column, e);
        }
    }
}
