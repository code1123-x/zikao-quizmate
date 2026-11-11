package com.quizmate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quizmate.domain.ReviewState;
import com.quizmate.mapper.ReviewStateMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * SM-2 简化的间隔重复。题目和知识点共用同一套调度。
 */
@Service
public class ReviewService {

    private final ReviewStateMapper reviewMapper;

    public ReviewService(ReviewStateMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    /**
     * @param correct 答对/记住了
     * @param vague   模糊（间隔不变，难度略增）
     */
    public ReviewState apply(String itemType, Long itemId, boolean correct, boolean vague) {
        ReviewState rs = reviewMapper.selectOne(new LambdaQueryWrapper<ReviewState>()
                .eq(ReviewState::getUserId, 1L)
                .eq(ReviewState::getItemType, itemType)
                .eq(ReviewState::getItemId, itemId)
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (rs == null) {
            rs = new ReviewState();
            rs.setUserId(1L);
            rs.setItemType(itemType);
            rs.setItemId(itemId);
            rs.setEase(2.5);
            rs.setIntervalDays(0);
            rs.setReps(0);
            rs.setLapses(0);
        }
        double ease = rs.getEase() == null ? 2.5 : rs.getEase();
        int reps = rs.getReps() == null ? 0 : rs.getReps();
        int interval = rs.getIntervalDays() == null ? 0 : rs.getIntervalDays();
        int lapses = rs.getLapses() == null ? 0 : rs.getLapses();

        if (correct) {
            reps++;
            if (reps == 1) {
                interval = 1;
            } else if (reps == 2) {
                interval = 3;
            } else {
                interval = (int) Math.round(interval * ease);
            }
            interval = Math.min(interval, 180);
            ease = Math.min(3.0, ease + 0.1);
            rs.setLastResult("CORRECT");
        } else if (vague) {
            interval = Math.max(1, interval);
            ease = Math.max(1.3, ease - 0.05);
            rs.setLastResult("VAGUE");
        } else {
            reps = 0;
            interval = 1;
            lapses++;
            ease = Math.max(1.3, ease - 0.2);
            rs.setLastResult("WRONG");
        }

        rs.setEase(ease);
        rs.setReps(reps);
        rs.setIntervalDays(interval);
        rs.setLapses(lapses);
        rs.setDueAt(now.plusDays(interval));
        rs.setUpdatedAt(now);
        if (rs.getId() == null) {
            reviewMapper.insert(rs);
        } else {
            reviewMapper.updateById(rs);
        }
        return rs;
    }

    /** 0~100 的掌握度，用于列表展示。 */
    public int mastery(int reps, int interval, String lastResult) {
        if (reps <= 0) {
            return "WRONG".equals(lastResult) ? 15 : 0;
        }
        int base = switch (reps) {
            case 1 -> 40;
            case 2 -> 60;
            case 3 -> 75;
            default -> Math.min(95, 75 + (reps - 3) * 5 + Math.min(10, interval / 30));
        };
        return "WRONG".equals(lastResult) ? Math.min(base, 30) : base;
    }
}
