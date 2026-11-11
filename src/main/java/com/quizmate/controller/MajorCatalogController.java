package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.service.MajorCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/majors/catalog")
public class MajorCatalogController {

    private final MajorCatalogService service;

    public MajorCatalogController(MajorCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public Dtos.MajorCatalogView catalog(@RequestParam(required = false) String province,
                                         @RequestParam(required = false) String level) {
        return service.catalog(province, level);
    }

    @GetMapping("/coverage")
    public List<Dtos.MajorCatalogCoverage> coverage() {
        return service.coverage();
    }
}
