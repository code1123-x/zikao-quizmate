package com.quizmate.service;

import com.quizmate.common.BizException;
import com.quizmate.common.Json;
import com.quizmate.domain.Question;
import com.quizmate.dto.Dtos;
import com.quizmate.mapper.QuestionMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class QuestionService {

    /** 22A / 22.A / 22、A / 22 A */
    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,3})\\s*[.、．:：\\-]?\\s*(\\S{1,6})$");

    private final QuestionMapper questionMapper;
    private final JdbcTemplate jdbc;

    public QuestionService(QuestionMapper questionMapper, JdbcTemplate jdbc) {
        this.questionMapper = questionMapper;
        this.jdbc = jdbc;
    }

    /** 待补答案的选择题 */
    public List<Dtos.QuestionView> pending(Long subjectId, String source, int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        List<Long> ids = source == null || source.isBlank()
                ? jdbc.queryForList(
                        "select id from question where status='NO_ANSWER' and subject_id=? order by coalesce(qno,9999), id limit ?",
                        Long.class, subjectId, size)
                : jdbc.queryForList(
                        "select id from question where status='NO_ANSWER' and subject_id=? and source=? order by coalesce(qno,9999), id limit ?",
                        Long.class, subjectId, source, size);
        return viewsByIds(ids);
    }

    /** 待补答案的题按试卷（source）分组，方便按整套卷子补 */
    public List<Dtos.PendingSource> pendingSources(Long subjectId) {
        return jdbc.query("""
                select source, year, count(*) as c
                  from question
                 where status='NO_ANSWER' and subject_id=?
                 group by source, year
                 order by coalesce(year, 0) desc, source desc
                """, (rs, i) -> new Dtos.PendingSource(
                rs.getString("source"), (Integer) rs.getObject("year"), rs.getInt("c")), subjectId);
    }

    public int pendingCount(Long subjectId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from question where status='NO_ANSWER' and subject_id=?", Integer.class, subjectId);
        return n == null ? 0 : n;
    }

    /** 手工补答案 */
    public void setAnswer(Long id, String rawAnswer) {
        Question q = questionMapper.selectById(id);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        if (!"NO_ANSWER".equals(q.getStatus())) {
            throw new BizException("这道题已经有答案了");
        }
        String answer = validate(q, rawAnswer);
        q.setAnswer(answer);
        q.setStatus("ACTIVE");
        q.setUpdatedAt(LocalDateTime.now());
        questionMapper.updateById(q);
    }

    /**
     * 批量补答案，支持两种写法：
     * 1. 带题号：22A 23B 24C（推荐，不怕这套卷子前面几题已经有答案）
     * 2. 纯顺序：A B C AB D（按待补题目的先后顺序）
     */
    @Transactional
    public Dtos.BatchAnswerResult batchAnswer(Long subjectId, String source, String answers, boolean fromOne) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, qno from question
                 where status='NO_ANSWER' and subject_id=? and source=?
                 order by coalesce(qno, 9999), id
                """, subjectId, source);
        if (rows.isEmpty()) {
            throw new BizException("这套卷子没有待补答案的题了");
        }

        List<Long> ids = new ArrayList<>();
        Map<Long, Integer> idToQno = new LinkedHashMap<>();
        Map<Integer, Long> qnoToId = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            ids.add(id);
            Object qno = r.get("qno");
            if (qno != null) {
                int n = ((Number) qno).intValue();
                idToQno.put(id, n);
                qnoToId.putIfAbsent(n, id);
            }
        }

        String[] raw = answers == null ? new String[0] : answers.strip().split("[\\s,，、;；|]+");
        List<String> tokens = new ArrayList<>();
        for (String t : raw) {
            if (!t.isBlank()) {
                tokens.add(t.strip());
            }
        }
        if (tokens.isEmpty()) {
            throw new BizException("没有识别到答案");
        }

        // 解析：22A / 22.A / 22、A / 22 A
        Map<Integer, String> byNo = new LinkedHashMap<>();
        List<String> plain = new ArrayList<>();
        for (String t : tokens) {
            Matcher m = NUMBERED.matcher(t);
            if (m.matches()) {
                byNo.put(Integer.parseInt(m.group(1)), m.group(2));
            } else {
                plain.add(t);
            }
        }

        boolean numbered = !byNo.isEmpty();
        if (numbered && !plain.isEmpty()) {
            throw new BizException("不要混着写：要么全部带题号（22A 23B），要么全部按顺序（A B C）");
        }
        if (numbered && qnoToId.isEmpty()) {
            throw new BizException("这套卷子还没有题号信息，请改用「按顺序」填写");
        }

        int filled = 0;
        int skipped = 0;
        int ignored = 0;
        StringBuilder errors = new StringBuilder();
        LocalDateTime now = LocalDateTime.now();

        if (numbered) {
            for (Map.Entry<Integer, String> e : byNo.entrySet()) {
                Long id = qnoToId.get(e.getKey());
                if (id == null) {
                    // 这个题号本来就不在待补列表里（已经有答案 / 主观题），不算错
                    ignored++;
                    continue;
                }
                if (apply(id, e.getValue(), now)) {
                    filled++;
                } else {
                    skipped++;
                    if (errors.length() < 300) {
                        errors.append(errors.isEmpty() ? "" : "；")
                                .append("第 ").append(e.getKey()).append(" 题答案无效");
                    }
                }
            }
        } else if (fromOne) {
            // 整卷顺序：第 1 个答案就是第 1 题，和这题是否已经有答案无关
            for (int i = 0; i < plain.size(); i++) {
                Long id = qnoToId.get(i + 1);
                if (id == null) {
                    ignored++;
                    continue;
                }
                if (apply(id, plain.get(i), now)) {
                    filled++;
                } else {
                    skipped++;
                    if (errors.length() < 300) {
                        errors.append(errors.isEmpty() ? "" : "；")
                                .append("第 ").append(i + 1).append(" 题答案无效");
                    }
                }
            }
        } else {
            int n = Math.min(ids.size(), plain.size());
            for (int i = 0; i < n; i++) {
                if (apply(ids.get(i), plain.get(i), now)) {
                    filled++;
                } else {
                    skipped++;
                    Integer qno = idToQno.get(ids.get(i));
                    if (errors.length() < 300) {
                        errors.append(errors.isEmpty() ? "" : "；")
                                .append("第 ").append(qno == null ? (i + 1) : qno)
                                .append(" 题答案无效");
                    }
                }
            }
        }

        String msg = "填入 " + filled + " 题";
        if (skipped > 0) {
            msg += "，无效 " + skipped + " 个";
        }
        if (ignored > 0) {
            msg += "，忽略 " + ignored + " 个（该题号已有答案或不是选择题）";
        }
        if (!numbered && !fromOne && plain.size() < ids.size()) {
            msg += "（还有 " + (ids.size() - plain.size()) + " 题没填）";
        }
        if (!errors.isEmpty()) {
            msg += "；" + errors;
        }
        return new Dtos.BatchAnswerResult(filled, skipped, msg);
    }

    private boolean apply(Long id, String rawAnswer, LocalDateTime now) {
        Question q = questionMapper.selectById(id);
        if (q == null || !"NO_ANSWER".equals(q.getStatus())) {
            return false;
        }
        try {
            String answer = validate(q, rawAnswer);
            q.setAnswer(answer);
            q.setStatus("ACTIVE");
            q.setUpdatedAt(now);
            questionMapper.updateById(q);
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    /** 校验并规范化答案（单选 A、多选 ABD、判断 对/错） */
    private String validate(Question q, String rawAnswer) {
        String type = q.getType();
        String answer = rawAnswer == null ? "" : rawAnswer.strip();
        if ("JUDGE".equals(type)) {
            String a = answer.toUpperCase(Locale.ROOT);
            if (a.equals("T") || a.equals("Y") || a.equals("TRUE") || a.equals("√")
                    || a.equals("正确") || a.equals("1") || a.equals("A")) {
                answer = "对";
            } else if (a.equals("F") || a.equals("N") || a.equals("FALSE") || a.equals("×")
                    || a.equals("X") || a.equals("错误") || a.equals("0") || a.equals("B")) {
                answer = "错";
            }
            if (!answer.equals("对") && !answer.equals("错")) {
                throw new BizException("判断题答案只能是 对 / 错");
            }
            return answer;
        }

        answer = answer.toUpperCase(Locale.ROOT).replaceAll("[^A-F]", "");
        if ("SINGLE".equals(type)) {
            if (!answer.matches("[A-F]")) {
                throw new BizException("单选题答案只能是 A-F 中的一个字母");
            }
        } else if ("MULTIPLE".equals(type)) {
            if (!answer.matches("[A-F]{2,6}")) {
                throw new BizException("多选题答案至少两个字母，如 ABD");
            }
            char[] cs = answer.toCharArray();
            java.util.Arrays.sort(cs);
            answer = new String(cs);
        } else {
            throw new BizException("只有选择题支持补答案");
        }

        if (q.getOptions() != null && !q.getOptions().isBlank()) {
            Dtos.Option[] opts = Json.read(q.getOptions(), Dtos.Option[].class);
            Set<String> keys = new HashSet<>();
            for (Dtos.Option o : opts) {
                keys.add(o.key());
            }
            for (char c : answer.toCharArray()) {
                if (!keys.contains(String.valueOf(c))) {
                    throw new BizException("答案 " + c + " 不在选项里");
                }
            }
        }
        return answer;
    }

    public Dtos.QuestionView toView(Question q) {
        List<Dtos.Option> options = new ArrayList<>();
        if (q.getOptions() != null && !q.getOptions().isBlank()) {
            Dtos.Option[] arr = Json.read(q.getOptions(), Dtos.Option[].class);
            options = List.of(arr);
        }
        List<String> tags = new ArrayList<>();
        if (q.getTags() != null && !q.getTags().isBlank()) {
            tags = Json.read(q.getTags(), List.class);
        }
        return new Dtos.QuestionView(
                q.getId(), q.getType(), q.getStem(), options, q.getAnswer(), q.getAnalysis(),
                q.getScore() == null ? 1 : q.getScore(), q.getSource(), q.getYear(), tags,
                q.getChapterId(), q.getQno(), q.getStatus());
    }

    /** 题库管理：按条件分页搜索 */
    public Dtos.QuestionPage page(Long subjectId, String type, String status, String keyword,
                                 Integer year, String source, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, 100));
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            where.append(" and subject_id=?");
            args.add(subjectId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" and type=?");
            args.add(type);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status=?");
            args.add(status);
        }
        if (year != null) {
            where.append(" and year=?");
            args.add(year);
        }
        if (source != null && !source.isBlank()) {
            where.append(" and source=?");
            args.add(source);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (stem like ? or answer like ? or source like ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }

        Integer total = jdbc.queryForObject("select count(*) from question" + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Long> ids = jdbc.queryForList(
                "select id from question" + where + " order by id desc limit ? offset ?",
                Long.class, pageArgs.toArray());
        return new Dtos.QuestionPage(viewsByIds(ids), total == null ? 0 : total, p, s);
    }

    /** 题库管理：改答案 / 解析（选择题会按题型校验） */
    @Transactional
    public Dtos.QuestionView update(Long id, Dtos.QuestionUpdate req) {
        Question q = questionMapper.selectById(id);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        if (req.answer() != null) {
            String raw = req.answer().strip();
            if (raw.isEmpty()) {
                q.setAnswer("");
                q.setStatus("NO_ANSWER");
            } else if ("SINGLE".equals(q.getType()) || "MULTIPLE".equals(q.getType())
                    || "JUDGE".equals(q.getType())) {
                q.setAnswer(validate(q, raw));
                q.setStatus("ACTIVE");
            } else {
                q.setAnswer(raw);
                q.setStatus("ACTIVE");
            }
        }
        if (req.analysis() != null) {
            q.setAnalysis(req.analysis().strip());
        }
        q.setUpdatedAt(LocalDateTime.now());
        questionMapper.updateById(q);
        return toView(q);
    }

    /** 题库管理：删题（连同作答记录和复习状态） */
    @Transactional
    public void delete(Long id) {
        jdbc.update("delete from ai_check where question_id=?", id);
        jdbc.update("delete from answer_record where question_id=?", id);
        // 客观题用 QUESTION，主观题卡片用 KNOWLEDGE，两种都要清
        jdbc.update("delete from review_state where item_id=? and item_type in ('QUESTION','KNOWLEDGE')", id);
        questionMapper.deleteById(id);
    }

    @Transactional
    public int deleteMany(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Long id : ids.subList(0, Math.min(ids.size(), 200))) {
            if (id == null) {
                continue;
            }
            delete(id);
            n++;
        }
        return n;
    }

    public List<Dtos.QuestionView> viewsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Question> questions = questionMapper.selectBatchIds(ids);
        Map<Long, Question> byId = questions.stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));
        List<Dtos.QuestionView> out = new ArrayList<>();
        for (Long id : ids) {
            Question q = byId.get(id);
            if (q != null) {
                out.add(toView(q));
            }
        }
        return out;
    }

    public List<Question> byIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Question> list = questionMapper.selectBatchIds(ids);
        list.sort(Comparator.comparingInt(q -> ids.indexOf(q.getId())));
        return list;
    }
}
