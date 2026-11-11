package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.service.PracticeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    @PostMapping("/sessions")
    public Dtos.SessionView create(@RequestBody Dtos.CreateSessionRequest req) {
        return practiceService.create(req);
    }

    @GetMapping("/options")
    public Dtos.BuildOptions options(@RequestParam Long subjectId) {
        return practiceService.options(subjectId);
    }

    @PostMapping("/review")
    public Dtos.SessionView review(@RequestParam(defaultValue = "30") int limit,
                                   @RequestParam(required = false) Long majorId,
                                   @RequestParam(required = false) Long subjectId) {
        return practiceService.startReview(limit, majorId, subjectId);
    }

    @GetMapping("/sessions/{id}")
    public Dtos.SessionView get(@PathVariable Long id) {
        return practiceService.get(id);
    }

    @PostMapping("/sessions/{id}/answers")
    public Dtos.AnswerResult answer(@PathVariable Long id, @RequestBody Dtos.AnswerRequest req) {
        return practiceService.answer(id, req);
    }

    @PostMapping("/sessions/{id}/finish")
    public Dtos.ResultView finish(@PathVariable Long id) {
        return practiceService.finish(id);
    }

    @GetMapping("/sessions/{id}/result")
    public Dtos.ResultView result(@PathVariable Long id) {
        return practiceService.result(id);
    }
}
