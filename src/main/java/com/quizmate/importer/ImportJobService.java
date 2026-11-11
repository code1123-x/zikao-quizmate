package com.quizmate.importer;

import com.quizmate.common.BizException;
import com.quizmate.common.CurrentUser;
import com.quizmate.dto.Dtos;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台导入任务。一次只跑一个任务，避免多个导入同时抢 SQLite 写锁。
 * jobId 直接复用 import_batch.id，页面刷新后仍能通过接口查到进度。
 */
@Slf4j
@Service
@DependsOnDatabaseInitialization
public class ImportJobService {

    private static final String RUNNING = "RUNNING";
    private static final String DONE = "DONE";
    private static final String CANCELLED = "CANCELLED";
    private static final String FAILED = "FAILED";

    private final ImportService importService;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "import-job");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    /** 每个账号同时只允许一个导入，但不同账号可以并行。 */
    private final Map<Long, Long> runningByUser = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public ImportJobService(ImportService importService, JdbcTemplate jdbc) {
        this.importService = importService;
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void recoverAbandonedJobs() {
        try {
            int n = jdbc.update("""
                    update import_batch
                       set status = 'FAILED',
                           message = coalesce(message, '') || '（应用重启，导入任务已中断）'
                     where status = 'RUNNING'
                    """);
            if (n > 0) {
                log.warn("已将 {} 个上次未完成的导入任务标记为失败", n);
            }
        } catch (Exception e) {
            log.warn("恢复中断的导入任务失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    public Dtos.ImportJobView start(Dtos.ImportRequest req) {
        synchronized (lock) {
            long userId = CurrentUser.id();
            if (runningByUser.containsKey(userId)) {
                throw new BizException("当前账号已有导入任务在运行，请等它结束或先取消");
            }
            ImportService.ImportContext ctx = importService.prepare(req);
            int realFiles = (int) ctx.files().stream()
                    .filter(f -> !importService.isAnswerKeyFile(f.getName()))
                    .count();
            Job job = new Job(ctx, realFiles, CurrentUser.get());
            jobs.put(job.id, job);
            // 只保留最近的已完成任务，避免长时间运行后内存里堆太多明细
            if (jobs.size() > 20) {
                jobs.entrySet().removeIf(e -> e.getValue().finishedAt != null && !e.getKey().equals(job.id));
            }
            runningByUser.put(userId, job.id);
            try {
                pool.submit(() -> run(job));
            } catch (RuntimeException e) {
                runningByUser.remove(userId, job.id);
                job.status = FAILED;
                job.message = "启动导入任务失败：" + e.getMessage();
                job.finishedAt = LocalDateTime.now();
                jobs.remove(job.id);
                try {
                    importService.updateBatch(job.id, FAILED, job.totalFiles, 0, 0, job.message);
                } catch (Exception ignored) {
                    // 启动失败时即使写不回批次状态，也要把异常抛给调用方
                }
                throw new BizException(job.message);
            }
            return view(job);
        }
    }

    public Dtos.ImportJobView get(Long id) {
        Job job = jobs.get(id);
        if (job != null) {
            if (job.user != null && job.user.id() != CurrentUser.id()) {
                throw new BizException("导入任务不存在");
            }
            return view(job);
        }
        // 应用重启后内存里没有任务，从 import_batch 回退一个概要状态
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select b.id, b.subject_id, s.name as subject_name, b.file_path, b.status,
                       b.total, b.imported, b.skipped, b.message, b.created_at
                  from import_batch b left join subject s on s.id = b.subject_id
                 where b.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BizException("导入任务不存在");
        }
        Map<String, Object> row = rows.get(0);
        String status = str(row.get("status"));
        int total = number(row.get("total"));
        int done = DONE.equals(status) || "ROLLED_BACK".equals(status) ? total : 0;
        return new Dtos.ImportJobView(
                id,
                row.get("subject_id") == null ? null : ((Number) row.get("subject_id")).longValue(),
                str(row.get("subject_name")),
                str(row.get("file_path")),
                status,
                total,
                done,
                "",
                number(row.get("imported")),
                number(row.get("skipped")),
                0,
                str(row.get("message")),
                str(row.get("created_at")),
                null,
                List.of()
        );
    }

    public Dtos.ImportJobView cancel(Long id) {
        Job job = jobs.get(id);
        if (job != null && job.user != null && job.user.id() != CurrentUser.id()) {
            throw new BizException("导入任务不存在");
        }
        if (job == null) {
            return get(id);
        }
        if (RUNNING.equals(job.status)) {
            job.cancelRequested = true;
            job.message = "正在取消（当前文件处理完就停止）…";
        }
        return view(job);
    }

    private void run(Job job) {
        CurrentUser.set(job.user);
        boolean hasError = false;
        try {
            importService.updateBatch(job.id, RUNNING, job.totalFiles, 0, 0, "开始导入…");
            for (File file : job.ctx.files()) {
                if (job.cancelRequested) {
                    job.status = CANCELLED;
                    job.message = "导入已取消（已处理 " + job.doneFiles + "/" + job.totalFiles + " 个文件）";
                    break;
                }
                job.currentFile = file.getName();
                if (importService.isAnswerKeyFile(file.getName())) {
                    // 答案文件只用于对齐，不计入“待导入文档”进度
                    continue;
                }

                Dtos.ImportFileResult result = importService.importOne(job.ctx, file);
                job.details.add(result);
                if ("ERROR".equals(result.status())) {
                    hasError = true;
                }
                job.imported += result.imported();
                job.skipped += result.skipped();
                job.pending += result.pending();
                job.doneFiles++;
                job.message = progressMessage(job);
                importService.updateBatch(job.id, RUNNING, job.totalFiles,
                        job.imported, job.skipped, job.message);
            }

            if (RUNNING.equals(job.status)) {
                job.status = DONE;
                if (job.imported == 0 && !hasError) {
                    // 重复导入没有新增题：不保留这次批次，避免历史列表出现一条空记录。
                    jdbc.update("delete from import_batch where id=?", job.id);
                    job.message = "没有新增题目，重复导入不会新增导入历史";
                } else {
                    job.message = "导入完成：新增 " + job.imported + " 题，跳过 " + job.skipped
                            + (job.pending > 0 ? "，待补答案 " + job.pending : "");
                }
            }
        } catch (Throwable e) {
            log.error("导入任务失败 id={}", job.id, e);
            job.status = FAILED;
            job.message = "导入失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            job.currentFile = "";
            job.finishedAt = LocalDateTime.now();
            try {
                importService.updateBatch(job.id, job.status, job.totalFiles,
                        job.imported, job.skipped, job.message);
            } catch (Exception e) {
                log.warn("写回导入任务状态失败 id={}", job.id, e);
            } finally {
                CurrentUser.clear();
            }
            if (job.user != null) {
                runningByUser.remove(job.user.id(), job.id);
            }
        }
    }

    private static String progressMessage(Job job) {
        return "已处理 " + job.doneFiles + "/" + job.totalFiles + " 个文件"
                + (job.currentFile == null || job.currentFile.isBlank() ? "" : "，当前：" + job.currentFile);
    }

    private Dtos.ImportJobView view(Job job) {
        List<Dtos.ImportFileResult> details;
        synchronized (job.details) {
            details = List.copyOf(job.details);
        }
        return new Dtos.ImportJobView(
                job.id,
                job.subjectId,
                job.subjectName,
                job.ctx.dir().getAbsolutePath(),
                job.status,
                job.totalFiles,
                job.doneFiles,
                job.currentFile,
                job.imported,
                job.skipped,
                job.pending,
                job.message,
                job.startedAt.toString(),
                job.finishedAt == null ? null : job.finishedAt.toString(),
                details
        );
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static final class Job {
        final Long id;
        final Long subjectId;
        final String subjectName;
        final ImportService.ImportContext ctx;
        final int totalFiles;
        final CurrentUser.Principal user;
        final LocalDateTime startedAt = LocalDateTime.now();
        final List<Dtos.ImportFileResult> details =
                Collections.synchronizedList(new ArrayList<>());

        volatile String status = RUNNING;
        volatile int doneFiles;
        volatile int imported;
        volatile int skipped;
        volatile int pending;
        volatile String currentFile = "";
        volatile String message = "准备导入…";
        volatile boolean cancelRequested;
        volatile LocalDateTime finishedAt;

        Job(ImportService.ImportContext ctx, int totalFiles, CurrentUser.Principal user) {
            this.ctx = ctx;
            this.id = ctx.batchId();
            this.subjectId = ctx.subject().getId();
            this.subjectName = ctx.subject().getName();
            this.totalFiles = totalFiles;
            this.user = user;
        }
    }
}
