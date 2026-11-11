package com.quizmate.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 专业：一个专业下有多门学科，不同专业的人只看自己的学科。
 */
@Data
@TableName("major")
public class Major {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String color;
    /** 考试所在省份，不同省份的考试计划和科目会不一样 */
    private String province;
    /** 专科 / 本科 */
    private String level;
    private Integer sort;
    private LocalDateTime createdAt;
}
