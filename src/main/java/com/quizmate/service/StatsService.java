package com.quizmate.service;

import com.quizmate.dto.Dtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Dtos.Overview overview(Long majorId) {
        String sf = inSubjects(majorId, null);
        String qf = inSubjects(majorId, "q");

        int subjectCount = majorId == null
                ? n("select count(*) from subject")
                : n("select count(*) from subject where major_id=?", majorId);
        int questionCount = n("select count(*) from question where status='ACTIVE'" + sf);
        int choiceCount = n("""
                select count(*) from question where status='ACTIVE'
                 and type in ('SINGLE','MULTIPLE','JUDGE')""" + sf);
        int cardCount = n("""
                select count(*) from question where status='ACTIVE'
                 and type in ('FILL','TERM','SHORT','CALC','ESSAY')""" + sf);
        int pendingCount = n("select count(*) from question where status='NO_ANSWER'" + sf);
        int wrongCount = n("""
                select count(*) from question q
                 where q.status='ACTIVE'
                   and q.type in ('SINGLE','MULTIPLE','JUDGE')""" + qf + """
                   and (select a.is_correct from answer_record a
                         where a.question_id=q.id order by a.id desc limit 1) = 0""");
        int answeredQuestionCount = n("""
                select count(distinct ar.question_id) from answer_record ar
                  join question q on q.id = ar.question_id
                 where 1=1""" + qf);
        String today = LocalDate.now().toString();
        int todayAnswered = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where substr(ar.answered_at,1,10)=?""" + qf, today);

        int total = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where 1=1""" + qf);
        int correct = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where ar.is_correct=1""" + qf);
        double accuracy = total == 0 ? 0 : Math.round(correct * 1000.0 / total) / 10.0;

        String now = LocalDateTime.now().toString();
        int due = n("""
                select count(*) from review_state
                 where item_type='QUESTION' and due_at <= ?
                   and item_id in (select id from question where 1=1""" + sf + ")", now);
        int dueCards = n("""
                select count(*) from review_state
                 where item_type='KNOWLEDGE' and due_at <= ?
                   and item_id in (select id from question where 1=1""" + sf + ")", now);
        int streak = streakDays();

        return new Dtos.Overview(subjectCount, questionCount, choiceCount, cardCount, pendingCount,
                wrongCount, answeredQuestionCount, todayAnswered, accuracy, due, dueCards, streak);
    }

    /** 统计页：汇总 + 每日 + 学科 + 题型 */
    public Dtos.StatsReport report(int days, Long majorId) {
        int span = Math.max(1, Math.min(days, 60));
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        String from = today.minusDays(span - 1L).toString();

        String sf = inSubjects(majorId, null);
        String qf = inSubjects(majorId, "q");

        int total = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where 1=1""" + qf);
        int correct = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where ar.is_correct=1""" + qf);
        int minutes = n("""
                select coalesce(sum(ar.cost_ms),0)/60000 from answer_record ar
                  join question q on q.id = ar.question_id
                 where 1=1""" + qf);
        int activeDays = n("""
                select count(distinct substr(ar.answered_at,1,10)) from answer_record ar
                  join question q on q.id = ar.question_id
                 where 1=1""" + qf);
        int todayAnswered = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where substr(ar.answered_at,1,10)=?""" + qf, todayStr);
        int todayCorrect = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where ar.is_correct=1 and substr(ar.answered_at,1,10)=?""" + qf, todayStr);
        int weekAnswered = n("""
                select count(*) from answer_record ar
                  join question q on q.id = ar.question_id
                 where substr(ar.answered_at,1,10)>=?""" + qf, from);
        int choiceCount = n("""
                select count(*) from question where status='ACTIVE'
                 and type in ('SINGLE','MULTIPLE','JUDGE')""" + sf);
        int answeredQuestions = n("""
                select count(distinct ar.question_id) from answer_record ar
                  join question q on q.id = ar.question_id
                 where 1=1""" + qf);
        int cardCount = n("""
                select count(*) from question where status='ACTIVE'
                 and type in ('FILL','TERM','SHORT','CALC','ESSAY')""" + sf);
        int pendingCount = n("select count(*) from question where status='NO_ANSWER'" + sf);
        int wrongCount = n("""
                select count(*) from question q
                 where q.status='ACTIVE'
                   and q.type in ('SINGLE','MULTIPLE','JUDGE')""" + qf + """
                   and (select a.is_correct from answer_record a
                         where a.question_id=q.id order by a.id desc limit 1) = 0""");

        var overview = new Dtos.StatsOverview(total, correct, pct(correct, total), minutes, activeDays,
                streakDays(), todayAnswered, todayCorrect, weekAnswered, choiceCount, answeredQuestions,
                cardCount, pendingCount, wrongCount);

        Map<String, int[]> byDay = new HashMap<>();
        jdbc.query("""
                select substr(ar.answered_at,1,10) d, count(*) c, coalesce(sum(ar.is_correct),0) ok
                  from answer_record ar
                  join question q on q.id = ar.question_id
                 where substr(ar.answered_at,1,10)>=?""" + qf + """
                 group by d
                """, rs -> {
            byDay.put(rs.getString("d"), new int[]{rs.getInt("c"), rs.getInt("ok")});
        }, from);

        List<Dtos.StatsDaily> daily = new ArrayList<>();
        for (int i = span - 1; i >= 0; i--) {
            String d = today.minusDays(i).toString();
            int[] v = byDay.getOrDefault(d, new int[]{0, 0});
            daily.add(new Dtos.StatsDaily(d, v[0], v[1]));
        }

        String subjectWhere = majorId == null ? "" : " where s.major_id = " + majorId;
        List<Dtos.StatsSubject> subjects = jdbc.query("""
                select s.id, s.name, s.color,
                  (select count(*) from question q where q.subject_id=s.id and q.status='ACTIVE'
                     and q.type in ('SINGLE','MULTIPLE','JUDGE')) choice_count,
                  (select count(*) from question q where q.subject_id=s.id and q.status='NO_ANSWER') pending,
                  (select count(distinct ar.question_id) from answer_record ar
                     join question q on q.id=ar.question_id where q.subject_id=s.id) answered,
                  (select count(*) from answer_record ar
                     join question q on q.id=ar.question_id where q.subject_id=s.id) tries,
                  (select count(*) from answer_record ar
                     join question q on q.id=ar.question_id
                    where q.subject_id=s.id and ar.is_correct=1) correct
                  from subject s
                """ + subjectWhere + """
                 order by s.sort, s.id
                """, (rs, i) -> new Dtos.StatsSubject(
                rs.getLong("id"), rs.getString("name"), rs.getString("color"),
                rs.getInt("choice_count"), rs.getInt("answered"), rs.getInt("correct"),
                pct(rs.getInt("correct"), rs.getInt("tries")), rs.getInt("pending")));

        List<Dtos.StatsType> types = jdbc.query("""
                select q.type, count(*) tries, coalesce(sum(ar.is_correct),0) ok
                  from answer_record ar join question q on q.id=ar.question_id
                 where 1=1""" + qf + """
                 group by q.type
                 order by tries desc
                """, (rs, i) -> new Dtos.StatsType(
                rs.getString("type"), rs.getInt("tries"), rs.getInt("ok"),
                pct(rs.getInt("ok"), rs.getInt("tries"))));

        return new Dtos.StatsReport(overview, daily, subjects, types);
    }

    /** 生成 " and [alias.]subject_id in (select id from subject where major_id=?)" 片段 */
    private static String inSubjects(Long majorId, String alias) {
        if (majorId == null) {
            return "";
        }
        String col = alias == null || alias.isBlank() ? "subject_id" : alias + ".subject_id";
        return " and " + col + " in (select id from subject where major_id = " + majorId + ")";
    }

    private static int pct(int part, int total) {
        return total == 0 ? 0 : (int) Math.round(part * 100.0 / total);
    }

    private int streakDays() {
        List<String> days = jdbc.queryForList(
                "select distinct substr(answered_at,1,10) d from answer_record order by d desc", String.class);
        if (days.isEmpty()) {
            return 0;
        }
        LocalDate expect = LocalDate.now();
        if (!days.get(0).equals(expect.toString())) {
            // 今天还没刷，从昨天开始算
            expect = expect.minusDays(1);
        }
        int streak = 0;
        for (String d : days) {
            if (d.equals(expect.toString())) {
                streak++;
                expect = expect.minusDays(1);
            } else if (d.compareTo(expect.toString()) < 0) {
                break;
            }
        }
        return streak;
    }

    private int n(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }
}
