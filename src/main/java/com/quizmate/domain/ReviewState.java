package com.quizmate.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_state")
public class ReviewState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String itemType;
    private Long itemId;
    private Double ease;
    private Integer intervalDays;
    private Integer reps;
    private Integer lapses;
    private LocalDateTime dueAt;
    private String lastResult;
    private LocalDateTime updatedAt;

    public static final String QUESTION = "QUESTION";
    public static final String KNOWLEDGE = "KNOWLEDGE";
}
