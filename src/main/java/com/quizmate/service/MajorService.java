package com.quizmate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quizmate.common.BizException;
import com.quizmate.domain.Major;
import com.quizmate.dto.Dtos;
import com.quizmate.mapper.MajorMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class MajorService {

    public static final String DEFAULT_NAME = "计算机科学与技术";
    public static final String DEFAULT_CODE = "2080901";

    private final MajorMapper majorMapper;
    private final JdbcTemplate jdbc;

    public MajorService(MajorMapper majorMapper, JdbcTemplate jdbc) {
        this.majorMapper = majorMapper;
        this.jdbc = jdbc;
    }

    public List<Dtos.MajorView> list() {
        String sql = """
                select m.id, m.code, m.name, m.color, m.province, m.level,
                       (select count(*) from subject s where s.major_id = m.id) as sc,
                       (select count(*) from question q join subject s on s.id = q.subject_id
                         where s.major_id = m.id and q.status = 'ACTIVE') as qc,
                       (select count(*) from question q join subject s on s.id = q.subject_id
                         where s.major_id = m.id and q.status = 'ACTIVE'
                           and q.type in ('SINGLE','MULTIPLE','JUDGE')) as cc
                  from major m
                 order by m.sort, m.id
                """;
        RowMapper<Dtos.MajorView> mapper = (rs, i) -> new Dtos.MajorView(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("color"),
                rs.getString("province"), rs.getString("level"),
                rs.getInt("sc"), rs.getInt("qc"), rs.getInt("cc"));
        return jdbc.query(sql, mapper);
    }

    public Major create(Dtos.CreateMajorRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("专业名称不能为空");
        }
        String name = req.name().strip();
        Major exist = majorMapper.selectOne(new LambdaQueryWrapper<Major>()
                .eq(Major::getName, name).last("limit 1"));
        if (exist != null) {
            throw new BizException("已经有这个专业了：" + name);
        }
        Major m = new Major();
        m.setName(name);
        m.setCode(req.code() == null || req.code().isBlank() ? null : req.code().strip());
        m.setColor(req.color() == null || req.color().isBlank() ? "#5B5BE0" : req.color());
        m.setProvince(blankToNull(req.province()));
        m.setLevel(blankToNull(req.level()));
        m.setSort(99);
        m.setCreatedAt(LocalDateTime.now());
        majorMapper.insert(m);
        return m;
    }

    public Major update(Long id, Dtos.UpdateMajorRequest req) {
        Major m = majorMapper.selectById(id);
        if (m == null) {
            throw new BizException("专业不存在");
        }
        if (req.name() != null && !req.name().isBlank()) {
            String name = req.name().strip();
            Major same = majorMapper.selectOne(new LambdaQueryWrapper<Major>()
                    .eq(Major::getName, name).ne(Major::getId, id).last("limit 1"));
            if (same != null) {
                throw new BizException("已经有这个专业了：" + name);
            }
            m.setName(name);
        }
        if (req.code() != null) {
            m.setCode(req.code().isBlank() ? null : req.code().strip());
        }
        if (req.color() != null && !req.color().isBlank()) {
            m.setColor(req.color());
        }
        if (req.province() != null) {
            m.setProvince(blankToNull(req.province()));
        }
        if (req.level() != null) {
            m.setLevel(blankToNull(req.level()));
        }
        majorMapper.updateById(m);
        return m;
    }

    public void delete(Long id) {
        Major m = majorMapper.selectById(id);
        if (m == null) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from subject where major_id = ?", Integer.class, id);
        int n = count == null ? 0 : count;
        if (n > 0) {
            throw new BizException("这个专业下还有 " + n + " 门学科，请先删除学科或把它移到别的专业");
        }
        majorMapper.deleteById(id);
    }

    /** 名称完全相同、课程代码相同，或去掉括号后互相包含，都算同一门课 */
    static boolean sameSubject(String existName, String existCode, String name, String code) {
        if (existName == null || existName.isBlank()) {
            return false;
        }
        if (existName.strip().equalsIgnoreCase(name)) {
            return true;
        }
        if (existCode != null && !existCode.isBlank() && code != null && existCode.strip().equals(code)) {
            return true;
        }
        String a = normName(existName);
        String b = normName(name);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return Math.min(a.length(), b.length()) >= 4 && (a.contains(b) || b.contains(a));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** 去掉空白和括号里的说明，用来做近似匹配 */
    private static String normName(String raw) {
        return raw == null ? "" : raw
                .replaceAll("[（(\\[【].*?[）)\\]】]", "")
                .replaceAll("\\s+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** 没有专业时自动建一个默认专业，并把没有归属的学科挂进去。 */
    public Long ensureDefault() {
        Major m = majorMapper.selectOne(new LambdaQueryWrapper<Major>()
                .orderByAsc(Major::getSort).orderByAsc(Major::getId).last("limit 1"));
        if (m == null) {
            m = new Major();
            m.setName(DEFAULT_NAME);
            m.setCode(DEFAULT_CODE);
            m.setColor("#5B5BE0");
            m.setSort(0);
            m.setCreatedAt(LocalDateTime.now());
            majorMapper.insert(m);
        }
        jdbc.update("update subject set major_id = ? where major_id is null", m.getId());
        return m.getId();
    }

    public Long resolve(Long majorId) {
        if (majorId != null && majorMapper.selectById(majorId) != null) {
            return majorId;
        }
        return ensureDefault();
    }
}
