package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.importer.AnswerImageService;
import com.quizmate.importer.AnswerOcrService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/answer-images")
public class AnswerImageController {

    private final AnswerImageService service;
    private final AnswerOcrService ocr;

    public AnswerImageController(AnswerImageService service, AnswerOcrService ocr) {
        this.service = service;
        this.ocr = ocr;
    }

    @GetMapping
    public List<String> list(@RequestParam String source) {
        return service.urls(source);
    }

    /** 自动识别答案页里的题号 + 答案 */
    @GetMapping("/ocr")
    public Dtos.OcrResult ocr(@RequestParam String source,
                              @RequestParam(required = false) Long subjectId,
                              @RequestParam(required = false, defaultValue = "false") boolean loose) {
        return ocr.recognize(source, subjectId, loose);
    }

    @GetMapping("/{key}/{name}")
    public ResponseEntity<byte[]> image(@PathVariable String key, @PathVariable String name) {
        Path p = service.fileByKey(key, name);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(p);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
