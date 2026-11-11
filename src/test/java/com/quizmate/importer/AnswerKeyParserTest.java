package com.quizmate.importer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerKeyParserTest {

    private final AnswerKeyParser parser = new AnswerKeyParser();

    @Test
    void parsesCommonNumberAnswerPairs() {
        Map<Integer, String> answers = parser.parse("1．A 2．D 3．AB 4. C");

        assertEquals("A", answers.get(1));
        assertEquals("D", answers.get(2));
        assertEquals("AB", answers.get(3));
        assertEquals("C", answers.get(4));
    }

    @Test
    void ignoresMultiDigitYearLikeText() {
        Map<Integer, String> answers = parser.parse("2024年10月真题 1.A 2.B");

        assertEquals(2, answers.size());
        assertEquals("A", answers.get(1));
        assertEquals("B", answers.get(2));
    }

    @Test
    void parsesJudgeAnswers() {
        Map<Integer, String> answers = parser.parse("1.对 2.错 3.正确 4.错误");

        assertEquals("对", answers.get(1));
        assertEquals("错", answers.get(2));
        assertEquals("正确", answers.get(3));
        assertEquals("错误", answers.get(4));
    }
}
