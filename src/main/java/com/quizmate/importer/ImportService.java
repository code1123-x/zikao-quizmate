package com.quizmate.importer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quizmate.common.BizException;
import com.quizmate.common.CurrentUser;
import com.quizmate.common.Json;
import com.quizmate.domain.Chapter;
import com.quizmate.domain.Question;
import com.quizmate.domain.Subject;
import com.quizmate.dto.Dtos;
import com.quizmate.mapper.ChapterMapper;
import com.quizmate.mapper.QuestionMapper;
import com.quizmate.mapper.SubjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final Set<String> SUPPORTED = Set.of(".doc", ".docx", ".pdf", ".pptx", ".txt", ".md", ".xlsx", ".xls");
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})\\s*[.年_\\-]?\\s*(\\d{1,2})?");

    private final TextExtractor extractor;
    private final PaperParser parser;
    private final AnswerKeyParser answerKeyParser;
    private final SubjectMapper subjectMapper;
    private final ChapterMapper chapterMapper;
    private final QuestionMapper questionMapper;
    private final JdbcTemplate jdbc;

    public ImportService(TextExtractor extractor, PaperParser parser, AnswerKeyParser answerKeyParser,
                         SubjectMapper subjectMapper, ChapterMapper chapterMapper,
                         QuestionMapper questionMapper, JdbcTemplate jdbc) {
        this.extractor = extractor;
        this.parser = parser;
        this.answerKeyParser = answerKeyParser;
        this.subjectMapper = subjectMapper;
        this.chapterMapper = chapterMapper;
        this.questionMapper = questionMapper;
        this.jdbc = jdbc;
    }

    public List<java.util.Map<String, Object>> preview(String path, int limit) throws Exception {
        File f = requireUnderRaw(path, false).toFile();
        if (!SUPPORTED.contains(ext(f))) {
            throw new BizException("不支持的文件类型");
        }
        TextExtractor.Extracted ex = extractor.extract(f);
        if (ex.text() == null || ex.text().isBlank()) {
            throw new BizException("没有可用文本（扫描件？）");
        }
        PaperParser.ParseResult pr = parser.parse(ex.text());
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (ParsedQuestion q : pr.questions()) {
            if (out.size() >= limit) {
                break;
            }
            String stem = q.getStem() == null ? "" : q.getStem().replace("\n", " ");
            out.add(java.util.Map.of(
                    "number", q.getNumber(),
                    "type", String.valueOf(q.getType()),
                    "stem", stem.length() > 90 ? stem.substring(0, 90) + "…" : stem,
                    "options", q.getOptions().size(),
                    "answer", String.valueOf(q.getAnswer()),
                    "analysisLen", q.getAnalysis() == null ? 0 : q.getAnalysis().length(),
                    "tags", q.getTags()));
        }
        return out;
    }

    /* ---------- 导入工作台 ---------- */

    /** 浏览 raw/ 目录（只允许 raw 下，避免任意读盘） */
    public Dtos.FsListing browse(String path) {
        Path root = rawRoot();
        Path dir = requireUnderRaw(path, true);
        List<Dtos.FsEntry> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                boolean isDir = Files.isDirectory(p);
                String ext = isDir ? "" : ext(p.toFile());
                long size = 0;
                if (!isDir) {
                    try {
                        size = Files.size(p);
                    } catch (Exception ignored) {
                    }
                }
                entries.add(new Dtos.FsEntry(name, p.toString(), isDir, size, ext,
                        !isDir && SUPPORTED.contains(ext)));
            }
        } catch (Exception e) {
            throw new BizException("读取目录失败：" + e.getMessage());
        }
        entries.sort(Comparator.comparing(Dtos.FsEntry::dir).reversed()
                .thenComparing(Dtos.FsEntry::name));
        String parent = dir.equals(root) ? null
                : (dir.getParent() == null ? root.toString() : dir.getParent().toString());
        return new Dtos.FsListing(root.toString(), dir.toString(), parent, entries);
    }

    /** raw/ 的真实绝对路径；目录不存在时自动创建。 */
    private static Path rawRoot() {
        try {
            Path root = CurrentUser.rawDir();
            Files.createDirectories(root);
            return root.toRealPath();
        } catch (Exception e) {
            throw new BizException("账号资料目录不可用：" + e.getMessage());
        }
    }

    /**
     * 所有导入接口统一限制在 raw/ 下。
     * 先用规范化路径做字面校验，再用 toRealPath 防止符号链接/目录联接跳出去。
     */
    private static Path requireUnderRaw(String path, boolean directory) {
        Path lexicalRoot = CurrentUser.rawDir();
        Path target;
        try {
            target = (path == null || path.isBlank()) ? lexicalRoot
                    : Path.of(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new BizException("路径不合法");
        }
        if (!target.startsWith(lexicalRoot)) {
            throw new BizException("只能访问当前账号的资料目录");
        }
        if (!Files.exists(target)) {
            throw new BizException(directory ? "目录不存在" : "文件不存在");
        }
        try {
            Path realRoot = rawRoot();
            Path real = target.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new BizException("只能访问当前账号的资料目录");
            }
            if (directory && !Files.isDirectory(real)) {
                throw new BizException("不是目录");
            }
            if (!directory && !Files.isRegularFile(real)) {
                throw new BizException("不是文件");
            }
            return real;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("读取路径失败：" + e.getMessage());
        }
    }

    /**
     * 导入目录不能是 raw 根目录，否则会把所有学科的文件夹一次性导入到当前学科。
     * raw/ 根目录只用于浏览，必须进入某个学科/试卷子目录后再预检或导入。
     */
    private static Path requireImportDir(String path) {
        Path root = rawRoot();
        Path dir = requireUnderRaw(path, true);
        if (dir.equals(root)) {
            throw new BizException("请先进入某个学科/试卷文件夹，不能直接导入账号资料根目录");
        }
        return dir;
    }

    /** 预检：只解析、不落库，让用户导入前先看清楚 */
    public Dtos.ScanResult scan(Dtos.ImportScanRequest req) {
        if (req.subjectId() == null) {
            throw new BizException("请先选择学科");
        }
        File dir = requireImportDir(req.path()).toFile();
        List<File> files = listSupportedFiles(dir);
        Map<String, Map<Integer, String>> keys = collectAnswerKeys(files);

        List<Dtos.ScanFile> details = new ArrayList<>();
        Set<String> knownHashes = loadHashes(req.subjectId());
        int parsedSum = 0;
        int answerSum = 0;
        int pendingSum = 0;
        int scannedSum = 0;
        int skippedSum = 0;
        int realFiles = 0;

        for (File f : files) {
            if (isAnswerKeyFile(f.getName())) {
                details.add(new Dtos.ScanFile(f.getName(), f.getAbsolutePath(), 0, 0, 0, 0, 0, 0,
                        false, "参考答案文件（用于按考期对齐答案）", List.of()));
                continue;
            }
            realFiles++;
            try {
                TextExtractor.Extracted ex = extractor.extract(f);
                if (ex.text() == null || ex.text().isBlank() || ex.scanned()) {
                    scannedSum++;
                    details.add(new Dtos.ScanFile(f.getName(), f.getAbsolutePath(), 0, 0, 0, 0, 0, 0,
                            true, "扫描件（没有文字层），需要 OCR 或补答案图", List.of()));
                    continue;
                }
                PaperParser.ParseResult pr = parser.parse(ex.text());
                Map<Integer, String> key = keys.get(yearMonth(f.getName()));
                int parsed = pr.questions().size();
                int objective = 0;
                int withAnswer = 0;
                int pending = 0;
                int skipped = 0;
                int duplicates = 0;
                List<String> samples = new ArrayList<>();
                for (ParsedQuestion pq : pr.questions()) {
                    String type = normalizeType(pq);
                    if (!isObjective(type)) {
                        skipped++;
                        continue;
                    }
                    objective++;
                    String answer = normalizeAnswer(pq, type);
                    if ((answer == null || answer.isBlank()) && key != null) {
                        String fromKey = key.get(pq.getNumber());
                        if (fromKey != null) {
                            pq.setAnswer(fromKey);
                            answer = normalizeAnswer(pq, type);
                        }
                    }
                    String questionHash = hash(req.subjectId(), pq.getStem());
                    boolean duplicate = knownHashes.contains(questionHash);
                    if (duplicate) duplicates++;
                    if (answer == null || answer.isBlank()) {
                        if (hasStructure(pq, type)) {
                            pending++;
                        } else {
                            skipped++;
                        }
                    } else if (!valid(pq, type, answer)) {
                        skipped++;
                    } else if (!duplicate) {
                        withAnswer++;
                    }
                    if (samples.size() < 3) {
                        String stem = pq.getStem() == null ? "" : pq.getStem().replace("\n", " ").strip();
                        samples.add(pq.getNumber() + ". " + (stem.length() > 42 ? stem.substring(0, 42) + "…" : stem));
                    }
                }
                parsedSum += parsed;
                answerSum += withAnswer;
                pendingSum += pending;
                skippedSum += skipped;
                String msg = "解析 " + parsed + " 题，可导入 " + withAnswer
                        + (pending > 0 ? "，待补答案 " + pending : "")
                        + (duplicates > 0 ? "，已有同题 " + duplicates : "")
                        + (pr.warnings().isEmpty() ? "" : "；" + String.join("；", pr.warnings()));
                details.add(new Dtos.ScanFile(f.getName(), f.getAbsolutePath(), parsed, objective,
                        withAnswer, pending, skipped, duplicates, false, msg, samples));
            } catch (Exception e) {
                log.warn("预检失败 {}", f.getName(), e);
                details.add(new Dtos.ScanFile(f.getName(), f.getAbsolutePath(), 0, 0, 0, 0, 0, 0,
                        false, "解析失败：" + e.getClass().getSimpleName(), List.of()));
            }
        }
        return new Dtos.ScanResult(realFiles, parsedSum, answerSum, pendingSum, scannedSum, skippedSum, details);
    }

    public List<Dtos.ImportBatchView> batches(Long subjectId) {
        String sql = """
                select b.id, b.subject_id, s.name as subject_name, b.file_name, b.file_path, b.status,
                       b.total, b.imported, b.skipped, b.created_at
                  from import_batch b left join subject s on s.id = b.subject_id
                """;
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            sql += " where b.subject_id=?";
            args.add(subjectId);
        }
        sql += " order by b.id desc limit 50";
        return jdbc.query(sql, (rs, i) -> new Dtos.ImportBatchView(
                rs.getLong("id"), rs.getLong("subject_id"), rs.getString("subject_name"),
                rs.getString("file_name"), rs.getString("file_path"), rs.getString("status"),
                rs.getInt("total"), rs.getInt("imported"), rs.getInt("skipped"),
                rs.getString("created_at")), args.toArray());
    }

    /** 按「学科 + 文件夹」聚合的导入历史，供历史抽屉分页展示。 */
    public Dtos.ImportHistoryPage history(Long subjectId, String status, String keyword,
                                          int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, 50));
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            where.append(" and b.subject_id=?");
            args.add(subjectId);
        }
        String st = status == null ? "active" : status;
        switch (st) {
            case "failed" -> where.append(" and b.status='FAILED'");
            case "rolled_back" -> where.append(" and b.status='ROLLED_BACK'");
            case "all" -> { }
            default -> where.append("""
                     and b.status <> 'ROLLED_BACK'
                     and not (b.status='DONE' and b.imported=0)
                    """);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (b.file_name like ? or b.file_path like ? or s.name like ?)");
            String like = "%" + keyword.strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.id, b.subject_id, s.name as subject_name, b.file_name, b.file_path,
                       b.status, b.total, b.imported, b.skipped, b.created_at
                  from import_batch b left join subject s on s.id = b.subject_id
                """ + where + " order by b.id desc", args.toArray());

        Map<String, List<Dtos.ImportBatchView>> grouped = new LinkedHashMap<>();
        Map<String, Dtos.ImportBatchView> latest = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Dtos.ImportBatchView view = new Dtos.ImportBatchView(
                    ((Number) row.get("id")).longValue(),
                    row.get("subject_id") == null ? null : ((Number) row.get("subject_id")).longValue(),
                    (String) row.get("subject_name"),
                    (String) row.get("file_name"),
                    (String) row.get("file_path"),
                    (String) row.get("status"),
                    ((Number) row.get("total")).intValue(),
                    ((Number) row.get("imported")).intValue(),
                    ((Number) row.get("skipped")).intValue(),
                    (String) row.get("created_at"));
            String key = view.subjectId() + "|" + (view.filePath() == null ? "" : view.filePath());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(view);
            latest.putIfAbsent(key, view);
        }

        List<Dtos.ImportHistoryGroup> groups = new ArrayList<>();
        grouped.forEach((key, batches) -> {
            Dtos.ImportBatchView head = latest.get(key);
            int imported = batches.stream().mapToInt(Dtos.ImportBatchView::imported).sum();
            int skipped = batches.stream().mapToInt(Dtos.ImportBatchView::skipped).sum();
            groups.add(new Dtos.ImportHistoryGroup(
                    head.subjectId(), head.subjectName(), head.filePath(), head.fileName(),
                    head.status(), head.createdAt(), batches.size(), imported, skipped, batches));
        });

        int totalGroups = groups.size();
        int from = Math.min((p - 1) * s, totalGroups);
        int to = Math.min(from + s, totalGroups);
        return new Dtos.ImportHistoryPage(p, s, totalGroups, rows.size(),
                new ArrayList<>(groups.subList(from, to)));
    }

    /** 回滚一次导入：删掉这批题，连同作答记录和复习状态 */
    @Transactional
    public int rollback(Long batchId) {
        return rollbackInternal(batchId);
    }

    /** 回滚某个学科某个文件夹下的全部导入批次。 */
    @Transactional
    public int rollbackGroup(Long subjectId, String path) {
        if (subjectId == null || path == null || path.isBlank()) {
            throw new BizException("缺少要回滚的文件夹");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, status from import_batch where subject_id=? and file_path=?",
                subjectId, path);
        if (rows.isEmpty()) {
            throw new BizException("没有找到这个文件夹的导入记录");
        }
        for (Map<String, Object> row : rows) {
            if ("RUNNING".equals(row.get("status"))) {
                throw new BizException("这个文件夹还有导入任务在运行，不能删除");
            }
        }
        int removed = 0;
        for (Map<String, Object> row : rows) {
            removed += rollbackInternal(((Number) row.get("id")).longValue());
        }
        return removed;
    }

    private int rollbackInternal(Long batchId) {
        List<String> statuses = jdbc.queryForList("select status from import_batch where id=?", String.class, batchId);
        if (!statuses.isEmpty() && "RUNNING".equals(statuses.get(0))) {
            throw new BizException("导入还在进行中，不能回滚");
        }
        List<Long> ids = jdbc.queryForList("select id from question where import_batch_id=?", Long.class, batchId);
        for (Long id : ids) {
            jdbc.update("delete from ai_check where question_id=?", id);
            jdbc.update("delete from answer_record where question_id=?", id);
            jdbc.update("delete from review_state where item_id=? and item_type in ('QUESTION','KNOWLEDGE')", id);
            jdbc.update("delete from question where id=?", id);
        }
        jdbc.update("update import_batch set status='ROLLED_BACK', message=? where id=?",
                "回滚 " + ids.size() + " 题", batchId);
        return ids.size();
    }

    private List<File> listSupportedFiles(File dir) {
        List<File> files = new ArrayList<>();
        Path root = rawRoot();
        try (var stream = Files.walk(dir.toPath())) {
            stream.map(Path::toFile)
                    .filter(File::isFile)
                    .filter(f -> SUPPORTED.contains(ext(f)))
                    // 防止 raw/ 内的链接文件指向外部
                    .filter(f -> {
                        try {
                            return f.toPath().toRealPath().startsWith(root);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .sorted(Comparator.comparing(File::getName))
                    .forEach(files::add);
        } catch (Exception e) {
            throw new BizException("扫描目录失败：" + e.getMessage());
        }
        return files;
    }

    /**
     * 后台任务用的导入上下文：路径、文件列表、答案索引、学科和批次都在这里准备好，
     * 真正逐文件导入由 ImportJobService 在后台线程执行。
     */
    public record ImportContext(
            Long batchId,
            File dir,
            Subject subject,
            Long chapterId,
            Set<String> existing,
            List<File> files,
            Map<String, Map<Integer, String>> answerKeys,
            Integer year
    ) {
    }

    /** 校验路径并准备导入上下文；不执行题目写入。 */
    public ImportContext prepare(Dtos.ImportRequest req) {
        if (req == null) {
            throw new BizException("导入参数不能为空");
        }
        File dir = requireImportDir(req.path()).toFile();
        Subject subject = resolveSubject(req);
        List<File> files = listSupportedFiles(dir);
        if (files.isEmpty() || files.stream().noneMatch(f -> !isAnswerKeyFile(f.getName()))) {
            throw new BizException("这个文件夹里没有可导入的文档");
        }
        Map<String, Map<Integer, String>> keys = collectAnswerKeys(files);
        Long chapterId = defaultChapter(subject.getId());
        Set<String> existing = loadHashes(subject.getId());
        // 最后建批次，前面的准备失败时不会留下 RUNNING 的孤儿批次
        Long batchId = createBatch(subject.getId(), dir);
        return new ImportContext(batchId, dir, subject, chapterId, existing, files, keys, req.year());
    }

    /** 后台任务调用：按文件导入，答案文件由调用方跳过。 */
    @Transactional
    public Dtos.ImportFileResult importOne(ImportContext ctx, File file) {
        Map<Integer, String> key = ctx.answerKeys().get(yearMonth(file.getName()));
        return importOne(file, ctx.subject(), ctx.chapterId(), ctx.existing(),
                ctx.batchId(), ctx.year(), key);
    }

    public void updateBatch(Long batchId, String status, int total, int imported,
                            int skipped, String message) {
        jdbc.update("update import_batch set status=?, total=?, imported=?, skipped=?, message=? where id=?",
                status, total, imported, skipped, message, batchId);
    }

    /** 小程序 / 手机上传单个文件：保存到当前账号 raw/上传导入/ 下，再复用目录导入。 */
    @Transactional
    public Dtos.ImportResult importUpload(MultipartFile file, Long subjectId, String subjectName,
                                          Integer year, Long majorId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            original = "upload";
        }
        original = Path.of(original).getFileName().toString()
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        String ext = ext(new File(original));
        if (!SUPPORTED.contains(ext)) {
            throw new BizException("不支持的文件类型，只支持 doc/docx/pdf/pptx/txt/md/xlsx/xls");
        }
        try {
            Path dir = rawRoot()
                    .resolve("上传导入")
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
            Files.createDirectories(dir);
            Path target = dir.resolve(original).normalize();
            if (!target.startsWith(dir)) {
                throw new BizException("文件名不合法");
            }
            file.transferTo(target.toFile());
            Dtos.ImportRequest req = new Dtos.ImportRequest(
                    dir.toString(), subjectName, subjectId, year, majorId);
            return importDirectory(req);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("上传导入失败：" + e.getMessage());
        }
    }

    @Transactional
    public Dtos.ImportResult importDirectory(Dtos.ImportRequest req) {        File dir = requireImportDir(req.path()).toFile();
        Subject subject = resolveSubject(req);
        Long batchId = createBatch(subject.getId(), dir);
        Long chapterId = defaultChapter(subject.getId());
        Set<String> existing = loadHashes(subject.getId());

        List<File> files = listSupportedFiles(dir);

        List<Dtos.ImportFileResult> details = new ArrayList<>();
        int totalImported = 0;
        int totalSkipped = 0;

        // 先收集同目录下的「参考答案」文件，按考期（年-月）建索引
        Map<String, Map<Integer, String>> keys = collectAnswerKeys(files);

        for (File file : files) {
            if (isAnswerKeyFile(file.getName())) {
                continue;
            }
            Map<Integer, String> key = keys.get(yearMonth(file.getName()));
            Dtos.ImportFileResult r = importOne(file, subject, chapterId, existing, batchId, req.year(), key);
            details.add(r);
            totalImported += r.imported();
            totalSkipped += r.skipped();
        }

        // 重复导入没有新增题目时不保留历史记录，避免历史列表被无意义的批次刷屏。
        boolean hasError = details.stream().anyMatch(d -> "ERROR".equals(d.status()));
        if (totalImported == 0 && !hasError) {
            jdbc.update("delete from import_batch where id=?", batchId);
        } else {
            jdbc.update("update import_batch set status=?, total=?, imported=?, skipped=?, message=? where id=?",
                    "DONE", details.size(), totalImported, totalSkipped,
                    "files=" + details.size(), batchId);
        }

        return new Dtos.ImportResult(details.size(), totalImported, totalSkipped, details);
    }

    @Transactional
    public Dtos.ImportFileResult importOne(File file, Subject subject, Long chapterId,
                                           Set<String> existing, Long batchId, Integer yearOverride,
                                           Map<Integer, String> answerKey) {
        String name = file.getName();
        try {
            TextExtractor.Extracted ex = extractor.extract(file);
            if (ex.text() == null || ex.text().isBlank()) {
                return new Dtos.ImportFileResult(name, "SKIPPED", 0, 0, 0, 0, 0,
                        ex.scanned() ? "扫描件（无文字层），需要 OCR" : "没有可用文本");
            }
            if (ex.scanned()) {
                return new Dtos.ImportFileResult(name, "SKIPPED", 0, 0, 0, 0, 0, "扫描件，需要 OCR");
            }

            PaperParser.ParseResult pr = parser.parse(ex.text());
            int year = yearOverride != null ? yearOverride : extractYear(name);
            int imported = 0;
            int skipped = 0;
            int noAnswer = 0;
            int pending = 0;
            int recovered = 0;
            LocalDateTime now = LocalDateTime.now();
            String source = stripExt(name);

            for (ParsedQuestion pq : pr.questions()) {
                String type = normalizeType(pq);
                String answer = normalizeAnswer(pq, type);

                // 题目里没有答案？看看同目录「参考答案」文件有没有对齐
                if ((answer == null || answer.isBlank()) && answerKey != null) {
                    String fromKey = answerKey.get(pq.getNumber());
                    if (fromKey != null) {
                        pq.setAnswer(fromKey);
                        answer = normalizeAnswer(pq, type);
                        if (answer != null && !answer.isBlank()) {
                            recovered++;
                        }
                    }
                }

                boolean pendingQuestion = false;
                if (answer == null || answer.isBlank()) {
                    noAnswer++;
                    if (!isObjective(type) || !hasStructure(pq, type)) {
                        skipped++;
                        continue;
                    }
                    pendingQuestion = true;
                } else if (!valid(pq, type, answer)) {
                    skipped++;
                    continue;
                }

                String hash = hash(subject.getId(), pq.getStem());
                if (!existing.add(hash)) {
                    // 已有同题：如果旧的是「待补答案」，正好用这次的答案补齐
                    if (!pendingQuestion) {
                        Question old = questionMapper.selectOne(new LambdaQueryWrapper<Question>()
                                .eq(Question::getHash, hash).last("limit 1"));
                        if (old != null && "NO_ANSWER".equals(old.getStatus())) {
                            old.setAnswer(answer);
                            old.setStatus("ACTIVE");
                            if (pq.getAnalysis() != null && !pq.getAnalysis().isBlank()) {
                                old.setAnalysis(pq.getAnalysis().strip());
                            }
                            old.setUpdatedAt(now);
                            questionMapper.updateById(old);
                            imported++;
                            recovered++;
                        }
                    }
                    skipped++;
                    continue;
                }

                Question q = new Question();
                q.setSubjectId(subject.getId());
                q.setChapterId(chapterId);
                q.setType(type);
                q.setStem(cleanStem(pq.getStem()));
                q.setOptions(Json.write(pq.getOptions().stream()
                        .map(o -> new Dtos.Option(o.getKey(), com.quizmate.common.Texts.normalize(o.getText())))
                        .toList()));
                q.setAnswer(pendingQuestion ? "" : answer);
                q.setAnalysis(pq.getAnalysis() == null ? null : com.quizmate.common.Texts.normalize(pq.getAnalysis()));
                q.setDifficulty(3);
                q.setScore(defaultScore(type));
                q.setSource(source);
                q.setYear(year > 0 ? year : null);
                q.setTags(pq.getTags().isEmpty() ? null : Json.write(pq.getTags()));
                q.setHash(hash);
                q.setStatus(pendingQuestion ? "NO_ANSWER" : "ACTIVE");
                q.setImportBatchId(batchId);
                q.setQno(pq.getNumber());
                q.setCreatedAt(now);
                q.setUpdatedAt(now);
                questionMapper.insert(q);
                if (pendingQuestion) {
                    pending++;
                }
                imported++;
            }

            String msg = "解析 " + pr.questions().size() + " 题"
                    + (recovered > 0 ? "，答案对齐 " + recovered : "")
                    + (pending > 0 ? "，待补答案 " + pending : "")
                    + (noAnswer > 0 ? "，无答案 " + noAnswer : "")
                    + (pr.warnings().isEmpty() ? "" : "；" + String.join("；", pr.warnings()));
            return new Dtos.ImportFileResult(name, imported > 0 ? "OK" : "EMPTY",
                    pr.questions().size(), imported, skipped, noAnswer, pending, msg);
        } catch (Throwable e) {
            log.warn("导入失败 {}", name, e);
            return new Dtos.ImportFileResult(name, "ERROR", 0, 0, 0, 0, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /* ---------- 答案对齐 ---------- */

    private Map<String, Map<Integer, String>> collectAnswerKeys(List<File> files) {
        Map<String, Map<Integer, String>> keys = new LinkedHashMap<>();
        for (File f : files) {
            if (!isAnswerKeyFile(f.getName())) {
                continue;
            }
            try {
                TextExtractor.Extracted ex = extractor.extract(f);
                if (ex.text() == null || ex.text().isBlank()) {
                    continue;
                }
                Map<Integer, String> map = answerKeyParser.parse(ex.text());
                if (map.size() >= 5) {
                    keys.put(yearMonth(f.getName()), map);
                    log.info("答案文件 {} 解析出 {} 条答案", f.getName(), map.size());
                }
            } catch (Exception e) {
                log.warn("答案文件解析失败 {}", f.getName(), e);
            }
        }
        return keys;
    }

    /** 「参考答案.pdf」这类纯答案文件；带真题/试题/解析的是完整试卷，不能跳过 */
    public boolean isAnswerKeyFile(String name) {
        if (!name.contains("答案")) {
            return false;
        }
        return !(name.contains("真题") || name.contains("试题") || name.contains("试卷")
                || name.contains("题目") || name.contains("解析"));
    }

    /** 从文件名提取考期，如 2024-10 / 2017-10 */
    private String yearMonth(String name) {
        Matcher m = Pattern.compile("(20\\d{2})\\s*[.\\-年_]?\\s*(\\d{1,2})?").matcher(name);
        if (!m.find()) {
            return "";
        }
        int month = 0;
        if (m.group(2) != null) {
            try {
                month = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
            }
        }
        if (month < 1 || month > 12) {
            month = 0;
        }
        return m.group(1) + "-" + month;
    }

    private boolean isObjective(String type) {
        return "SINGLE".equals(type) || "MULTIPLE".equals(type) || "JUDGE".equals(type);
    }

    private boolean hasStructure(ParsedQuestion pq, String type) {
        if (pq.getStem() == null || pq.getStem().strip().length() < 4) {
            return false;
        }
        return !("SINGLE".equals(type) || "MULTIPLE".equals(type)) || pq.hasOptions();
    }

    private Subject resolveSubject(Dtos.ImportRequest req) {
        if (req.subjectId() != null) {
            Subject s = subjectMapper.selectById(req.subjectId());
            if (s == null) {
                throw new BizException("学科不存在：" + req.subjectId());
            }
            return s;
        }
        if (req.subjectName() == null || req.subjectName().isBlank()) {
            throw new BizException("请指定学科");
        }
        Subject exist = subjectMapper.selectOne(new LambdaQueryWrapper<Subject>()
                .eq(Subject::getName, req.subjectName()).last("limit 1"));
        if (exist != null) {
            return exist;
        }
        Subject s = new Subject();
        s.setMajorId(req.majorId());
        s.setName(req.subjectName());
        s.setColor("#4F46E5");
        s.setSort(99);
        s.setCreatedAt(LocalDateTime.now());
        subjectMapper.insert(s);
        return s;
    }

    private Long createBatch(Long subjectId, File dir) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into import_batch(subject_id, file_name, file_path, parser, status, created_at)
                    values (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, subjectId);
            ps.setString(2, dir.getName());
            ps.setString(3, dir.getAbsolutePath());
            ps.setString(4, "PaperParser");
            ps.setString(5, "RUNNING");
            ps.setString(6, LocalDateTime.now().toString());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BizException("创建导入批次失败");
        }
        return key.longValue();
    }

    private Long defaultChapter(Long subjectId) {
        Chapter c = chapterMapper.selectOne(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getSubjectId, subjectId).eq(Chapter::getName, "未分类").last("limit 1"));
        if (c != null) {
            return c.getId();
        }
        c = new Chapter();
        c.setSubjectId(subjectId);
        c.setName("未分类");
        c.setPath("/");
        c.setDepth(0);
        c.setSort(999);
        chapterMapper.insert(c);
        return c.getId();
    }

    private Set<String> loadHashes(Long subjectId) {
        List<String> hashes = jdbc.queryForList(
                "select hash from question where subject_id=? and hash is not null", String.class, subjectId);
        return new HashSet<>(hashes);
    }

    private String normalizeType(ParsedQuestion pq) {
        String t = pq.getType();
        if (t == null) {
            t = pq.hasOptions() ? "SINGLE" : "SHORT";
        }
        if ("SINGLE".equals(t) && pq.getAnswer() != null
                && pq.getAnswer().replaceAll("[^A-Fa-f]", "").length() > 1) {
            return "MULTIPLE";
        }
        return t;
    }

    private String normalizeAnswer(ParsedQuestion pq, String type) {
        String a = pq.getAnswer() == null ? "" : pq.getAnswer().strip();
        if (a.isEmpty()) {
            return null;
        }
        switch (type) {
            case "SINGLE", "MULTIPLE" -> {
                String letters = a.replaceAll("[^A-Fa-f]", "").toUpperCase(Locale.ROOT);
                return letters.isEmpty() ? null : letters;
            }
            case "JUDGE" -> {
                String v = a.replaceAll("[\\s。.]", "");
                if (v.matches("^(对|正确|√|T|true|True|是)$")) {
                    return "对";
                }
                if (v.matches("^(错|错误|×|F|false|False|否)$")) {
                    return "错";
                }
                return null;
            }
            default -> {
                String v = a.strip();
                return v.isEmpty() ? null : v;
            }
        }
    }

    private boolean valid(ParsedQuestion pq, String type, String answer) {
        if (pq.getStem() == null || pq.getStem().strip().length() < 4) {
            return false;
        }
        if ("SINGLE".equals(type) || "MULTIPLE".equals(type)) {
            if (!pq.hasOptions()) {
                return false;
            }
            Set<String> keys = new HashSet<>();
            pq.getOptions().forEach(o -> keys.add(o.getKey()));
            for (char c : answer.toCharArray()) {
                if (!keys.contains(String.valueOf(c))) {
                    return false;
                }
            }
            if ("SINGLE".equals(type) && answer.length() != 1) {
                return false;
            }
            return true;
        }
        if ("JUDGE".equals(type)) {
            return "对".equals(answer) || "错".equals(answer);
        }
        return answer.length() >= 1;
    }

    private String cleanStem(String stem) {
        String s = stem == null ? "" : stem.strip();
        s = s.replaceAll("^[\\s　]+", "").replaceAll("[\\s　]+$", "");
        return com.quizmate.common.Texts.normalize(s);
    }

    private double defaultScore(String type) {
        return switch (type) {
            case "SINGLE", "JUDGE" -> 1;
            case "MULTIPLE" -> 2;
            case "FILL" -> 2;
            case "SHORT" -> 4;
            case "ESSAY" -> 10;
            default -> 3;
        };
    }

    private int extractYear(String name) {
        Matcher m = YEAR.matcher(name);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            if (y >= 1990 && y <= 2100) {
                return y;
            }
        }
        return 0;
    }

    private String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private String ext(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i) : "";
    }

    private String hash(Long subjectId, String stem) {
        return Hashes.question(subjectId, stem);
    }
}
