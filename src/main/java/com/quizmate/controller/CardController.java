package com.quizmate.controller;

import com.quizmate.dto.Dtos;
import com.quizmate.service.CardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping("/summary")
    public List<Dtos.CardSummary> summary(@RequestParam(required = false) Long majorId) {
        return cardService.summary(majorId);
    }

    @GetMapping
    public List<Dtos.CardView> list(@RequestParam Long subjectId,
                                    @RequestParam(defaultValue = "50") int limit) {
        return cardService.list(subjectId, Math.max(1, Math.min(limit, 200)));
    }

    @PostMapping("/{id}/review")
    public Dtos.CardReviewResult review(@PathVariable Long id,
                                        @RequestBody Dtos.CardReviewRequest req) {
        return cardService.review(id, req.rating());
    }
}
