package com.quizmate.controller;

import com.quizmate.domain.Major;
import com.quizmate.dto.Dtos;
import com.quizmate.service.MajorCourseService;
import com.quizmate.service.MajorService;
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
@RequestMapping("/api/majors")
public class MajorController {

    private final MajorService majorService;
    private final MajorCourseService courseService;

    public MajorController(MajorService majorService, MajorCourseService courseService) {
        this.majorService = majorService;
        this.courseService = courseService;
    }

    @GetMapping
    public List<Dtos.MajorView> list() {
        majorService.ensureDefault();
        return majorService.list();
    }

    @GetMapping("/courses")
    public Dtos.MajorCourseView courses(@RequestParam String province,
                                        @RequestParam(required = false) String level,
                                        @RequestParam(required = false) String code,
                                        @RequestParam(required = false) String name) {
        return courseService.courses(province, level, code, name);
    }

    @PostMapping
    public Major create(@RequestBody Dtos.CreateMajorRequest req) {
        return majorService.create(req);
    }

    @PutMapping("/{id}")
    public Major update(@PathVariable Long id, @RequestBody Dtos.UpdateMajorRequest req) {
        return majorService.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        majorService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
