package com.quizmate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quizmate.common.BizException;
import com.quizmate.domain.Major;
import com.quizmate.domain.Subject;
import com.quizmate.dto.Dtos;
import com.quizmate.mapper.MajorMapper;
import com.quizmate.mapper.SubjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 首次设置向导 + 考试计划。
 *
 * 新用户进来先确定「哪个省 / 什么专业 / 这次考哪几门 / 什么时候考」，
 * 首页只围绕这次考期做倒计时和复习安排；老数据通过 DbMigration 自动跳过向导。
 */
@Service
public class SetupService {

    public static final String SETUP_DONE = "setup_done";

    private static final String[] COLORS = {
            "#4F46E5", "#0EA5E9", "#F59E0B", "#10B981", "#EF4444", "#8B5CF6",
            "#06B6D4", "#F97316", "#84CC16", "#EC4899", "#14B8A6", "#6366F1"
    };

    private final MajorMapper majorMapper;
    private final SubjectMapper subjectMapper;
    private final SubjectService subjectService;
    private final JdbcTemplate jdbc;

    public SetupService(MajorMapper majorMapper, SubjectMapper subjectMapper,
                        SubjectService subjectService, JdbcTemplate jdbc) {
        this.majorMapper = majorMapper;
        this.subjectMapper = subjectMapper;
        this.subjectService = subjectService;
        this.jdbc = jdbc;
    }

    public Dtos.SetupStatus status(Long majorId) {
        boolean done = "1".equals(setting(SETUP_DONE));
        Major m = null;
        if (majorId != null) {
            m = majorMapper.selectById(majorId);
        }
        if (m == null) {
            List<Major> first = majorMapper.selectList(new LambdaQueryWrapper<Major>()
                    .orderByAsc(Major::getSort).orderByAsc(Major::getId).last("limit 1"));
            m = first.isEmpty() ? null : first.get(0);
        }
        return new Dtos.SetupStatus(done,
                m == null ? null : m.getId(),
                m == null ? null : m.getName(),
                m == null ? null : m.getProvince(),
                m == null ? null : m.getLevel());
    }

    @Transactional
    public Dtos.SetupCompleteResult complete(Dtos.SetupCompleteRequest req) {
        if (req == null) {
            throw new BizException("设置内容不能为空");
        }
        Major major = resolveMajor(req);
        if (major == null) {
            throw new BizException("先选择或填写一个专业");
        }

        List<Subject> existing = subjectMapper.selectList(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getMajorId, major.getId()));
        int created = 0;
        int updated = 0;
        int cleared = 0;

        if (req.subjects() != null) {
            for (Dtos.SetupSubjectItem item : req.subjects()) {
                if (item == null || item.name() == null || item.name().isBlank()) {
                    continue;
                }
                boolean selected = item.selected() == null || item.selected();
                String name = item.name().strip();
                String code = item.code() == null || item.code().isBlank() ? null : item.code().strip();
                String examDate = item.examDate() == null || item.examDate().isBlank()
                        ? null : item.examDate().strip();

                Subject target = null;
                if (item.id() != null) {
                    target = subjectMapper.selectById(item.id());
                }
                if (target == null) {
                    target = existing.stream()
                            .filter(s -> MajorService.sameSubject(s.getName(), s.getCode(), name, code))
                            .findFirst().orElse(null);
                }

                if (target != null) {
                    boolean changed = false;
                    boolean clearDate = false;
                    if (!target.getName().equals(name)) {
                        target.setName(name);
                        changed = true;
                    }
                    if (code != null && !code.equals(target.getCode())) {
                        target.setCode(code);
                        changed = true;
                    }
                    if (target.getMajorId() == null || !target.getMajorId().equals(major.getId())) {
                        target.setMajorId(major.getId());
                        changed = true;
                    }
                    if (!selected) {
                        if (target.getExamDate() != null && !target.getExamDate().isBlank()) {
                            clearDate = true;
                            cleared++;
                            changed = true;
                        }
                    } else if (examDate != null && !examDate.equals(target.getExamDate())) {
                        target.setExamDate(examDate);
                        changed = true;
                    }
                    if (changed) {
                        subjectMapper.updateById(target);
                        if (clearDate) {
                            // 显式清空，绕开 MyBatis-Plus 的 null 字段忽略策略
                            jdbc.update("update subject set exam_date = null where id = ?", target.getId());
                        }
                        updated++;
                    }
                } else if (selected) {
                    Subject s = new Subject();
                    s.setMajorId(major.getId());
                    s.setName(name);
                    s.setCode(code);
                    s.setExamDate(examDate);
                    s.setColor(COLORS[created % COLORS.length]);
                    s.setSort(100 + created);
                    s.setCreatedAt(LocalDateTime.now());
                    subjectMapper.insert(s);
                    existing.add(s);
                    created++;
                }
            }
        }

        markSetupDone();
        return new Dtos.SetupCompleteResult(major.getId(), created, updated, cleared);
    }

    /** 首页考试计划：按考试日期分组，最近一场单独给出倒计时。 */
    public Dtos.ExamPlan plan(Long majorId) {
        List<Dtos.SubjectView> all = subjectService.list(majorId);
        LocalDate today = LocalDate.now();
        Map<String, List<Dtos.SubjectView>> grouped = new TreeMap<>();
        List<Dtos.SubjectView> unplanned = new ArrayList<>();

        for (Dtos.SubjectView s : all) {
            String date = s.examDate() == null ? "" : s.examDate().strip();
            if (date.isEmpty()) {
                unplanned.add(s);
            } else {
                grouped.computeIfAbsent(date, k -> new ArrayList<>()).add(s);
            }
        }

        List<Dtos.PlanGroup> groups = new ArrayList<>();
        String nextDate = null;
        Long nextDays = null;
        for (Map.Entry<String, List<Dtos.SubjectView>> e : grouped.entrySet()) {
            long days = daysLeft(e.getKey(), today);
            groups.add(new Dtos.PlanGroup(e.getKey(), days, e.getValue()));
            if (nextDate == null && days >= 0) {
                nextDate = e.getKey();
                nextDays = days;
            }
        }
        // 所有考试日期都过去了：返回日期最晚的一场（最近结束的），前端显示「已结束」
        if (nextDate == null && !groups.isEmpty()) {
            Dtos.PlanGroup last = groups.get(groups.size() - 1);
            nextDate = last.examDate();
            nextDays = last.daysLeft();
        }

        return new Dtos.ExamPlan(nextDate, nextDays, all.size() - unplanned.size(), all.size(),
                groups, unplanned);
    }

    private Major resolveMajor(Dtos.SetupCompleteRequest req) {
        Major major = null;
        if (req.majorId() != null) {
            major = majorMapper.selectById(req.majorId());
        }

        String name = req.majorName() == null ? "" : req.majorName().strip();
        if (major == null && !name.isEmpty()) {
            List<Major> same = majorMapper.selectList(new LambdaQueryWrapper<Major>()
                    .eq(Major::getName, name).last("limit 1"));
            if (!same.isEmpty()) {
                major = same.get(0);
            }
        }

        if (major == null && !name.isEmpty()) {
            // 首次启动时迁移会建一个空的默认专业；如果用户选了别的专业，直接复用它，避免留下空专业
            List<Major> all = majorMapper.selectList(new LambdaQueryWrapper<Major>()
                    .orderByAsc(Major::getSort).orderByAsc(Major::getId));
            if (all.size() == 1) {
                Major only = all.get(0);
                Integer subjectCount = jdbc.queryForObject(
                        "select count(*) from subject where major_id = ?", Integer.class, only.getId());
                Integer questionCount = jdbc.queryForObject(
                        "select count(*) from question q join subject s on s.id=q.subject_id where s.major_id = ?",
                        Integer.class, only.getId());
                if ((subjectCount == null || subjectCount == 0)
                        && (questionCount == null || questionCount == 0)) {
                    major = only;
                }
            }
        }

        if (major == null) {
            if (name.isEmpty()) {
                throw new BizException("先填写专业名称");
            }
            major = new Major();
            major.setName(name);
            major.setCode(blankToNull(req.majorCode()));
            major.setColor("#5B5BE0");
            major.setSort(99);
            major.setCreatedAt(LocalDateTime.now());
        } else {
            if (!name.isEmpty()) {
                major.setName(name);
            }
            if (req.majorCode() != null) {
                major.setCode(blankToNull(req.majorCode()));
            }
        }

        if (req.province() != null) {
            major.setProvince(blankToNull(req.province()));
        }
        if (req.level() != null) {
            major.setLevel(blankToNull(req.level()));
        }

        if (major.getId() == null) {
            majorMapper.insert(major);
        } else {
            majorMapper.updateById(major);
            // 省份 / 层次允许被清空，同样要绕开 null 忽略策略
            if (req.province() != null || req.level() != null) {
                jdbc.update("update major set province = ?, level = ? where id = ?",
                        major.getProvince(), major.getLevel(), major.getId());
            }
        }
        return major;
    }

    private void markSetupDone() {
        jdbc.update("insert or replace into app_setting(key, value, updated_at) values (?,?,?)",
                SETUP_DONE, "1", LocalDateTime.now().toString());
    }

    private String setting(String key) {
        try {
            List<String> values = jdbc.query("select value from app_setting where key = ?",
                    (rs, i) -> rs.getString("value"), key);
            return values.isEmpty() ? null : values.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static long daysLeft(String date, LocalDate today) {
        try {
            LocalDate d = LocalDate.parse(date.length() > 10 ? date.substring(0, 10) : date);
            return ChronoUnit.DAYS.between(today, d);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
