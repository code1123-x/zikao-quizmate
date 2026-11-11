package com.quizmate.importer;

import com.quizmate.dto.Dtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别答案扫描页里的「题号 + 答案」。
 *
 * 思路：答案页永远是「网格」——每行几列、从上到下、从左到右，题号连续。
 * 所以不依赖 OCR 读对题号，只用版面位置推题号；题号只用来给第一行兜底。
 * 再用两种版面分析（PSM 6 / PSM 4）各跑一遍，只保留两边一致的答案，降低误识别。
 */
@Slf4j
@Service
public class AnswerOcrService {

    private static final Pattern CELL = Pattern.compile("^(\\d{1,2})[.,．、]?([A-F]{1,5})");
    private static final Pattern NUM_ONLY = Pattern.compile("^(\\d{1,2})[.,．、]?$");
    private static final Pattern LETTERS_ONLY = Pattern.compile("^([A-F]{1,5})[.]?$");

    private final AnswerImageService images;
    private final OcrService ocr;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AnswerOcrService(AnswerImageService images, OcrService ocr,
                            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.images = images;
        this.ocr = ocr;
        this.jdbc = jdbc;
    }

    public Dtos.OcrResult recognize(String source, Long subjectId, boolean loose) {
        List<Path> files = images.images(source);
        if (files.isEmpty()) {
            return new Dtos.OcrResult(List.of(), 0, "这套卷子没有答案图");
        }
        Map<Integer, Map<String, Integer>> votes = new TreeMap<>();
        Map<Integer, Map<String, Integer>> origVotes = new TreeMap<>();
        Map<Integer, String> types = types(source, subjectId);

        int pages = Math.min(files.size(), 8);
        for (int i = 0; i < pages; i++) {
            Path img = files.get(i);
            Map<Integer, String> o6 = parse(tokens(img, 6, false));
            if (o6.size() < 5) {
                continue; // 这页不是答案表，省得白跑三遍
            }
            Map<Integer, String> o4 = parse(tokens(img, 4, false));
            Map<Integer, String> c6 = parse(tokens(img, 6, true));
            Map<Integer, String> c4 = parse(tokens(img, 4, true));
            log.debug("OCR {} 第{}页：原图6列={} 原图4列={} 去水印6列={} 去水印4列={}",
                    source, i + 1, o6.size(), o4.size(), c6.size(), c4.size());
            vote(votes, origVotes, o6, true, types);
            vote(votes, origVotes, o4, true, types);
            vote(votes, origVotes, c6, false, types);
            vote(votes, origVotes, c4, false, types);
            if (votes.size() >= 40) {
                break;
            }
        }

        // 多数票：至少两票、且至少一票来自原图；2:2 打平就丢掉
        Map<Integer, String> merged = new TreeMap<>();
        int lowConfidence = 0;
        for (Map.Entry<Integer, Map<String, Integer>> e : votes.entrySet()) {
            int qno = e.getKey();
            String best = null;
            int bestN = 0;
            int second = 0;
            for (Map.Entry<String, Integer> v : e.getValue().entrySet()) {
                int n = v.getValue();
                if (n > bestN) {
                    second = bestN;
                    bestN = n;
                    best = v.getKey();
                } else if (n > second) {
                    second = n;
                }
            }
            int fromOrig = origVotes.getOrDefault(qno, Map.of()).getOrDefault(best, 0);
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            boolean consensus = bestN >= 2 && bestN > second && fromOrig > 0;
            // 只有一个识别方式读到了这题（其它方式都没读到）
            boolean single = bestN == 1 && total == 1;
            if (best == null) {
                continue;
            }
            if (consensus) {
                merged.put(qno, best);
            } else if (single && loose) {
                merged.put(qno, best);
                lowConfidence++;
            }
        }

        Map<Integer, String> valid = filter(merged, source, subjectId);
        int pending = pendingCount(source, subjectId);
        String msg;
        if (!valid.isEmpty()) {
            int left = Math.max(0, pending - valid.size());
            msg = "识别 " + valid.size() + " 题"
                    + (lowConfidence > 0 ? "（其中 " + lowConfidence + " 个为单次识别，建议重点核对）" : "")
                    + (left > 0 ? "，还有 " + left + " 题没识别出来（水印遮挡或图片不是答案页），需要对照图片补" : "")
                    + "；请核对后再填入";
        } else if (!merged.isEmpty()) {
            msg = "识别到 " + merged.size() + " 项，但剩下的 " + pending
                    + " 道待补题都没对上（多半被水印盖住或识别偏差），请对照答案图手动补";
        } else {
            msg = "这套卷的图片里没找到答案表（多半是题目插图或试卷扫描页，不是答案页）";
        }
        return toResult(valid, msg);
    }

    private int pendingCount(String source, Long subjectId) {
        if (subjectId == null) {
            return 0;
        }
        try {
            Integer n = jdbc.queryForObject(
                    "select count(*) from question where subject_id=? and source=? and status='NO_ANSWER'",
                    Integer.class, subjectId, source);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void vote(Map<Integer, Map<String, Integer>> votes,
                             Map<Integer, Map<String, Integer>> origVotes,
                             Map<Integer, String> answers, boolean fromOrig,
                             Map<Integer, String> types) {
        for (Map.Entry<Integer, String> e : answers.entrySet()) {
            String type = types.get(e.getKey());
            if (!shapeOk(type, e.getValue())) {
                continue;
            }
            votes.computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                    .merge(e.getValue(), 1, Integer::sum);
            if (fromOrig) {
                origVotes.computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                        .merge(e.getValue(), 1, Integer::sum);
            }
        }
    }

    /** 用题库里的题型过滤明显不合理的识别结果（多选不可能是单字母） */
    private static boolean shapeOk(String type, String ans) {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case "SINGLE" -> ans.length() == 1;
            case "MULTIPLE" -> ans.length() >= 2;
            case "JUDGE" -> false;
            default -> true;
        };
    }

    private Map<Integer, String> types(String source, Long subjectId) {
        if (subjectId == null) {
            return Map.of();
        }
        Map<Integer, String> map = new java.util.HashMap<>();
        try {
            jdbc.query("select qno, type from question where subject_id=? and source=? and qno is not null",
                    rs -> {
                        map.put(rs.getInt("qno"), rs.getString("type"));
                    }, subjectId, source);
        } catch (Exception e) {
            return Map.of();
        }
        return map;
    }

    /** OCR 结果按图片缓存成词表，换解析算法时不用重新跑 OCR */
    private List<OcrService.Token> tokens(Path image, int psm, boolean clean) {
        Path cache = image.resolveSibling(image.getFileName() + ".psm" + psm
                + (clean ? ".c" : ".o") + ".v3.txt");
        if (Files.isRegularFile(cache)) {
            List<OcrService.Token> list = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(cache, StandardCharsets.UTF_8)) {
                    String[] p = line.split("\\|");
                    if (p.length == 5) {
                        list.add(new OcrService.Token(p[0], Integer.parseInt(p[1]),
                                Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])));
                    }
                }
                return list;
            } catch (Exception ignored) {
            }
        }
        BufferedImage raw = ocr.readRaw(image);
        if (raw == null) {
            return List.of();
        }
        List<OcrService.Token> list = ocr.tokens(clean ? OcrService.clean(raw) : raw, psm);
        try {
            StringBuilder sb = new StringBuilder();
            for (OcrService.Token t : list) {
                sb.append(t.text()).append('|').append(t.x()).append('|').append(t.y())
                        .append('|').append(t.w()).append('|').append(t.h()).append('\n');
            }
            Files.writeString(cache, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("写 OCR 词表缓存失败 {}", cache, e);
        }
        return list;
    }

    /** 只保留「确实是这套卷子里待补的选择题」且答案形状对得上的 */
    private Map<Integer, String> filter(Map<Integer, String> in, String source, Long subjectId) {
        if (subjectId == null) {
            return in;
        }
        Map<Integer, String> out = new TreeMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select qno, type from question
                     where subject_id=? and source=? and status='NO_ANSWER' and qno is not null
                    """, subjectId, source);
            for (Map<String, Object> r : rows) {
                int qno = ((Number) r.get("qno")).intValue();
                String type = String.valueOf(r.get("type"));
                String ans = in.get(qno);
                if (ans == null) {
                    continue;
                }
                boolean ok = switch (type) {
                    case "SINGLE" -> ans.matches("[A-D]");
                    case "MULTIPLE" -> ans.matches("[A-D]{2,4}");
                    default -> false;
                };
                if (ok) {
                    out.put(qno, ans);
                }
            }
        } catch (Exception e) {
            return in;
        }
        return out;
    }

    private Dtos.OcrResult toResult(Map<Integer, String> answers, String message) {
        List<Dtos.OcrAnswer> list = new ArrayList<>();
        for (Map.Entry<Integer, String> e : answers.entrySet()) {
            list.add(new Dtos.OcrAnswer(e.getKey(), e.getValue()));
        }
        return new Dtos.OcrResult(list, list.size(), message);
    }

    /* ---------------- 版面解析 ---------------- */

    private record Cell(Integer no, String ans, int x, int y, int h) {
    }

    private record Row(int y, int h, List<Cell> cells) {
    }

    private Map<Integer, String> parse(List<OcrService.Token> tokens) {
        List<Cell> cells = new ArrayList<>();
        for (OcrService.Token w : tokens) {
            String t = w.text().replaceAll("\\s+", "");
            if (t.isEmpty() || t.length() > 64) {
                continue;
            }
            int pos = 0;
            int count = 0;
            List<Cell> tokenCells = new ArrayList<>();
            while (pos < t.length()) {
                Matcher m = CELL.matcher(t.substring(pos));
                if (!m.lookingAt()) {
                    break;
                }
                int no = Integer.parseInt(m.group(1));
                String ans = m.group(2);
                int x = w.x() + (int) Math.round((double) w.w() * pos / t.length());
                tokenCells.add(new Cell(no, ans, x, w.y(), w.h()));
                pos += m.end();
                count++;
            }
            // 长 token 只有当它确实是「一整行答案」（连续 4 个以上）才收
            if (count > 0 && (t.length() <= 24 || count >= 4)) {
                cells.addAll(tokenCells);
                continue;
            }
            Matcher nm = NUM_ONLY.matcher(t);
            if (nm.matches()) {
                cells.add(new Cell(Integer.parseInt(nm.group(1)), "", w.x(), w.y(), w.h()));
                continue;
            }
            Matcher lm = LETTERS_ONLY.matcher(t);
            if (lm.matches()) {
                cells.add(new Cell(null, lm.group(1), w.x(), w.y(), w.h()));
            }
        }
        if (cells.size() < 10) {
            return Map.of();
        }

        // 行
        cells.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x));
        List<Row> rows = new ArrayList<>();
        for (Cell c : cells) {
            Row last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null && Math.abs(c.y() - last.y()) <= Math.max(8, last.h() * 0.7)) {
                last.cells().add(c);
            } else {
                rows.add(new Row(c.y(), c.h(), new ArrayList<>(List.of(c))));
            }
        }
        rows.forEach(r -> r.cells().sort(Comparator.comparingInt(Cell::x)));

        // 块：行距突然变大
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            gaps.add(rows.get(i).y() - rows.get(i - 1).y());
        }
        int med = gaps.isEmpty() ? 40 : gaps.stream().sorted().toList().get(gaps.size() / 2);
        List<List<Row>> blocks = new ArrayList<>();
        int lastY = Integer.MIN_VALUE;
        for (Row r : rows) {
            if (!blocks.isEmpty() && r.y() - lastY <= Math.max(med * 1.75, 55)) {
                blocks.get(blocks.size() - 1).add(r);
            } else {
                List<Row> b = new ArrayList<>();
                b.add(r);
                blocks.add(b);
            }
            lastY = r.y();
        }

        Map<Integer, String> answers = new LinkedHashMap<>();
        int next = 1;
        for (List<Row> block : blocks) {
            List<Integer> counts = block.stream().map(r -> r.cells().size()).toList();
            int maxCols = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
            if (maxCols < 3) {
                continue;
            }
            // 列数取「多数行」的列数：某一行多出一个噪声 token 时，不至于把整块列数带偏
            Map<Integer, Integer> freq = new LinkedHashMap<>();
            counts.forEach(c -> freq.merge(c, 1, Integer::sum));
            int mode = freq.entrySet().stream()
                    .max(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparingInt(Map.Entry::getKey))
                    .map(Map.Entry::getKey).orElse(maxCols);
            int cols = freq.getOrDefault(maxCols, 0) * 2 >= counts.size() ? maxCols : mode;
            if (cols < 3) {
                continue;
            }
            List<Cell> all = new ArrayList<>();
            block.forEach(r -> all.addAll(r.cells()));
            long withAns = all.stream().filter(c -> c.ans() != null && !c.ans().isEmpty()).count();
            if (all.isEmpty() || withAns * 10 < all.size() * 4L) {
                continue; // 答案太少，不像答案表（可能是标题/正文）
            }
            List<Integer> lens = new ArrayList<>();
            for (Cell c : all) {
                if (c.ans() != null && !c.ans().isEmpty()) {
                    lens.add(c.ans().length());
                }
            }
            lens.sort(Integer::compareTo);
            boolean single = lens.isEmpty() || lens.get(lens.size() / 2) <= 1;

            int[] colX = new int[cols];
            List<Row> fullRows = block.stream().filter(r -> r.cells().size() == cols).toList();
            for (int i = 0; i < cols; i++) {
                final int idx = i;
                List<Integer> xs = fullRows.stream().map(r -> r.cells().get(idx).x()).sorted().toList();
                colX[i] = xs.isEmpty() ? i * 176 : xs.get(xs.size() / 2);
            }

            for (Row r : block) {
                Map<Integer, Cell> assign = new TreeMap<>();
                if (r.cells().size() == cols) {
                    for (int i = 0; i < cols; i++) {
                        assign.put(next + i, r.cells().get(i));
                    }
                } else {
                    for (Cell c : r.cells()) {
                        int best = 0;
                        int bestD = Integer.MAX_VALUE;
                        for (int i = 0; i < cols; i++) {
                            int d = Math.abs(colX[i] - c.x());
                            if (d < bestD) {
                                bestD = d;
                                best = i;
                            }
                        }
                        assign.putIfAbsent(next + best, c);
                    }
                }
                for (Map.Entry<Integer, Cell> e : assign.entrySet()) {
                    String ans = clean(e.getValue().ans(), single);
                    if (!ans.isEmpty()) {
                        answers.putIfAbsent(e.getKey(), ans);
                    }
                }
                next += cols;
            }
        }
        return answers;
    }

    private static String clean(String raw, boolean single) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw.toUpperCase();
        // 出现 A-D 之外的字母（E/F 多半是水印或误识别）就整条不要
        for (char ch : s.toCharArray()) {
            if (ch < 'A' || ch > 'D') {
                return "";
            }
        }
        String uniq = s.chars().distinct().sorted()
                .collect(StringBuilder::new, (sb, c) -> sb.append((char) c), StringBuilder::append).toString();
        if (single) {
            return uniq.length() == 1 ? uniq : "";
        }
        return uniq.length() >= 2 ? uniq : "";
    }

}
