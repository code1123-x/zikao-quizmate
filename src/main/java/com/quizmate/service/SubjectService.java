package com.quizmate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quizmate.common.BizException;
import com.quizmate.domain.Chapter;
import com.quizmate.domain.Subject;
import com.quizmate.dto.Dtos;
import com.quizmate.mapper.ChapterMapper;
import com.quizmate.mapper.SubjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SubjectService {

    private final SubjectMapper subjectMapper;
    private final ChapterMapper chapterMapper;
    private final MajorService majorService;
    private final JdbcTemplate jdbc;

    public SubjectService(SubjectMapper subjectMapper, ChapterMapper chapterMapper,
                          MajorService majorService, JdbcTemplate jdbc) {
        this.subjectMapper = subjectMapper;
        this.chapterMapper = chapterMapper;
        this.majorService = majorService;
        this.jdbc = jdbc;
    }

    public List<Dtos.SubjectView> list(Long majorId) {
        String now = java.time.LocalDateTime.now().toString();
        String sql = """
                select s.id, s.code, s.name, s.color, s.exam_date,
                       (select count(*) from question q where q.subject_id = s.id) as qc,
                       (select count(*) from question q where q.subject_id = s.id
                          and q.status='ACTIVE' and q.type in ('SINGLE','MULTIPLE','JUDGE')) as choice_count,
                       (select count(*) from question q where q.subject_id = s.id
                          and q.status='ACTIVE' and q.type in ('FILL','TERM','SHORT','CALC','ESSAY')) as card_count,
                       (select count(*) from question q where q.subject_id = s.id
                          and q.status='NO_ANSWER') as pending_count,
                       (select count(distinct ar.question_id) from answer_record ar
                          join question q on q.id = ar.question_id where q.subject_id = s.id) as ac,
                       (select count(*) from answer_record ar
                          join question q on q.id = ar.question_id
                         where q.subject_id = s.id and ar.is_correct = 1) as cc,
                       (select count(*) from review_state rs
                          join question q on q.id = rs.item_id
                         where q.subject_id = s.id and rs.user_id = 1
                           and rs.item_type = 'QUESTION' and rs.due_at <= ?) as due_count
                  from subject s
                """;
        List<Object> args = new java.util.ArrayList<>();
        args.add(now);
        if (majorId != null) {
            sql += " where s.major_id = ?";
            args.add(majorId);
        }
        sql += " order by s.sort, s.id";
        RowMapper<Dtos.SubjectView> mapper = (rs, i) -> {
            int answered = rs.getInt("ac");
            int correct = rs.getInt("cc");
            double acc = answered == 0 ? 0 : Math.round(correct * 1000.0 / answered) / 10.0;
            return new Dtos.SubjectView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                    rs.getString("color"), rs.getString("exam_date"), rs.getInt("qc"),
                    rs.getInt("choice_count"), rs.getInt("card_count"), rs.getInt("pending_count"),
                    answered, acc, rs.getInt("due_count"));
        };
        return jdbc.query(sql, mapper, args.toArray());
    }

    public Subject create(Dtos.CreateSubjectRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("学科名称不能为空");
        }
        Subject s = new Subject();
        s.setMajorId(majorService.resolve(req.majorId()));
        s.setName(req.name().strip());
        s.setCode(req.code());
        s.setColor(req.color() == null || req.color().isBlank() ? "#4F46E5" : req.color());
        s.setExamDate(req.examDate());
        s.setSort(99);
        s.setCreatedAt(LocalDateTime.now());
        subjectMapper.insert(s);
        return s;
    }

    public Subject update(Long id, Dtos.UpdateSubjectRequest req) {
        Subject s = subjectMapper.selectById(id);
        if (s == null) {
            throw new BizException("学科不存在");
        }
        if (req.name() != null && !req.name().isBlank()) {
            s.setName(req.name().strip());
        }
        subjectMapper.updateById(s);
        // MyBatis-Plus 默认忽略 null 字段，清空代码/考试日期要显式 update
        if (req.code() != null) {
            String code = req.code().isBlank() ? null : req.code().strip();
            jdbc.update("update subject set code = ? where id = ?", code, id);
            s.setCode(code);
        }
        if (req.examDate() != null) {
            String examDate = req.examDate().isBlank() ? null : req.examDate().strip();
            jdbc.update("update subject set exam_date = ? where id = ?", examDate, id);
            s.setExamDate(examDate);
        }
        return s;
    }

    @Transactional
    public void delete(Long id) {
        Subject s = subjectMapper.selectById(id);
        if (s == null) {
            throw new BizException("学科不存在");
        }
        Integer questionCount = jdbc.queryForObject(
                "select count(*) from question where subject_id = ?", Integer.class, id);
        if (questionCount != null && questionCount > 0) {
            throw new BizException("这门学科还有 " + questionCount + " 道题，请先在题库中清空后再删除");
        }
        // 已加入考试计划的学科：取消考试计划里的勾选，并保留在考试计划列表中
        if (s.getExamDate() != null && !s.getExamDate().isBlank()) {
            jdbc.update("update subject set exam_date = null where id = ?", id);
            return;
        }
        jdbc.update("delete from ai_check where question_id in (select id from question where subject_id=?)", id);
        jdbc.update("delete from answer_record where question_id in (select id from question where subject_id=?)", id);
        jdbc.update("""
                delete from review_state
                 where item_type in ('QUESTION','KNOWLEDGE')
                   and item_id in (select id from question where subject_id=?)
                """, id);
        jdbc.update("delete from practice_session where subject_id=?", id);
        jdbc.update("delete from question where subject_id=?", id);
        jdbc.update("delete from chapter where subject_id=?", id);
        jdbc.update("delete from subject where id=?", id);
    }

    public List<Dtos.ChapterView> chapters(Long subjectId) {
        List<Chapter> list = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getSubjectId, subjectId)
                .orderByAsc(Chapter::getSort)
                .orderByAsc(Chapter::getId));
        return list.stream().map(c -> {
            Integer count = jdbc.queryForObject(
                    "select count(*) from question where chapter_id = ?", Integer.class, c.getId());
            return new Dtos.ChapterView(c.getId(), c.getParentId(), c.getName(),
                    c.getDepth() == null ? 0 : c.getDepth(), count == null ? 0 : count);
        }).toList();
    }
}
