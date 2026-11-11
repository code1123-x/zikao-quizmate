package com.quizmate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quizmate.common.BizException;
import com.quizmate.common.Json;
import com.quizmate.domain.AnswerRecord;
import com.quizmate.domain.PracticeSession;
import com.quizmate.domain.Question;
import com.quizmate.domain.ReviewState;
import com.quizmate.domain.Subject;
import com.quizmate.dto.Dtos;
import com.quizmate.mapper.AnswerRecordMapper;
import com.quizmate.mapper.PracticeSessionMapper;
import com.quizmate.mapper.QuestionMapper;
import com.quizmate.mapper.ReviewStateMapper;
import com.quizmate.mapper.SubjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PracticeService {

    private final SubjectMapper subjectMapper;
    private final QuestionMapper questionMapper;
    private final PracticeSessionMapper sessionMapper;
    private final AnswerRecordMapper answerMapper;
    private final QuestionService questionService;
    private final ReviewService reviewService;
    private final JdbcTemplate jdbc;

    public PracticeService(SubjectMapper subjectMapper, QuestionMapper questionMapper,
                           PracticeSessionMapper sessionMapper, AnswerRecordMapper answerMapper,
                           QuestionService questionService, ReviewService reviewService,
                           JdbcTemplate jdbc) {
        this.subjectMapper = subjectMapper;
        this.questionMapper = questionMapper;
        this.sessionMapper = sessionMapper;
        this.answerMapper = answerMapper;
        this.questionService = questionService;
        this.reviewService = reviewService;
        this.jdbc = jdbc;
    }

    @Transactional
    public Dtos.SessionView create(Dtos.CreateSessionRequest req) {
        if (req.subjectId() == null) {
            throw new BizException("请选择学科");
        }
        Subject subject = subjectMapper.selectById(req.subjectId());
        if (subject == null) {
            throw new BizException("学科不存在");
        }
        int count = req.count() == null ? 20 : Math.max(1, Math.min(req.count(), 200));
        List<Long> ids;
        List<String> types = new ArrayList<>();
        if (req.types() != null) {
            req.types().stream()
                    .filter(t -> t != null && t.matches("SINGLE|MULTIPLE|JUDGE"))
                    .forEach(types::add);
        }
        if (types.isEmpty() && req.type() != null && !req.type().isBlank()) {
            types.add(req.type());
        }

        if (req.questionIds() != null && !req.questionIds().isEmpty()) {
            // 错题本勾选后只练选中的题；顺序保持用户勾选的先后顺序
            List<Long> wanted = req.questionIds().stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .limit(200)
                    .toList();
            if (wanted.isEmpty()) {
                throw new BizException("没有选中的题");
            }
            String placeholders = String.join(",",
                    java.util.Collections.nCopies(wanted.size(), "?"));
            List<Object> selectedArgs = new ArrayList<>();
            selectedArgs.add(req.subjectId());
            selectedArgs.addAll(wanted);
            List<Long> available = jdbc.queryForList(
                    "select id from question where subject_id=? and status='ACTIVE'"
                            + " and type in ('SINGLE','MULTIPLE','JUDGE')"
                            + " and id in (" + placeholders + ")",
                    Long.class, selectedArgs.toArray());
            java.util.Set<Long> set = new java.util.HashSet<>(available);
            ids = wanted.stream().filter(set::contains).limit(count).toList();
        } else {
            StringBuilder sql = new StringBuilder(
                    "select id from question where subject_id=? and status='ACTIVE'"
                            + " and type in ('SINGLE','MULTIPLE','JUDGE')");
            List<Object> args = new ArrayList<>();
            args.add(req.subjectId());
            if (req.chapterId() != null) {
                sql.append(" and chapter_id=?");
                args.add(req.chapterId());
            }

            // 组卷器：题型多选 / 年份多选 / 只练错题 / 只练没做过
            if (!types.isEmpty()) {
                sql.append(" and type in (")
                        .append(String.join(",", java.util.Collections.nCopies(types.size(), "?")))
                        .append(")");
                args.addAll(types);
            }
            if (req.years() != null && !req.years().isEmpty()) {
                sql.append(" and year in (")
                        .append(String.join(",", java.util.Collections.nCopies(req.years().size(), "?")))
                        .append(")");
                args.addAll(req.years());
            }
            if (Boolean.TRUE.equals(req.onlyNew())) {
                sql.append(" and id not in (select question_id from answer_record)");
            }
            if (Boolean.TRUE.equals(req.onlyWrong())) {
                // 和错题本口径一致：最近一次作答是错的
                sql.append(" and (select a.is_correct from answer_record a")
                        .append(" where a.question_id=question.id order by a.id desc limit 1) = 0");
            }
            sql.append(Boolean.FALSE.equals(req.shuffle())
                    ? " order by coalesce(year,0) desc, coalesce(qno,9999), id"
                    : " order by random()");
            sql.append(" limit ?");
            args.add(count);
            ids = jdbc.queryForList(sql.toString(), Long.class, args.toArray());
        }
        if (ids.isEmpty()) {
            throw new BizException("这门课还没有可练的选择题，先导入题库，或去「知识点」里背主观题");
        }

        PracticeSession s = new PracticeSession();
        s.setUserId(1L);
        s.setSubjectId(req.subjectId());
        String mode = req.mode() == null ? "RANDOM" : req.mode();
        s.setMode(mode);
        int duration = 0;
        if ("EXAM".equals(mode)) {
            int m = req.durationMinutes() == null ? 150 : req.durationMinutes();
            duration = Math.max(5, Math.min(m, 300));
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("chapterId", req.chapterId() == null ? 0 : req.chapterId());
        cfg.put("type", req.type() == null ? "" : req.type());
        cfg.put("types", types);
        cfg.put("years", req.years() == null ? List.of() : req.years());
        cfg.put("onlyWrong", Boolean.TRUE.equals(req.onlyWrong()));
        cfg.put("onlyNew", Boolean.TRUE.equals(req.onlyNew()));
        cfg.put("count", count);
        cfg.put("duration", duration);
        s.setConfig(Json.write(cfg));
        s.setQuestionIds(Json.write(ids));
        s.setCursor(0);
        s.setStatus("DOING");
        s.setCorrectCount(0);
        s.setTotalCount(ids.size());
        s.setCostMs(0L);
        s.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(s);

        return buildView(s, subject);
    }

    /** 组卷器：这门课有哪些题型/年份可组、错题和没做过的数量 */
    public Dtos.BuildOptions options(Long subjectId) {
        if (subjectId == null) {
            throw new BizException("请选择学科");
        }
        List<Dtos.BuildType> types = jdbc.query("""
                select type, count(*) c from question
                 where subject_id=? and status='ACTIVE' and type in ('SINGLE','MULTIPLE','JUDGE')
                 group by type
                 order by case type when 'SINGLE' then 1 when 'MULTIPLE' then 2 else 3 end
                """, (rs, i) -> new Dtos.BuildType(rs.getString("type"), rs.getInt("c")), subjectId);

        List<Dtos.BuildYear> years = jdbc.query("""
                select year, count(*) c from question
                 where subject_id=? and status='ACTIVE' and type in ('SINGLE','MULTIPLE','JUDGE')
                   and year is not null
                 group by year order by year desc
                """, (rs, i) -> new Dtos.BuildYear(rs.getInt("year"), rs.getInt("c")), subjectId);

        int choiceCount = types.stream().mapToInt(Dtos.BuildType::count).sum();
        List<Dtos.BuildChapter> chapters = jdbc.query("""
                select c.id, c.name, count(q.id) as c,
                       sum(case when (select a.is_correct from answer_record a
                            where a.question_id=q.id order by a.id desc limit 1)=0 then 1 else 0 end) as wrong,
                       sum(case when q.id not in (select question_id from answer_record) then 1 else 0 end) as fresh
                  from chapter c join question q on q.chapter_id=c.id
                 where c.subject_id=? and q.subject_id=? and q.status='ACTIVE'
                   and q.type in ('SINGLE','MULTIPLE','JUDGE')
                 group by c.id, c.name order by c.depth, c.sort, c.id
                """, (rs, i) -> new Dtos.BuildChapter(rs.getLong("id"), rs.getString("name"),
                rs.getInt("c"), rs.getInt("wrong"), rs.getInt("fresh")), subjectId, subjectId);
        String base = " from question q where q.subject_id=? and q.status='ACTIVE'"
                + " and q.type in ('SINGLE','MULTIPLE','JUDGE')";
        Integer wrong = jdbc.queryForObject("select count(*) " + base
                + " and (select a.is_correct from answer_record a"
                + " where a.question_id=q.id order by a.id desc limit 1)=0", Integer.class, subjectId);
        Integer fresh = jdbc.queryForObject("select count(*) " + base
                + " and q.id not in (select question_id from answer_record)", Integer.class, subjectId);

        return new Dtos.BuildOptions(subjectId, choiceCount, types, years, chapters,
                wrong == null ? 0 : wrong, fresh == null ? 0 : fresh);
    }

    /** 今日复习：默认把当前专业所有学科到期的选择题拼成一套；传 subjectId 则只复习这门学科 */
    @Transactional
    public Dtos.SessionView startReview(int limit, Long majorId, Long subjectId) {
        int size = Math.max(1, Math.min(limit, 100));
        String now = LocalDateTime.now().toString();
        String base = """
                select q.id from review_state rs
                  join question q on q.id = rs.item_id
                  join subject s on s.id = q.subject_id
                 where rs.user_id = 1 and rs.item_type = 'QUESTION'
                   and q.status = 'ACTIVE' and q.type in ('SINGLE','MULTIPLE','JUDGE')
                   and rs.due_at <= ?
                """;
        StringBuilder sql = new StringBuilder(base);
        List<Object> args = new java.util.ArrayList<>();
        args.add(now);
        if (majorId != null) {
            sql.append(" and s.major_id = ?");
            args.add(majorId);
        }
        if (subjectId != null) {
            sql.append(" and q.subject_id = ?");
            args.add(subjectId);
        }
        sql.append(" order by rs.due_at asc limit ?");
        args.add(size);
        List<Long> ids = jdbc.queryForList(sql.toString(), Long.class, args.toArray());
        if (ids.isEmpty()) {
            throw new BizException("今天没有到期的题，先去练几道新的吧");
        }

        PracticeSession s = new PracticeSession();
        s.setUserId(1L);
        s.setSubjectId(subjectId == null ? 0L : subjectId);
        s.setMode("REVIEW");
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("count", ids.size());
        cfg.put("kind", "due");
        if (subjectId != null) {
            cfg.put("subjectId", subjectId);
        }
        s.setConfig(Json.write(cfg));
        s.setQuestionIds(Json.write(ids));
        s.setCursor(0);
        s.setStatus("DOING");
        s.setCorrectCount(0);
        s.setTotalCount(ids.size());
        s.setCostMs(0L);
        s.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return buildView(s, null);
    }

    public Dtos.SessionView get(Long id) {
        PracticeSession s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException("练习不存在");
        }
        Subject subject = subjectMapper.selectById(s.getSubjectId());
        return buildView(s, subject);
    }

    private Dtos.SessionView buildView(PracticeSession s, Subject subject) {
        List<Long> ids = questionIds(s);
        List<Dtos.QuestionView> questions = questionService.viewsByIds(ids);

        Map<Long, String> answered = new LinkedHashMap<>();
        List<AnswerRecord> records = answerMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>().eq(AnswerRecord::getSessionId, s.getId()));
        for (AnswerRecord r : records) {
            answered.put(r.getQuestionId(), r.getUserAnswer());
        }

        return new Dtos.SessionView(
                s.getId(), s.getSubjectId(), subjectName(s, subject),
                s.getMode(), s.getStatus(), ids.size(), answered.size(), answered, questions,
                s.getStartedAt() == null ? null : s.getStartedAt().toString(),
                configInt(s.getConfig(), "duration", 0));
    }

    private static List<Long> questionIds(PracticeSession s) {
        String raw = s.getQuestionIds();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Json.readLongList(raw);
    }

    private static int configInt(String json, String key, int def) {
        if (json == null || json.isBlank()) {
            return def;
        }
        try {
            Map<?, ?> m = Json.read(json, Map.class);
            Object v = m.get(key);
            return v instanceof Number n ? n.intValue() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String subjectName(PracticeSession s, Subject subject) {
        if (s.getSubjectId() != null && s.getSubjectId() == 0L) {
            return "综合复习";
        }
        return subject == null ? "" : subject.getName();
    }

    @Transactional
    public Dtos.AnswerResult answer(Long sessionId, Dtos.AnswerRequest req) {
        PracticeSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BizException("练习不存在");
        }
        if (!"DOING".equals(s.getStatus())) {
            throw new BizException("这次练习已经交卷了，不能继续作答");
        }
        if (req.questionId() == null) {
            throw new BizException("缺少题目");
        }
        // 题目必须属于当前练习，避免通过接口往任意 session 里塞题
        if (!questionIds(s).contains(req.questionId())) {
            throw new BizException("这道题不属于当前练习");
        }
        Question q = questionMapper.selectById(req.questionId());
        if (q == null) {
            throw new BizException("题目不存在");
        }
        if (!"ACTIVE".equals(q.getStatus()) || q.getAnswer() == null || q.getAnswer().isBlank()) {
            throw new BizException("这道题还没有有效答案，不能作答");
        }
        if (examExpired(s)) {
            throw new BizException("考试时间已到，请交卷");
        }

        String userAnswer = req.answer() == null ? "" : req.answer().strip();
        // 考试模式取消最后一个多选项时，前端会提交空答案；这里直接删除记录，保持“未作答”状态
        if ("EXAM".equals(s.getMode()) && userAnswer.isEmpty()) {
            jdbc.update("delete from answer_record where session_id=? and question_id=?",
                    sessionId, q.getId());
            refreshSessionCounts(s);
            long left = answerMapper.selectCount(new LambdaQueryWrapper<AnswerRecord>()
                    .eq(AnswerRecord::getSessionId, sessionId));
            return new Dtos.AnswerResult(false, null, null, 0, (int) left);
        }

        boolean correct = judge(q, userAnswer, req.selfRating());
        long costMs = clampCost(req.costMs());
        double score = q.getScore() == null ? 1.0 : q.getScore();

        // 唯一索引 (session_id, question_id) + upsert，重复提交只更新原记录，
        // 同时把每次作答的耗时累加起来。
        jdbc.update("""
                insert into answer_record
                  (session_id, user_id, question_id, user_answer, is_correct, score,
                   self_rating, cost_ms, answered_at)
                values (?,?,?,?,?,?,?,?,?)
                on conflict(session_id, question_id) do update set
                  user_answer = excluded.user_answer,
                  is_correct = excluded.is_correct,
                  score = excluded.score,
                  self_rating = excluded.self_rating,
                  cost_ms = coalesce(answer_record.cost_ms, 0) + coalesce(excluded.cost_ms, 0),
                  answered_at = excluded.answered_at
                """, sessionId, 1L, q.getId(), userAnswer, correct ? 1 : 0,
                correct ? score : 0.0, req.selfRating(), costMs, LocalDateTime.now());

        reviewService.apply(ReviewState.QUESTION, q.getId(), correct, false);
        refreshSessionCounts(s);

        long correctCount = answerMapper.selectCount(new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getSessionId, sessionId).eq(AnswerRecord::getIsCorrect, 1));
        long answeredCount = answerMapper.selectCount(new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getSessionId, sessionId));

        // 考试模式不把对错、正确答案和解析返回给客户端，交卷后统一看结果。
        if ("EXAM".equals(s.getMode())) {
            return new Dtos.AnswerResult(false, null, null, 0, (int) answeredCount);
        }
        return new Dtos.AnswerResult(correct, q.getAnswer(), q.getAnalysis(),
                (int) correctCount, (int) answeredCount);
    }

    /** 交卷；重复调用不会改变已经记录的交卷时间和用时。 */
    @Transactional
    public Dtos.ResultView finish(Long sessionId) {
        PracticeSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BizException("练习不存在");
        }
        if (!"DONE".equals(s.getStatus())) {
            s.setStatus("DONE");
            s.setFinishedAt(LocalDateTime.now());
            if (s.getStartedAt() != null) {
                s.setCostMs(Math.max(0L,
                        Duration.between(s.getStartedAt(), s.getFinishedAt()).toMillis()));
            }
            refreshSessionCounts(s);
            sessionMapper.updateById(s);
        }
        return buildResult(s);
    }

    /** 结果查询只读，不修改练习状态和交卷时间。 */
    public Dtos.ResultView result(Long sessionId) {
        PracticeSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BizException("练习不存在");
        }
        return buildResult(s);
    }

    private Dtos.ResultView buildResult(PracticeSession s) {
        Subject subject = subjectMapper.selectById(s.getSubjectId());
        List<Long> ids = questionIds(s);
        List<Question> questions = questionService.byIds(ids);
        Map<Long, AnswerRecord> records = new HashMap<>();
        for (AnswerRecord r : answerMapper.selectList(
                new LambdaQueryWrapper<AnswerRecord>().eq(AnswerRecord::getSessionId, s.getId()))) {
            records.put(r.getQuestionId(), r);
        }

        List<Dtos.QuestionResult> items = new ArrayList<>();
        int correct = 0;
        int answered = 0;
        double earnedScore = 0;
        double totalScore = 0;
        for (Question q : questions) {
            AnswerRecord r = records.get(q.getId());
            boolean isAnswered = r != null;
            boolean ok = isAnswered && Integer.valueOf(1).equals(r.getIsCorrect());
            double questionScore = q.getScore() == null ? 1.0 : q.getScore();
            totalScore += questionScore;
            if (isAnswered) {
                answered++;
            }
            if (ok) {
                correct++;
                earnedScore += questionScore;
            }
            items.add(new Dtos.QuestionResult(q.getId(), q.getStem(), q.getType(), q.getAnswer(),
                    isAnswered ? r.getUserAnswer() : null, ok, isAnswered, q.getAnalysis()));
        }

        int total = questions.size();
        int wrong = Math.max(0, answered - correct);
        int unanswered = Math.max(0, total - answered);
        double accuracy = total == 0 ? 0 : Math.round(correct * 1000.0 / total) / 10.0;
        long cost = s.getCostMs() == null ? 0 : s.getCostMs();
        // 未交卷时直接看结果：按时长展示，但不写回数据库。
        if (cost <= 0 && s.getStartedAt() != null) {
            try {
                LocalDateTime end = s.getFinishedAt() == null ? LocalDateTime.now() : s.getFinishedAt();
                cost = Math.max(0L, Duration.between(s.getStartedAt(), end).toMillis());
            } catch (Exception ignored) {
                // 旧数据的 started_at 格式异常时忽略
            }
        }

        return new Dtos.ResultView(s.getId(), s.getSubjectId(), subjectName(s, subject),
                total, correct, wrong, unanswered, accuracy,
                round2(earnedScore), round2(totalScore), cost, items, s.getMode(),
                configInt(s.getConfig(), "duration", 0));
    }

    private static long clampCost(Long ms) {
        if (ms == null || ms <= 0) {
            return 0L;
        }
        // 单题耗时上限 6 小时，防止前端异常值把统计撑爆
        return Math.min(ms, 6L * 60 * 60 * 1000);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean examExpired(PracticeSession s) {
        if (!"EXAM".equals(s.getMode()) || s.getStartedAt() == null) {
            return false;
        }
        int minutes = configInt(s.getConfig(), "duration", 0);
        if (minutes <= 0) {
            return false;
        }
        try {
            LocalDateTime end = s.getStartedAt().plusMinutes(minutes);
            return LocalDateTime.now().isAfter(end);
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshSessionCounts(PracticeSession s) {
        long correct = answerMapper.selectCount(new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getSessionId, s.getId()).eq(AnswerRecord::getIsCorrect, 1));
        long answered = answerMapper.selectCount(new LambdaQueryWrapper<AnswerRecord>()
                .eq(AnswerRecord::getSessionId, s.getId()));
        s.setCorrectCount((int) correct);
        s.setCursor((int) answered);
        sessionMapper.updateById(s);
    }

    private boolean judge(Question q, String userAnswer, String selfRating) {
        String type = q.getType();
        if (isSubjective(type)) {
            return "MASTER".equals(selfRating);
        }
        if ("FILL".equals(type)) {
            String ua = normalizeText(userAnswer);
            for (String alt : q.getAnswer().split("\\|")) {
                if (normalizeText(alt).equals(ua)) {
                    return true;
                }
            }
            return false;
        }
        return normalizeKey(userAnswer, type).equals(normalizeKey(q.getAnswer(), type));
    }

    private boolean isSubjective(String type) {
        return "TERM".equals(type) || "SHORT".equals(type) || "CALC".equals(type) || "ESSAY".equals(type);
    }

    private String normalizeText(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s\\p{Punct}，。、；：？！“”‘’（）《》【】…—·]", "")
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String s, String type) {
        if (s == null) {
            return "";
        }
        String v = s.strip().toUpperCase(Locale.ROOT);
        if ("JUDGE".equals(type)) {
            if (v.matches("^(对|正确|√|T|TRUE|是)$")) return "对";
            if (v.matches("^(错|错误|×|F|FALSE|否)$")) return "错";
            return v;
        }
        String letters = v.replaceAll("[^A-F]", "");
        if ("MULTIPLE".equals(type)) {
            char[] cs = letters.toCharArray();
            java.util.Arrays.sort(cs);
            return new String(cs);
        }
        return letters;
    }
}
