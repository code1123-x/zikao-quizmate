package com.quizmate.controller;

import com.quizmate.ai.AiCheckService;
import com.quizmate.ai.AiClient;
import com.quizmate.ai.AiConfigStore;
import com.quizmate.common.BizException;
import com.quizmate.dto.Dtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiConfigStore config;
    private final AiClient client;
    private final AiCheckService checks;

    public AiController(AiConfigStore config, AiClient client, AiCheckService checks) {
        this.config = config;
        this.client = client;
        this.checks = checks;
    }

    @GetMapping("/config")
    public Dtos.AiConfigView getConfig() {
        AiConfigStore.Settings s = config.get();
        String key = s.apiKey() == null ? "" : s.apiKey();
        String masked = key.isBlank() ? ""
                : key.length() <= 8 ? "****" : key.substring(0, 4) + "…" + key.substring(key.length() - 4);
        return new Dtos.AiConfigView(s.provider(), s.baseUrl(), s.model(),
                !key.isBlank() || s.local(), masked, s.thinking(),
                "DeepSeek 需充值（很便宜）；智谱 GLM-4-Flash 免费；SiliconFlow 有免费模型；Ollama 本地完全免费");
    }

    @PutMapping("/config")
    public Dtos.AiConfigView save(@RequestBody Dtos.AiConfigUpdate req) {
        AiConfigStore.Settings old = config.get();
        String key = req.apiKey() == null || req.apiKey().isBlank() ? old.apiKey() : req.apiKey().strip();
        String provider = blank(req.provider()) ? old.provider() : req.provider().strip();
        String baseUrl = blank(req.baseUrl()) ? old.baseUrl() : req.baseUrl().strip();
        String model = blank(req.model()) ? old.model() : req.model().strip();
        boolean thinking = req.thinking() == null ? old.thinking() : req.thinking();
        config.save(new AiConfigStore.Settings(provider, baseUrl, key, model, thinking));
        return getConfig();
    }

    @PostMapping("/test")
    public Dtos.AiTestResult test() {
        try {
            String out = client.chat("你是一个简洁的助手。", "请只回复两个字：正常");
            return new Dtos.AiTestResult(true, "连接成功", out.strip());
        } catch (BizException e) {
            return new Dtos.AiTestResult(false, e.getMessage(), null);
        } catch (Exception e) {
            return new Dtos.AiTestResult(false, e.getMessage(), null);
        }
    }

    @PostMapping("/checks/start")
    public Dtos.AiCheckStartResult start(@RequestBody Dtos.AiCheckStartRequest req) {
        return checks.start(req);
    }

    @PostMapping("/check-one")
    public Dtos.AiCheckOneResult checkOne(@RequestBody Dtos.AiCheckOneRequest req) {
        return checks.checkOne(req.questionId(), req.answer());
    }

    @PostMapping("/analysis")
    public Dtos.AiAnalysisResult analysis(@RequestBody Dtos.AiAnalysisRequest req) {
        return checks.analysis(req.questionId());
    }

    @GetMapping("/checks/progress")
    public Dtos.AiProgress progress() {
        return checks.progress();
    }

    @GetMapping("/check-stats")
    public List<Dtos.AiSubjectStat> stats(@RequestParam(required = false) Long majorId) {
        return checks.stats(majorId);
    }

    @GetMapping("/checks")
    public List<Dtos.AiCheckView> list(@RequestParam(required = false) Long subjectId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String verdict,
                                       @RequestParam(defaultValue = "100") int limit) {
        return checks.list(subjectId, status == null || status.isBlank() ? "NEW" : status, verdict, limit);
    }

    @PostMapping("/checks/{questionId}/apply")
    public Dtos.QuestionView apply(@PathVariable Long questionId) {
        return checks.apply(questionId);
    }

    @PostMapping("/checks/{questionId}/ignore")
    public void ignore(@PathVariable Long questionId) {
        checks.ignore(questionId);
    }

    @PostMapping("/checks/apply-all")
    public Dtos.AiApplyAllResult applyAll(@RequestParam(required = false) Long subjectId,
                                          @RequestBody(required = false) Dtos.AiApplyAllRequest req) {
        Long sid = req != null && req.subjectId() != null ? req.subjectId() : subjectId;
        List<Long> ids = req == null ? null : req.questionIds();
        return checks.applyAll(sid, ids);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
