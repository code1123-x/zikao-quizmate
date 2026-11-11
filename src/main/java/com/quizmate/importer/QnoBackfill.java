package com.quizmate.importer;

import com.quizmate.common.QuizmatePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 历史题目的 qno 是后加的列，老数据为 null。
 * 这里按 (学科, 来源文件) 重新解析一次原始资料，用 hash 把题号回填进去。
 * 只跑一次，标记文件 data/.qno-backfilled；删掉标记文件即可重跑。
 */
@Slf4j
@Component
public class QnoBackfill {

    private static final Path MARKER = QuizmatePaths.data(".qno-backfilled");

    private final JdbcTemplate jdbc;
    private final SourceLocator locator;
    private final TextExtractor extractor;
    private final PaperParser parser;

    public QnoBackfill(JdbcTemplate jdbc, SourceLocator locator, TextExtractor extractor, PaperParser parser) {
        this.jdbc = jdbc;
        this.locator = locator;
        this.extractor = extractor;
        this.parser = parser;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void backfill() {
        if (Files.exists(MARKER)) {
            return;
        }
        List<Map<String, Object>> groups;
        try {
            groups = jdbc.queryForList("""
                    select subject_id, source, count(*) as c
                      from question
                     where qno is null and source is not null
                     group by subject_id, source
                     order by c desc
                    """);
        } catch (Exception e) {
            log.warn("题号回填：查询失败，跳过", e);
            return;
        }
        if (groups.isEmpty()) {
            mark();
            return;
        }

        int files = 0;
        int updated = 0;
        long start = System.currentTimeMillis();
        for (Map<String, Object> g : groups) {
            Long subjectId = ((Number) g.get("subject_id")).longValue();
            String source = (String) g.get("source");
            Path path = locator.find(source);
            if (path == null) {
                log.info("题号回填：找不到原始文件 {}", source);
                continue;
            }
            files++;
            try {
                TextExtractor.Extracted ex = extractor.extract(path.toFile());
                if (ex.text() == null || ex.text().isBlank()) {
                    continue;
                }
                PaperParser.ParseResult pr = parser.parse(ex.text());
                int n = 0;
                for (ParsedQuestion pq : pr.questions()) {
                    if (pq.getNumber() <= 0) {
                        continue;
                    }
                    String hash = Hashes.question(subjectId, pq.getStem());
                    n += jdbc.update("""
                            update question set qno=?
                             where subject_id=? and source=? and hash=? and qno is null
                            """, pq.getNumber(), subjectId, source, hash);
                }
                updated += n;
                if (n > 0) {
                    log.info("题号回填：{} 回填 {} 题", source, n);
                }
            } catch (Exception e) {
                log.warn("题号回填失败 {}", source, e);
            }
        }
        mark();
        log.info("题号回填完成：扫描 {} 个文件，回填 {} 题，耗时 {} ms",
                files, updated, System.currentTimeMillis() - start);
    }

    private void mark() {
        try {
            Files.createDirectories(MARKER.getParent());
            Files.writeString(MARKER, "done");
        } catch (Exception ignored) {
        }
    }
}
