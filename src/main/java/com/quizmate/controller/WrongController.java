package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.service.WrongService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/wrong")
public class WrongController {

    private final WrongService wrongService;

    public WrongController(WrongService wrongService) {
        this.wrongService = wrongService;
    }

    @GetMapping("/summary")
    public List<Dtos.WrongSubject> summary(@RequestParam(required = false) Long majorId) {
        return wrongService.summary(majorId);
    }

    @GetMapping
    public Dtos.WrongPage list(@RequestParam Long subjectId,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "recent") String sort,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return wrongService.list(subjectId, type, q, sort, page, size);
    }
}
