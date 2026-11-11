package com.quizmate.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subject")
public class Subject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long majorId;
    private String code;
    private String name;
    private String color;
    private String examDate;
    private Integer sort;
    private LocalDateTime createdAt;
}
