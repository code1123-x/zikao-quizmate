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
 * 启动时把 db/major-catalog.json 同步到 major_catalog 表。
 *
 * 数据文件里的每个省都带 sourceUrl / version / updatedAt，界面会展示来源；
 * 没有省级官方目录的省份回退到「全国」条目（教育部 2021 专业清单）。
 */
@Slf4j
@Component
@DependsOnDatabaseInitialization
public class MajorCatalogLoader implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MajorCatalogLoader(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        CatalogFile file;
        try (InputStream in = new ClassPathResource("db/major-catalog.json").getInputStream()) {
            file = mapper.readValue(in, CatalogFile.class);
        } catch (Exception e) {
            log.warn("专业目录文件加载失败，保留已有目录数据", e);
            return;
        }
        if (file == null || file.national() == null) {
            return;
        }

        jdbc.update("delete from major_catalog");
        List<Object[]> rows = new ArrayList<>();

        String nationalSource = file.national().sourceUrl();
        String nationalVersion = file.national().version();
        String nationalUpdated = file.national().updatedAt();
        int sort = 0;
        if (file.national().items() != null) {
            for (Item item : file.national().items()) {
                if (item == null || item.name() == null || item.name().isBlank()) continue;
                rows.add(new Object[]{
                        "全国", item.level(), item.code(), item.name().strip(),
                        nationalSource, nationalVersion,
                        "教育部全国标准专业目录；各省实际开考专业以省考试院目录为准",
                        nationalUpdated, sort++
                });
            }
        }

        int provinceRows = 0;
        if (file.provinces() != null) {
            for (ProvinceCatalog p : file.provinces()) {
                if (p == null || p.province() == null || p.items() == null) continue;
                sort = 0;
                for (Item item : p.items()) {
                    if (item == null || item.name() == null || item.name().isBlank()) continue;
                    rows.add(new Object[]{
                            p.province(), item.level(), item.code(), item.name().strip(),
                            p.sourceUrl(), p.version(), p.note(), p.updatedAt(), sort++
                    });
                    provinceRows++;
                }
            }
        }

        jdbc.batchUpdate("""
                insert or replace into major_catalog
                  (province, level, code, name, source_url, version, note, updated_at, sort)
                values (?,?,?,?,?,?,?,?,?)
                """, rows);
        log.info("专业目录已同步：全国 {} 条，省级 {} 条，来源省份 {} 个",
                rows.size() - provinceRows, provinceRows,
                file.provinces() == null ? 0 : file.provinces().size());
    }

    public record CatalogFile(National national, List<ProvinceCatalog> provinces) {
    }

    public record National(String sourceUrl, String version, String updatedAt, List<Item> items) {
    }

    public record ProvinceCatalog(String province, String sourceUrl, String version,
                                  String updatedAt, String note, List<Item> items) {
    }

    public record Item(String level, String code, String name) {
    }
}
