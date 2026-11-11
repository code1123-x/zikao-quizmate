package com.quizmate.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.quizmate.common.Json;
import com.quizmate.common.QuizmatePaths;
import com.quizmate.common.Texts;
import com.quizmate.dto.Dtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次性清洗历史数据：
 * 1) 去掉私用区字符（U+E000–U+F8FF）等不可见字符，避免页面出现方框；
 * 2) 统一中文标点：半角括号 → 全角、空括号 → 「（ ）」、中文后的 , ; : ? ! . → ，；：？！。
 * 只跑一次，标记文件 data/.text-normalized。
 */
@Slf4j
@Component
public class TextCleanup {

    private static final Path MARKER = QuizmatePaths.data(".text-normalized");

    private final JdbcTemplate jdbc;

    public TextCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(3)
    public void clean() {
        if (Files.exists(MARKER)) {
            return;
        }
        int changed = 0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select id, stem, options, analysis from question");
            for (Map<String, Object> r : rows) {
                Object rawStem = r.get("stem");
                Object rawOptions = r.get("options");
                Object rawAnalysis = r.get("analysis");
                String stem = Texts.normalize(rawStem == null ? null : rawStem.toString());
                String options = normalizeOptions(rawOptions == null ? null : rawOptions.toString());
                String analysis = Texts.normalize(rawAnalysis == null ? null : rawAnalysis.toString());
                if (!Objects.equals(stem, rawStem)
                        || !Objects.equals(options, rawOptions)
                        || !Objects.equals(analysis, rawAnalysis)) {
                    jdbc.update("update question set stem=?, options=?, analysis=? where id=?",
                            stem, options, analysis, r.get("id"));
                    changed++;
                }
            }
        } catch (Exception e) {
            log.warn("文本清洗失败", e);
            return;
        }
        try {
            Files.createDirectories(MARKER.getParent());
            Files.writeString(MARKER, "done");
        } catch (Exception ignored) {
        }
        log.info("文本清洗完成：修正 {} 道题", changed);
    }

    private String normalizeOptions(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            List<Dtos.Option> list = Json.read(json, new TypeReference<List<Dtos.Option>>() {
            });
            boolean changed = false;
            List<Dtos.Option> out = new ArrayList<>();
            for (Dtos.Option o : list) {
                String t = Texts.normalize(o.text());
                if (!Objects.equals(t, o.text())) {
                    changed = true;
                }
                out.add(new Dtos.Option(o.key(), t));
            }
            return changed ? Json.write(out) : json;
        } catch (Exception e) {
            return Texts.clean(json);
        }
    }
}
