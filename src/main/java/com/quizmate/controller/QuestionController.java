package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.service.QuestionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping
    public Dtos.QuestionPage page(@RequestParam(required = false) Long subjectId,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) Integer year,
                                  @RequestParam(required = false) String source,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return questionService.page(subjectId, type, status, q, year, source, page, size);
    }

    @PatchMapping("/{id}")
    public Dtos.QuestionView update(@PathVariable Long id, @RequestBody Dtos.QuestionUpdate req) {
        return questionService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        questionService.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/delete")
    public Map<String, Object> deleteMany(@RequestBody Dtos.DeleteQuestionsRequest req) {
        return Map.of("deleted", questionService.deleteMany(req.ids()));
    }

    @GetMapping("/pending")
    public List<Dtos.QuestionView> pending(@RequestParam Long subjectId,
                                           @RequestParam(required = false) String source,
                                           @RequestParam(defaultValue = "20") int limit) {
        return questionService.pending(subjectId, source, limit);
    }

    @GetMapping("/pending/sources")
    public List<Dtos.PendingSource> pendingSources(@RequestParam Long subjectId) {
        return questionService.pendingSources(subjectId);
    }

    @PostMapping("/{id}/answer")
    public Map<String, Object> setAnswer(@PathVariable Long id, @RequestBody Dtos.SetAnswerRequest req) {
        questionService.setAnswer(id, req.answer());
        return Map.of("ok", true);
    }

    @PostMapping("/batch-answer")
    public Dtos.BatchAnswerResult batchAnswer(@RequestBody Dtos.BatchAnswerRequest req) {
        return questionService.batchAnswer(req.subjectId(), req.source(), req.answers(),
                Boolean.TRUE.equals(req.fromOne()));
    }
}
