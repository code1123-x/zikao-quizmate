package com.quizmate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时把 db/major-courses.json 同步到 major_course 表。
 *
 * 数据按「省份 + 专业代码」组织，每个专业下列出该省官方考试计划中的课程。
 * 没有收录的省份/专业，前端会明确提示「本省官方课程目录尚未收录」。
 */
@Slf4j
@Component
@DependsOnDatabaseInitialization
public class MajorCourseLoader implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MajorCourseLoader(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        CourseFile file;
        try (InputStream in = new ClassPathResource("db/major-courses.json").getInputStream()) {
            file = mapper.readValue(in, CourseFile.class);
        } catch (Exception e) {
            log.warn("专业课程文件加载失败，保留已有课程数据", e);
            return;
        }
        if (file == null || file.provinces() == null) {
            return;
        }

        jdbc.update("delete from major_course");
        List<Object[]> rows = new ArrayList<>();
        int majorCount = 0;

        for (ProvinceCourses p : file.provinces()) {
            if (p == null || p.province() == null || p.majors() == null) continue;
            for (MajorCourses m : p.majors()) {
                if (m == null || m.code() == null || m.name() == null || m.courses() == null) continue;
                int sort = 0;
                boolean hasCourse = false;
                for (Course c : m.courses()) {
                    if (c == null || c.name() == null || c.name().isBlank()) continue;
                    rows.add(new Object[]{
                            p.province(), m.level(), m.code(), m.name().strip(),
                            c.code(), c.name().strip(),
                            p.sourceUrl(), p.version(), p.note(), p.updatedAt(), sort++
                    });
                    hasCourse = true;
                }
                if (hasCourse) majorCount++;
            }
        }

        jdbc.batchUpdate("""
                insert into major_course
                  (province, level, major_code, major_name, course_code, course_name,
                   source_url, version, note, updated_at, sort)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, rows);
        log.info("专业课程已同步：{} 个省、{} 个专业、{} 门课程",
                file.provinces().size(), majorCount, rows.size());
    }

    public record CourseFile(List<ProvinceCourses> provinces) {
    }

    public record ProvinceCourses(String province, String sourceUrl, String version,
                                  String updatedAt, String note, List<MajorCourses> majors) {
    }

    public record MajorCourses(String code, String name, String level, List<Course> courses) {
    }

    public record Course(String code, String name) {
    }
}
