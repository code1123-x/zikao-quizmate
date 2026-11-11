package com.quizmate.importer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一份试卷的纯文本切成结构化题目。
 * 目标是"尽量准 + 可解释"，不追求 100%，剩下的交给校对工作台。
 */
@Component
public class PaperParser {

    public record ParseResult(List<ParsedQuestion> questions, List<String> warnings) {
    }

    private static final Pattern SECTION = Pattern.compile(
            "^\\s*[一二三四五六七八九十]+\\s*[、.．]?\\s*(单项选择|单选|多项选择|多选|判断|填空|名词解释|简答|计算|论述|分析|综合)");

    /** 没有题型关键词的分节标题，例如「一、本大题共25小题，每小题2分……」 */
    private static final Pattern SECTION_ANY = Pattern.compile(
            "^\\s*[一二三四五六七八九十]+\\s*[、.．]\\s*(.+)$");

    private static final Pattern Q_START = Pattern.compile(
            "^\\s*(\\d{1,3})\\s*[.、．·，,:：]\\s*(.*)$");

    private static final Pattern OPTION_START = Pattern.compile(
            "^\\s*([A-F])\\s*[.、．·)）]\\s*(.*)$");

    private static final Pattern OPTION_MARK = Pattern.compile(
            "([A-F])\\s*[.、．·)）]\\s*");

    private static final Pattern ANSWER_LINE = Pattern.compile(
            "^\\s*(?:【\\s*(?:参考)?答案\\s*】|\\[(?:参考)?答案\\]|(?:参考)?答案\\s*[:：])\\s*(.*)$");

    private static final Pattern ANALYSIS_LINE = Pattern.compile(
            "^\\s*(?:【\\s*(?:答案)?解析\\s*】|\\[(?:答案)?解析\\]|(?:答案)?解析\\s*[:：])\\s*(.*)$");

    private static final Pattern KP_LINE = Pattern.compile(
            "^\\s*(?:【\\s*(?:考点|知识点)\\s*】|(?:考点|知识点)\\s*[:：])\\s*(.*)$");

    private static final Pattern ANSWER_HEADING = Pattern.compile(
            "^\\s*(参考答案|答案|参考答案及评分参考|参考答案及评分标准|答案及评分标准|参考答案与解析)\\s*$");

    private static final Pattern PAIR = Pattern.compile(
            "(?<!\\d)(\\d{1,3})\\s*[.、．·:：]?\\s*([A-F]{1,4}|正确|错误|对|错|√|×)(?![A-Za-z])");

    /** 答案直接印在题干括号里：22．……（  C  ） */
    private static final Pattern INLINE_ANSWER = Pattern.compile(
            "[（(]\\s*([A-Fa-f](?:\\s*[A-Fa-f]){0,3}|对|错|正确|错误|√|×)\\s*[)）]");

    private static final List<Pattern> NOISE = List.of(
            Pattern.compile(".*第\\s*\\d+\\s*页.*"),
            Pattern.compile(".*(微信|QQ群|公众号|扫码|客服|加群).*"),
            Pattern.compile("^[\\s\\-—=_*·.．]{3,}$"),
            Pattern.compile("^机密.*$"),
            Pattern.compile("^绝密.*$")
    );

    public ParseResult parse(String raw) {
        List<String> lines = toLines(raw);
        long qLike = lines.stream().filter(l -> Q_START.matcher(l).matches()).count();
        long optLike = lines.stream().filter(this::startsWithOption).count();
        boolean paperLike = raw.contains("单项选择") || raw.contains("多项选择")
                || raw.contains("判断题") || raw.contains("选择题")
                || (qLike >= 8 && optLike >= 8);

        List<ParsedQuestion> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String type = null;
        ParsedQuestion cur = null;
        int lastNo = 0;
        boolean inAnswers = false;
        String mode = "STEM";
        Map<Integer, String> answerKey = new LinkedHashMap<>();

        for (String line : lines) {
            if (ANSWER_HEADING.matcher(line).matches()) {
                flush(cur, out);
                cur = null;
                inAnswers = true;
                mode = "KEY";
                continue;
            }
            if (inAnswers) {
                collectAnswerKey(line, answerKey);
                continue;
            }

            Matcher sec = SECTION.matcher(line);
            if (sec.find()) {
                String mapped = mapType(sec.group(1));
                if (mapped != null) {
                    flush(cur, out);
                    cur = null;
                    type = mapped;
                    lastNo = 0;
                    mode = "STEM";
                    continue;
                }
            }

            Matcher anySec = SECTION_ANY.matcher(line);
            if (anySec.matches() && looksLikeSectionTitle(anySec.group(1))) {
                flush(cur, out);
                cur = null;
                type = null;
                lastNo = 0;
                mode = "STEM";
                continue;
            }

            Matcher qm = Q_START.matcher(line);
            if (qm.matches() && (type != null || paperLike)) {
                int no = Integer.parseInt(qm.group(1));
                boolean fresh = cur == null
                        || no == lastNo + 1
                        || (no > lastNo && no <= lastNo + 3)
                        || (no == 1 && lastNo > 3);
                if (fresh) {
                    flush(cur, out);
                    cur = new ParsedQuestion();
                    cur.setNumber(no);
                    cur.setType(type);
                    String rest = qm.group(2).strip();
                    int optIdx = firstOptionIndex(rest);
                    if (optIdx > 0) {
                        cur.addStem(rest.substring(0, optIdx).strip());
                        splitOptions(rest.substring(optIdx), cur);
                        mode = "OPTION";
                    } else {
                        int ansIdx = indexOfAnswerColon(rest);
                        if (ansIdx > 0) {
                            cur.addStem(rest.substring(0, ansIdx).strip());
                            cur.setAnswer(rest.substring(ansIdx + 2).strip());
                            mode = "ANSWER";
                        } else {
                            if (!rest.isEmpty()) {
                                cur.addStem(rest);
                            }
                            mode = "STEM";
                        }
                    }
                    lastNo = no;
                    continue;
                }
            }

            if (cur == null) {
                continue;
            }

            Matcher am = ANSWER_LINE.matcher(line);
            if (am.matches()) {
                String value = am.group(1).strip();
                int cut = value.indexOf("【解析】");
                if (cut >= 0) {
                    cur.setAnswer(value.substring(0, cut).strip());
                    cur.addAnalysis(value.substring(cut + 4).strip());
                    mode = "ANALYSIS";
                } else {
                    cur.setAnswer(value);
                    mode = "ANSWER";
                }
                continue;
            }

            Matcher an = ANALYSIS_LINE.matcher(line);
            if (an.matches()) {
                String value = an.group(1).strip();
                if (cur.getAnswer() == null || cur.getAnswer().isBlank()) {
                    // 有的资料把解析写在答案位置
                    cur.addAnalysis(value);
                } else {
                    cur.addAnalysis(value);
                }
                mode = "ANALYSIS";
                continue;
            }

            Matcher kp = KP_LINE.matcher(line);
            if (kp.matches()) {
                String value = kp.group(1).strip();
                if (!value.isEmpty()) {
                    cur.getTags().add(value);
                }
                mode = "STEM";
                continue;
            }

            if (OPTION_START.matcher(line).find() && startsWithOption(line)) {
                splitOptions(line, cur);
                mode = "OPTION";
                continue;
            }

            switch (mode) {
                case "ANSWER" -> {
                    if (isSubjective(cur.getType())) {
                        cur.setAnswer(safe(cur.getAnswer()) + "\n" + line);
                    }
                }
                case "ANALYSIS" -> cur.addAnalysis(line);
                case "OPTION" -> {
                    if (!cur.getOptions().isEmpty()) {
                        cur.getOptions().get(cur.getOptions().size() - 1).append(line);
                    }
                }
                default -> cur.addStem(line);
            }
        }
        flush(cur, out);

        if (!answerKey.isEmpty()) {
            int applied = 0;
            for (ParsedQuestion q : out) {
                if (q.getAnswer() == null || q.getAnswer().isBlank()) {
                    String a = answerKey.get(q.getNumber());
                    if (a != null) {
                        q.setAnswer(a);
                        applied++;
                    }
                }
            }
            warnings.add("卷尾答案区解析出 " + answerKey.size() + " 条，回填 " + applied + " 题");
        }

        return new ParseResult(out, warnings);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private void flush(ParsedQuestion q, List<ParsedQuestion> out) {
        if (q == null) {
            return;
        }
        String stem = q.getStem() == null ? "" : q.getStem().strip();
        q.setStem(stem);
        if (stem.length() < 4) {
            return;
        }
        if ((q.getAnswer() == null || q.getAnswer().isBlank()) && q.hasOptions()) {
            Matcher m = INLINE_ANSWER.matcher(stem);
            String found = null;
            int start = -1;
            int end = -1;
            while (m.find()) {
                found = m.group(1);
                start = m.start();
                end = m.end();
            }
            if (found != null) {
                q.setAnswer(found.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT));
                q.setStem((stem.substring(0, start) + "（  ）" + stem.substring(end)).strip());
            }
        }
        if (q.getType() == null) {
            q.setType(inferType(q));
        }
        out.add(q);
    }

    private int firstOptionIndex(String s) {
        Matcher m = OPTION_MARK.matcher(s);
        while (m.find()) {
            if (!"A".equals(m.group(1))) {
                continue;
            }
            if (!(m.start() == 0 || Character.isWhitespace(s.charAt(m.start() - 1))
                    || "）)".indexOf(s.charAt(m.start() - 1)) >= 0)) {
                continue;
            }
            long count = OPTION_MARK.matcher(s.substring(m.start())).results().count();
            if (count >= 3) {
                return m.start();
            }
        }
        return -1;
    }

    private int indexOfAnswerColon(String s) {
        int a = s.lastIndexOf("答：");
        int b = s.lastIndexOf("答:");
        return Math.max(a, b);
    }

    private String inferType(ParsedQuestion q) {
        String ans = q.getAnswer() == null ? "" : q.getAnswer().strip();
        if (q.hasOptions()) {
            return ans.replaceAll("[^A-F]", "").length() > 1 ? "MULTIPLE" : "SINGLE";
        }
        if (ans.matches("^(对|错|正确|错误|√|×|T|F)$")) {
            return "JUDGE";
        }
        return "SHORT";
    }

    private boolean startsWithOption(String line) {
        Matcher m = OPTION_MARK.matcher(line);
        return m.find() && m.start() <= 1;
    }

    private boolean looksLikeSectionTitle(String rest) {
        String s = rest.strip();
        if (s.length() > 80) {
            return false;
        }
        return s.contains("本大题") || s.contains("部分") || s.contains("考生") || s.contains("作答");
    }

    private void splitOptions(String line, ParsedQuestion q) {
        Matcher m = OPTION_MARK.matcher(line);
        int lastEnd = -1;
        String lastKey = null;
        while (m.find()) {
            if (lastEnd >= 0) {
                addOption(q, lastKey, line.substring(lastEnd, m.start()));
            }
            lastKey = m.group(1);
            lastEnd = m.end();
        }
        if (lastEnd >= 0) {
            addOption(q, lastKey, line.substring(lastEnd));
        }
    }

    private void addOption(ParsedQuestion q, String key, String text) {
        String t = text == null ? "" : text.strip();
        for (ParsedQuestion.ParsedOption o : q.getOptions()) {
            if (o.getKey().equals(key)) {
                o.append(t);
                return;
            }
        }
        q.getOptions().add(new ParsedQuestion.ParsedOption(key, t));
    }

    private void collectAnswerKey(String line, Map<Integer, String> key) {
        Matcher m = PAIR.matcher(line);
        while (m.find()) {
            try {
                int no = Integer.parseInt(m.group(1));
                if (no >= 1 && no <= 300) {
                    key.putIfAbsent(no, m.group(2));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private boolean isSubjective(String type) {
        return "FILL".equals(type) || "TERM".equals(type) || "SHORT".equals(type)
                || "CALC".equals(type) || "ESSAY".equals(type);
    }

    private String mapType(String s) {
        if (s.contains("单项选择") || s.equals("单选")) return "SINGLE";
        if (s.contains("多项选择") || s.equals("多选")) return "MULTIPLE";
        if (s.contains("判断")) return "JUDGE";
        if (s.contains("填空")) return "FILL";
        if (s.contains("名词解释")) return "TERM";
        if (s.contains("简答")) return "SHORT";
        if (s.contains("计算")) return "CALC";
        if (s.contains("论述") || s.contains("分析") || s.contains("综合")) return "ESSAY";
        return null;
    }

    private List<String> toLines(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replace('　', ' ');
        List<String> out = new ArrayList<>();
        for (String raw : t.split("\n")) {
            String s = raw.strip().replaceAll("[ \\t]{2,}", " ");
            if (s.isEmpty()) {
                continue;
            }
            boolean noise = NOISE.stream().anyMatch(p -> p.matcher(s).matches());
            if (noise) {
                continue;
            }
            out.add(s);
        }
        return out;
    }
}
