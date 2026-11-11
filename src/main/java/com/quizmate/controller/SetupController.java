package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.service.SetupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/setup/status")
    public Dtos.SetupStatus status(@RequestParam(required = false) Long majorId) {
        return setupService.status(majorId);
    }

    @PostMapping("/setup/complete")
    public Dtos.SetupCompleteResult complete(@RequestBody Dtos.SetupCompleteRequest req) {
        return setupService.complete(req);
    }

    @GetMapping("/exam-plan")
    public Dtos.ExamPlan plan(@RequestParam(required = false) Long majorId) {
        return setupService.plan(majorId);
    }
}
