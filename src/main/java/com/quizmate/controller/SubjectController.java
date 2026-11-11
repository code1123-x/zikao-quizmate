package com.quizmate.controller;

import com.quizmate.domain.Subject;
import com.quizmate.dto.Dtos;
import com.quizmate.service.SubjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/subjects")
public class SubjectController {

    private final SubjectService subjectService;

    public SubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    @GetMapping
    public List<Dtos.SubjectView> list(@RequestParam(required = false) Long majorId) {
        return subjectService.list(majorId);
    }

    @PostMapping
    public Subject create(@RequestBody Dtos.CreateSubjectRequest req) {
        return subjectService.create(req);
    }

    @PutMapping("/{id}")
    public Subject update(@PathVariable Long id, @RequestBody Dtos.UpdateSubjectRequest req) {
        return subjectService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        subjectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/chapters")
    public List<Dtos.ChapterView> chapters(@PathVariable Long id) {
        return subjectService.chapters(id);
    }
}
