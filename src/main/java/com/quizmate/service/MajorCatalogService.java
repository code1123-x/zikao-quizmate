package com.quizmate.service;

import com.quizmate.dto.Dtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 省级自考专业目录查询。
 *
 * 省级目录优先；没有收录的省份回退到教育部《高等教育自学考试开考专业清单（2021年）》，
 * 并在返回值里标明 scope=province / national，前端必须把来源展示给用户，
 * 不能让用户以为全国标准目录就是本省实际开考目录。
 */
@Service
public class MajorCatalogService {

    private final JdbcTemplate jdbc;

    public MajorCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Dtos.MajorCatalogView catalog(String province, String level) {
        String lv = normalizeLevel(level);
        String p = province == null || province.isBlank() ? "" : province.strip();

        if (!p.isEmpty() && count(p, lv) > 0) {
            return build(p, lv, "province");
        }
        return build("全国", lv, "national");
    }

    public List<Dtos.MajorCatalogCoverage> coverage() {
        String sql = """
                select province, count(*) as c, max(version) as version,
                       max(updated_at) as updated_at,
                       group_concat(distinct level) as levels
                  from major_catalog
                 where province <> '全国'
                 group by province
                 order by province
                """;
        return jdbc.query(sql, (rs, i) -> {
            String levels = rs.getString("levels");
            List<String> list = new ArrayList<>();
            if (levels != null) {
                for (String s : levels.split(",")) {
                    if (!s.isBlank()) list.add(s.strip());
                }
            }
            return new Dtos.MajorCatalogCoverage(rs.getString("province"), rs.getInt("c"),
                    rs.getString("version"), rs.getString("updated_at"), list);
        });
    }

    private Dtos.MajorCatalogView build(String province, String level, String scope) {
        String note;
        String sourceUrl;
        String version;
        String updatedAt;
        try {
            var meta = jdbc.queryForMap(
                    "select source_url, version, note, updated_at from major_catalog " +
                            "where province = ? and level = ? order by sort, id limit 1",
                    province, level);
            sourceUrl = (String) meta.get("source_url");
            version = (String) meta.get("version");
            note = (String) meta.get("note");
            updatedAt = (String) meta.get("updated_at");
        } catch (Exception e) {
            sourceUrl = null;
            version = null;
            note = null;
            updatedAt = null;
        }

        List<Dtos.MajorCatalogItem> items = jdbc.query(
                "select code, name from major_catalog where province = ? and level = ? order by sort, id",
                (rs, i) -> new Dtos.MajorCatalogItem(rs.getString("code"), rs.getString("name")),
                province, level);

        return new Dtos.MajorCatalogView(province, level, scope, sourceUrl, version, note, updatedAt,
                items.size(), items);
    }

    private int count(String province, String level) {
        Integer n = jdbc.queryForObject(
                "select count(*) from major_catalog where province = ? and level = ?",
                Integer.class, province, level);
        return n == null ? 0 : n;
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
