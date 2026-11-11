package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.importer.ImportJobService;
import com.quizmate.importer.ImportService;
import com.quizmate.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MiscController {

    private final StatsService statsService;
    private final ImportService importService;
    private final ImportJobService importJobService;

    public MiscController(StatsService statsService, ImportService importService,
                          ImportJobService importJobService) {
        this.statsService = statsService;
        this.importService = importService;
        this.importJobService = importJobService;
    }

    @GetMapping("/stats/overview")
    public Dtos.Overview overview(@RequestParam(required = false) Long majorId) {
        return statsService.overview(majorId);
    }

    @GetMapping("/stats/report")
    public Dtos.StatsReport report(@RequestParam(defaultValue = "14") int days,
                                   @RequestParam(required = false) Long majorId) {
        return statsService.report(days, majorId);
    }

    @PostMapping("/import/dir")
    public Dtos.ImportResult importDir(@RequestBody Dtos.ImportRequest req) {
        return importService.importDirectory(req);
    }

    /** 小程序 / 手机上传文件导入。文件保存在当前账号 raw/上传导入/ 下。 */
    @PostMapping("/import/upload")
    public Dtos.ImportResult importUpload(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) Long subjectId,
                                          @RequestParam(required = false) String subjectName,
                                          @RequestParam(required = false) Integer year,
                                          @RequestParam(required = false) Long majorId) {
        return importService.importUpload(file, subjectId, subjectName, year, majorId);
    }

    /** 后台导入：立即返回任务，前端轮询进度；一次只允许一个任务。 */
    @PostMapping("/import/jobs")
    public Dtos.ImportJobView startImportJob(@RequestBody Dtos.ImportRequest req) {
        return importJobService.start(req);
    }

    @GetMapping("/import/jobs/{id}")
    public Dtos.ImportJobView importJob(@PathVariable Long id) {
        return importJobService.get(id);
    }

    @PostMapping("/import/jobs/{id}/cancel")
    public Dtos.ImportJobView cancelImportJob(@PathVariable Long id) {
        return importJobService.cancel(id);
    }

    @GetMapping("/import/preview")
    public List<Map<String, Object>> preview(@RequestParam String path,
                                             @RequestParam(defaultValue = "10") int limit) throws Exception {
        return importService.preview(path, limit);
    }

    @GetMapping("/import/browse")
    public Dtos.FsListing browse(@RequestParam(required = false) String path) {
        return importService.browse(path);
    }

    @PostMapping("/import/scan")
    public Dtos.ScanResult scan(@RequestBody Dtos.ImportScanRequest req) {
        return importService.scan(req);
    }

    @GetMapping("/import/batches")
    public List<Dtos.ImportBatchView> batches(@RequestParam(required = false) Long subjectId) {
        return importService.batches(subjectId);
    }

    @GetMapping("/import/history")
    public Dtos.ImportHistoryPage history(@RequestParam(required = false) Long subjectId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return importService.history(subjectId, status, q, page, size);
    }

    @PostMapping("/import/rollback/{batchId}")
    public Map<String, Object> rollback(@PathVariable Long batchId) {
        return Map.of("deleted", importService.rollback(batchId));
    }

    @PostMapping("/import/rollback-group")
    public Map<String, Object> rollbackGroup(@RequestBody Dtos.ImportRollbackGroupRequest req) {
        return Map.of("deleted", importService.rollbackGroup(req.subjectId(), req.path()));
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
