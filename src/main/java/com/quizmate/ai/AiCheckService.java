package com.quizmate.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizmate.common.BizException;
import com.quizmate.common.CurrentUser;
import com.quizmate.common.Json;
import com.quizmate.domain.Question;
import com.quizmate.dto.Dtos;
import com.quizmate.mapper.QuestionMapper;
import com.quizmate.service.QuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用 AI 独立做一遍题，和题库里的答案比对。只产出「建议」，不直接改答案。
 */
@Slf4j
@Service
public class AiCheckService {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern LETTERS = Pattern.compile("([A-D]{1,4})");

    private final JdbcTemplate jdbc;
    private final QuestionMapper questionMapper;
    private final QuestionService questionService;
    private final AiClient ai;
    private final AiConfigStore config;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-check");
        t.setDaemon(true);
        return t;
    });

    /** 每个账号独立进度，不同账号可以同时跑 AI 校验。 */
    private final Map<Long, CheckState> states = new java.util.concurrent.ConcurrentHashMap<>();

    public AiCheckService(JdbcTemplate jdbc, QuestionMapper questionMapper,
                          QuestionService questionService, AiClient ai, AiConfigStore config) {
        this.jdbc = jdbc;
        this.questionMapper = questionMapper;
        this.questionService = questionService;
        this.ai = ai;
        this.config = config;
    }

    public synchronized Dtos.AiCheckStartResult start(Dtos.AiCheckStartRequest req) {
        long userId = CurrentUser.id();
        CheckState existing = states.get(userId);
        if (existing != null && existing.running) {
            throw new BizException("当前账号正在校验中，等这次跑完再开始");
        }
        if (req.subjectId() == null) {
            throw new BizException("请选择学科");
        }
        if (!config.get().ready()) {
            throw new BizException("还没有配置 AI 接口，先在下面填 API Key（本地 Ollama 不用填）");
        }        int limit = Math.max(1, Math.min(req.limit() == null ? 20 : req.limit(), 200));

        StringBuilder sql = new StringBuilder("""
                select q.id from question q
                  left join ai_check c on c.question_id = q.id
                 where q.subject_id=? and q.status in ('ACTIVE','NO_ANSWER')
                   and q.type in ('SINGLE','MULTIPLE','JUDGE')
                """);
        List<Object> args = new ArrayList<>();
        args.add(req.subjectId());
        if (Boolean.TRUE.equals(req.onlyUnchecked())) {
            sql.append(" and c.question_id is null");
        }
        // 失败过的先重试；没校验过的其次；都校验过之后按上次校验时间从早到晚轮着来
        sql.append(" order by case when c.verdict='ERROR' then 0 when c.question_id is null then 1 else 2 end,")
                .append(" c.created_at asc, q.id limit ?");
        args.add(limit);
        List<Long> ids = jdbc.queryForList(sql.toString(), Long.class, args.toArray());
        if (ids.isEmpty()) {
            throw new BizException("没有可校验的题（这个学科没有选择题/判断题）");
        }

        CheckState state = new CheckState(ids.size());
        states.put(userId, state);
        CurrentUser.Principal user = CurrentUser.get();
        pool.submit(() -> run(ids, user, state));
        return new Dtos.AiCheckStartResult(true, ids.size(), "已开始校验 " + ids.size() + " 题");
    }

    private void run(List<Long> ids, CurrentUser.Principal user, CheckState state) {
        CurrentUser.set(user);
        int match = 0;
        int diff = 0;
        int error = 0;
        String firstError = null;
        try {
            for (Long id : ids) {
                Question q = questionMapper.selectById(id);
                if (q == null) {
                    state.done++;
                    continue;
                }
                state.message = "第 " + (state.done + 1) + "/" + state.total + " 题";
                try {
                    Reply reply = ask(q);
                    String verdict = compare(q, reply.answer()) ? "MATCH" : "DIFF";
                    upsert(id, reply.answer(), verdict, reply.reason());
                    if ("MATCH".equals(verdict)) {
                        match++;
                    } else {
                        diff++;
                    }
                } catch (Exception e) {
                    error++;
                    String reason = trim(e.getMessage(), 180);
                    if (firstError == null) {
                        firstError = trim(reason, 100);
                    }
                    upsert(id, null, "ERROR", reason);
                }
                state.done++;
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            state.message = "完成：一致 " + match + "，不一致 " + diff + (error > 0 ? "，失败 " + error : "");
            if (firstError != null) {
                state.message += "（原因：" + firstError + "，再跑一批会优先重试失败的题）";
            }
        } finally {
            state.running = false;
            CurrentUser.clear();
            log.info("AI 校验结束：match={} diff={} error={}", match, diff, error);
        }
    }

    private static final class CheckState {
        volatile boolean running = true;
        volatile int done;
        final int total;
        volatile String message = "准备中…";

        CheckState(int total) {
            this.total = total;
        }
    }

    private record Reply(String answer, String reason) {
    }

    private Reply ask(Question q) {
        StringBuilder user = new StringBuilder();
        user.append("请独立作答下面的").append(typeName(q.getType())).append("。\n")
                .append("只输出一个 JSON 对象，不要 Markdown、不要解释：\n")
                .append("{\"answer\":\"...\",\"reason\":\"不超过30字\"}\n")
                .append("规则：单选给一个字母 A-D；多选把字母排序后连写（如 ABD）；判断题答“对”或“错”。\n\n")
                .append("题干：").append(q.getStem()).append("\n");
        List<Dtos.Option> options = parseOptions(q.getOptions());
        if (!options.isEmpty()) {
            user.append("选项：\n");
            for (Dtos.Option o : options) {
                user.append(o.key()).append(". ").append(o.text()).append("\n");
            }
        }

        String content = ai.chat("你是一名严谨的自考命题老师，只输出 JSON。", user.toString());
        String json = extractJson(content);
        String answer = "";
        String reason = "";
        if (json != null) {
            try {
                JsonNode n = mapper.readTree(json);
                answer = n.path("answer").asText("");
                reason = n.path("reason").asText("");
            } catch (Exception ignored) {
            }
        }
        if (answer.isBlank()) {
            answer = fallbackAnswer(q.getType(), content);
        }
        String normalized = norm(q.getType(), answer);
        if (normalized.isEmpty()) {
            throw new BizException("AI 返回的答案无法识别：" + trim(content, 80));
        }
        return new Reply(normalized, trim(reason, 120));
    }

    private static String extractJson(String content) {
        if (content == null) {
            return null;
        }
        Matcher m = JSON_BLOCK.matcher(content);
        return m.find() ? m.group() : null;
    }

    private static String fallbackAnswer(String type, String content) {
        if (content == null) {
            return "";
        }
        if ("JUDGE".equals(type)) {
            if (content.contains("对") || content.contains("正确") || content.contains("√")) {
                return "对";
            }
            if (content.contains("错") || content.contains("错误") || content.contains("×")) {
                return "错";
            }
        }
        Matcher m = LETTERS.matcher(content.toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1) : "";
    }

    private static String norm(String type, String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if ("JUDGE".equals(type)) {
            if (s.matches("(?i).*(对|正确|true|t|√).*")) {
                return "对";
            }
            if (s.matches("(?i).*(错|错误|false|f|×).*")) {
                return "错";
            }
            return "";
        }
        String letters = s.toUpperCase(Locale.ROOT).replaceAll("[^A-D]", "");
        if (letters.isEmpty()) {
            return "";
        }
        Set<Character> uniq = letters.chars().mapToObj(c -> (char) c)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return uniq.stream().sorted(Comparator.naturalOrder())
                .map(String::valueOf).collect(Collectors.joining());
    }

    private static boolean compare(Question q, String aiAnswer) {
        String a = norm(q.getType(), q.getAnswer());
        String b = norm(q.getType(), aiAnswer);
        return !a.isEmpty() && a.equals(b);
    }

    private static List<Dtos.Option> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Json.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<Dtos.Option>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private void upsert(Long questionId, String aiAnswer, String verdict, String reason) {
        jdbc.update("""
                insert into ai_check(question_id, ai_answer, verdict, reason, model, status, created_at)
                values (?,?,?,?,?, 'NEW', ?)
                on conflict(question_id) do update set
                  ai_answer=excluded.ai_answer, verdict=excluded.verdict, reason=excluded.reason,
                  model=excluded.model, status='NEW', created_at=excluded.created_at
                """, questionId, aiAnswer, verdict, reason,
                config.get().model(), LocalDateTime.now().toString());
    }

    public Dtos.AiProgress progress() {
        CheckState state = states.get(CurrentUser.id());
        if (state == null) {
            return new Dtos.AiProgress(false, 0, 0, "");
        }
        return new Dtos.AiProgress(state.running, state.done, state.total, state.message);
    }

    /** 补答案时用：AI 独立作答，判断用户选的答案对不对 */
    public Dtos.AiCheckOneResult checkOne(Long questionId, String answer) {
        if (questionId == null) {
            return new Dtos.AiCheckOneResult(false, false, null, null, "缺少题目");
        }
        if (!config.get().ready()) {
            return new Dtos.AiCheckOneResult(false, false, null, null, "未配置 AI");
        }
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            return new Dtos.AiCheckOneResult(false, false, null, null, "题目不存在");
        }
        if (!List.of("SINGLE", "MULTIPLE", "JUDGE").contains(q.getType())) {
            return new Dtos.AiCheckOneResult(false, false, null, null, "只有选择题能校验");
        }
        try {
            String cacheKey = CurrentUser.id() + ":" + questionId;
            Cached cached = cache.get(cacheKey);
            long now = System.currentTimeMillis();
            if (cached == null || now - cached.at() > 30 * 60 * 1000L) {
                cached = new Cached(ask(q), now);
                if (cache.size() > 800) {
                    cache.clear();
                }
                cache.put(cacheKey, cached);
            }
            String mine = norm(q.getType(), answer);
            boolean correct = !mine.isEmpty() && mine.equals(cached.reply().answer());
            return new Dtos.AiCheckOneResult(true, correct, cached.reply().answer(),
                    cached.reply().reason(), null);
        } catch (Exception e) {
            return new Dtos.AiCheckOneResult(false, false, null, null, trim(e.getMessage(), 120));
        }
    }

    private record Cached(Reply reply, long at) {
    }

    private final java.util.Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 题库里为没有解析的题生成解析；已有解析直接返回，不会重复调用 AI */
    @Transactional
    public Dtos.AiAnalysisResult analysis(Long questionId) {
        if (questionId == null) {
            throw new BizException("缺少题目");
        }
        if (!config.get().ready()) {
            throw new BizException("还没有配置 AI 接口，先去设置页填 API Key");
        }
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        if (q.getAnalysis() != null && !q.getAnalysis().isBlank()) {
            return new Dtos.AiAnalysisResult(q.getAnalysis(), false, "这题已经有解析了，没有调用 AI");
        }
        if (q.getAnswer() == null || q.getAnswer().isBlank()) {
            throw new BizException("这题还没有答案，先补答案再生成解析");
        }

        StringBuilder user = new StringBuilder();
        user.append("请为下面的").append(typeName(q.getType())).append("写一段解析。\n")
                .append("要求：中文、200 字以内、说明正确答案为什么对（必要时点出其他选项的问题），")
                .append("不要 Markdown、不要重复题干。\n\n")
                .append("题干：").append(q.getStem()).append("\n");
        List<Dtos.Option> options = parseOptions(q.getOptions());
        if (!options.isEmpty()) {
            user.append("选项：\n");
            for (Dtos.Option o : options) {
                user.append(o.key()).append(". ").append(o.text()).append("\n");
            }
        }
        user.append("正确答案：").append(q.getAnswer()).append("\n");

        String text = ai.chat("你是一名自考辅导老师，解析要准确、简洁。", user.toString());
        String cleaned = com.quizmate.common.Texts.normalize(text);
        if (cleaned == null || cleaned.isBlank()) {
            throw new BizException("AI 没有返回解析内容");
        }
        q.setAnalysis(cleaned);
        q.setUpdatedAt(java.time.LocalDateTime.now());
        questionMapper.updateById(q);
        return new Dtos.AiAnalysisResult(cleaned, true, "解析已生成并保存");
    }

    /** 各学科可校验题数，以及其中还没 AI 校验过的数量 */
    public List<Dtos.AiSubjectStat> stats(Long majorId) {
        String majorWhere = majorId == null ? ""
                : " and q.subject_id in (select id from subject where major_id = " + majorId + ")";
        return jdbc.query("""
                select q.subject_id as sid,
                       count(*) as total,
                       sum(case when c.question_id is null or c.verdict='ERROR' then 1 else 0 end) as unchecked
                  from question q
                  left join ai_check c on c.question_id = q.id
                 where q.status in ('ACTIVE','NO_ANSWER')
                   and q.type in ('SINGLE','MULTIPLE','JUDGE')""" + majorWhere + """
                 group by q.subject_id
                """, (rs, i) -> new Dtos.AiSubjectStat(
                rs.getLong("sid"), rs.getInt("total"), rs.getInt("unchecked")));
    }

    public List<Dtos.AiCheckView> list(Long subjectId, String status, String verdict, int limit) {
        StringBuilder sql = new StringBuilder("""
                select c.question_id, c.ai_answer, c.verdict, c.reason, c.model, c.status, c.created_at,
                       q.stem, q.type, q.answer
                  from ai_check c join question q on q.id = c.question_id
                 where 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            sql.append(" and q.subject_id=?");
            args.add(subjectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and c.status=?");
            args.add(status);
        }
        if (verdict != null && !verdict.isBlank()) {
            sql.append(" and c.verdict=?");
            args.add(verdict);
        }
        sql.append("""
                 order by case c.verdict when 'DIFF' then 0 when 'ERROR' then 1 else 2 end,
                          c.id desc limit ?
                """);
        args.add(Math.max(1, Math.min(limit, 300)));
        return jdbc.query(sql.toString(), (rs, i) -> new Dtos.AiCheckView(
                rs.getLong("question_id"), rs.getString("stem"), rs.getString("type"),
                rs.getString("answer"), rs.getString("ai_answer"), rs.getString("verdict"),
                rs.getString("reason"), rs.getString("model"), rs.getString("status"),
                rs.getString("created_at")), args.toArray());
    }

    @Transactional
    public Dtos.QuestionView apply(Long questionId) {
        List<String> rows = jdbc.query("select ai_answer from ai_check where question_id=?",
                (rs, i) -> rs.getString(1), questionId);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            throw new BizException("AI 没有给出可用的答案");
        }
        Dtos.QuestionView updated = questionService.update(questionId,
                new Dtos.QuestionUpdate(rows.get(0), null));
        jdbc.update("update ai_check set status='APPLIED' where question_id=?", questionId);
        return updated;
    }

    public void ignore(Long questionId) {
        jdbc.update("update ai_check set status='IGNORED' where question_id=?", questionId);
    }

    /** 一键采纳「不一致」的 AI 答案；传 questionIds 时只处理当前列表里这批题 */
    public Dtos.AiApplyAllResult applyAll(Long subjectId, List<Long> questionIds) {
        if (questionIds != null && questionIds.isEmpty()) {
            return new Dtos.AiApplyAllResult(0, 0, "当前列表没有可采纳的答案");
        }
        StringBuilder sql = new StringBuilder("""
                select c.question_id, c.ai_answer
                  from ai_check c join question q on q.id = c.question_id
                 where c.status='NEW' and c.verdict='DIFF'
                   and c.ai_answer is not null and c.ai_answer <> ''
                """);
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            sql.append(" and q.subject_id=?");
            args.add(subjectId);
        }
        if (questionIds != null) {
            sql.append(" and c.question_id in (")
                    .append(String.join(",", java.util.Collections.nCopies(questionIds.size(), "?")))
                    .append(")");
            args.addAll(questionIds);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        int applied = 0;
        int failed = 0;
        for (Map<String, Object> r : rows) {
            Long qid = ((Number) r.get("question_id")).longValue();
            String answer = String.valueOf(r.get("ai_answer"));
            try {
                questionService.update(qid, new Dtos.QuestionUpdate(answer, null));
                jdbc.update("update ai_check set status='APPLIED' where question_id=?", qid);
                applied++;
            } catch (Exception e) {
                log.warn("采纳失败 questionId={} answer={}: {}", qid, answer, e.getMessage());
                failed++;
            }
        }
        return new Dtos.AiApplyAllResult(applied, failed,
                "已采纳 " + applied + " 条" + (failed > 0 ? "，失败 " + failed + " 条" : ""));
    }

    private static String typeName(String type) {
        return switch (type) {
            case "SINGLE" -> "单选题";
            case "MULTIPLE" -> "多选题";
            case "JUDGE" -> "判断题";
            default -> "选择题";
        };
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
