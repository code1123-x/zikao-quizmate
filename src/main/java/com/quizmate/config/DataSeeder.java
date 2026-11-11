package com.quizmate.config;

import com.quizmate.domain.Subject;
import com.quizmate.mapper.SubjectMapper;
import com.quizmate.service.MajorService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 旧的默认科目种子。现在首次进入走「设置向导」，不再自动塞 14 门学科，
 * 否则用户会被不属于自己专业的课淹没。确需恢复旧行为时设置
 * quizmate.seed-defaults=true 即可。
 */
@Component
@ConditionalOnProperty(name = "quizmate.seed-defaults", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private final SubjectMapper subjectMapper;
    private final MajorService majorService;

    public DataSeeder(SubjectMapper subjectMapper, MajorService majorService) {
        this.subjectMapper = subjectMapper;
        this.majorService = majorService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (subjectMapper.selectCount(null) > 0) {
            return;
        }
        Long majorId = majorService.ensureDefault();
        record Seed(String name, String color) {
        }
        List<Seed> seeds = List.of(
                new Seed("马克思主义基本原理", "#4F46E5"),
                new Seed("中国近现代史纲要", "#0EA5E9"),
                new Seed("英语（专升本）", "#F59E0B"),
                new Seed("离散数学", "#10B981"),
                new Seed("高等数学（工本）", "#EF4444"),
                new Seed("操作系统", "#8B5CF6"),
                new Seed("数据结构与算法", "#06B6D4"),
                new Seed("数据库系统原理", "#F97316"),
                new Seed("计算机系统原理", "#84CC16"),
                new Seed("计算机网络与信息安全", "#EC4899"),
                new Seed("计算机网络安全", "#14B8A6"),
                new Seed("高级语言程序设计", "#6366F1"),
                new Seed("互联网软件应用与开发", "#A855F7"),
                new Seed("习近平新时代中国特色社会主义思想概论", "#E11D48")
        );
        int sort = 0;
        for (Seed s : seeds) {
            Subject subject = new Subject();
            subject.setMajorId(majorId);
            subject.setName(s.name());
            subject.setColor(s.color());
            subject.setSort(sort++);
            subject.setCreatedAt(LocalDateTime.now());
            subjectMapper.insert(subject);
        }
    }
}
