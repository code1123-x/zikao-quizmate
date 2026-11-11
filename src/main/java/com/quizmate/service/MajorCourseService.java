package com.quizmate.service;

import com.quizmate.dto.Dtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按「省份 + 专业代码」查询官方课程清单。
 *
 * 只返回省级考试院官方目录里明确收录的数据；没有收录时返回 scope=missing，
 * 前端必须明确提示用户，不能拿旧的内置参考科目冒充官方课程。
 */
@Service
public class MajorCourseService {

    private final JdbcTemplate jdbc;

    public MajorCourseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Dtos.MajorCourseView courses(String province, String level, String code, String name) {
        String p = province == null ? "" : province.strip();
        String lv = normalizeLevel(level);
        String c = code == null ? "" : code.strip();
        String n = name == null ? "" : name.strip();

        List<Map<String, Object>> rows = query(p, lv, c);
        if (rows.isEmpty() && !c.isEmpty()) {
            String base = c.replaceAll("[A-Z]+$", "");
            if (!base.equals(c)) {
                rows = query(p, lv, base);
            }
        }
        if (rows.isEmpty() && !n.isEmpty()) {
            rows = jdbc.queryForList("""
                    select major_code, major_name, course_code, course_name,
                           source_url, version, note, updated_at
                      from major_course
                     where province = ? and level = ? and major_name = ?
                     order by sort, id
                    """, p, lv, n);
        }

        if (rows.isEmpty()) {
            return new Dtos.MajorCourseView(p, lv, c, n, "missing", null, null,
                    "该专业官方完整课程设置尚未收录。现有公开资料只有每次考试的开考课程，不能当作完整专业计划，请手动添加或以省考试院专业计划为准。",
                    null, 0, List.of());
        }

        Map<String, Object> meta = rows.get(0);
        List<Dtos.MajorCourseItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(new Dtos.MajorCourseItem(
                    (String) row.get("course_code"),
                    (String) row.get("course_name")
            ));
        }
        return new Dtos.MajorCourseView(
                p, lv,
                (String) meta.get("major_code"),
                (String) meta.get("major_name"),
                "province",
                (String) meta.get("source_url"),
                (String) meta.get("version"),
                (String) meta.get("note"),
                (String) meta.get("updated_at"),
                items.size(),
                items
        );
    }

    private List<Map<String, Object>> query(String province, String level, String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select major_code, major_name, course_code, course_name,
                       source_url, version, note, updated_at
                  from major_course
                 where province = ? and level = ? and major_code = ?
                 order by sort, id
                """, province, level, code);
    }

    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return "本科";
        }
        String l = level.strip();
        if (l.contains("专科") || l.contains("高职")) {
            return "专科";
        }
        return "本科";
    }
}
