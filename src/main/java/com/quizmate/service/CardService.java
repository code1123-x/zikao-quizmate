package com.quizmate.service;

import com.quizmate.common.BizException;
import com.quizmate.domain.ReviewState;
import com.quizmate.dto.Dtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识点卡片：由主观题/填空题生成。
 * 复用 question 表，不额外建表；复习状态记在 review_state(item_type=KNOWLEDGE)。
 */
@Service
public class CardService {

    private static final String CARD_TYPES = "('FILL','TERM','SHORT','CALC','ESSAY')";

    private final JdbcTemplate jdbc;
    private final ReviewService reviewService;

    public CardService(JdbcTemplate jdbc, ReviewService reviewService) {
        this.jdbc = jdbc;
        this.reviewService = reviewService;
    }

    public List<Dtos.CardSummary> summary(Long majorId) {
        String now = LocalDateTime.now().toString();
        String where = majorId == null ? "" : " where s.major_id = " + majorId;
        String sql = ("""
                select s.id, s.name, s.color,
                       (select count(*) from question q
                         where q.subject_id = s.id and q.status='ACTIVE' and q.type in %s) as total,
                       (select count(*) from question q
                         join review_state r on r.item_type='KNOWLEDGE' and r.item_id=q.id and r.user_id=1
                        where q.subject_id = s.id and q.status='ACTIVE' and q.type in %s
                          and r.due_at <= ?) as due,
                       (select count(*) from question q
                         join review_state r on r.item_type='KNOWLEDGE' and r.item_id=q.id and r.user_id=1
                        where q.subject_id = s.id and q.status='ACTIVE' and q.type in %s
                          and r.last_result = 'CORRECT') as mastered
                  from subject s
                """ + where + """
                 order by s.sort, s.id
                """).formatted(CARD_TYPES, CARD_TYPES, CARD_TYPES);
        return jdbc.query(sql, (rs, i) -> new Dtos.CardSummary(
                rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                rs.getInt("total"), rs.getInt("due"), rs.getInt("mastered")), now);
    }

    public List<Dtos.CardView> list(Long subjectId, int limit) {
        String now = LocalDateTime.now().toString();
        String sql = """
                select q.id, q.subject_id, q.type, q.stem, q.answer, q.analysis, q.source, q.year,
                       r.reps, r.interval_days, r.last_result
                  from question q
                  left join review_state r
                    on r.item_type='KNOWLEDGE' and r.item_id=q.id and r.user_id=1
                 where q.subject_id = ? and q.status='ACTIVE' and q.type in %s
                 order by case when r.due_at is not null and r.due_at <= ? then 0 else 1 end,
                          r.due_at asc,
                          random()
                 limit ?
                """.formatted(CARD_TYPES);
        return jdbc.query(sql, (rs, i) -> {
            int reps = rs.getInt("reps");
            int interval = rs.getInt("interval_days");
            String last = rs.getString("last_result");
            return new Dtos.CardView(
                    rs.getLong("id"), rs.getLong("subject_id"), rs.getString("type"),
                    rs.getString("stem"), rs.getString("answer"), rs.getString("analysis"),
                    rs.getString("source"), (Integer) rs.getObject("year"),
                    reviewService.mastery(reps, interval, last), last);
        }, subjectId, now, limit);
    }

    public Dtos.CardReviewResult review(Long cardId, String rating) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from question where id=? and type in " + CARD_TYPES, Integer.class, cardId);
        if (exists == null || exists == 0) {
            throw new BizException("知识点不存在");
        }
        String r = rating == null ? "" : rating.toUpperCase();
        if (!r.equals("MASTER") && !r.equals("VAGUE") && !r.equals("NONE")) {
            throw new BizException("rating 只能是 MASTER / VAGUE / NONE");
        }
        ReviewState state = reviewService.apply(ReviewState.KNOWLEDGE, cardId,
                r.equals("MASTER"), r.equals("VAGUE"));
        int mastery = reviewService.mastery(state.getReps(), state.getIntervalDays(), state.getLastResult());
        return new Dtos.CardReviewResult(mastery, state.getLastResult(),
                state.getDueAt() == null ? null : state.getDueAt().toString());
    }
}
