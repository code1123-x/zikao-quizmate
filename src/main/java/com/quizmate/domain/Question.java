package com.quizmate.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("question")
public class Question {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    private Long chapterId;
    private String type;
    private String stem;
    /** JSON: [{"key":"A","text":"..."}] */
    private String options;
    private String answer;
    private String analysis;
    private Integer difficulty;
    private Double score;
    private String source;
    private Integer year;
    /** JSON array */
    private String tags;
    private String hash;
    private String status;
    private Long importBatchId;
    /** 试卷里的题号（1,2,3…），用于和答案页对齐 */
    private Integer qno;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
