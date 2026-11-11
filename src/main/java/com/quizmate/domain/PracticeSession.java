package com.quizmate.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("practice_session")
public class PracticeSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long subjectId;
    private String mode;
    /** JSON config */
    private String config;
    /** JSON array of question ids */
    private String questionIds;
    private Integer cursor;
    private String status;
    private Integer correctCount;
    private Integer totalCount;
    private Long costMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
