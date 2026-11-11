package com.quizmate.importer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PaperParserTest {

    private final PaperParser parser = new PaperParser();

    @Test
    void parsesSingleChoiceOptionsAndAnswer() {
        String text = """
                一、单项选择题
                1. 下列说法正确的是（  ）
                A. 甲
                B. 乙
                C. 丙
                D. 丁
                答案：B
                2. 下列哪一项是错误的（  ）
                A. 一
                B. 二
                C. 三
                D. 四
                答案：C
                """;

        PaperParser.ParseResult result = parser.parse(text);

        assertEquals(2, result.questions().size());
        ParsedQuestion first = result.questions().get(0);
        assertEquals(1, first.getNumber());
        assertEquals("SINGLE", first.getType());
        assertEquals("B", first.getAnswer());
        assertEquals(4, first.getOptions().size());
        assertEquals("C", result.questions().get(1).getAnswer());
    }

    @Test
    void appliesAnswerKeyAtTheEndOfPaper() {
        String text = """
                一、单项选择题
                1. 第一题（  ）
                A. 甲
                B. 乙
                C. 丙
                D. 丁
                2. 第二题（  ）
                A. 甲
                B. 乙
                C. 丙
                D. 丁
                参考答案
                1.A 2.D
                """;

        PaperParser.ParseResult result = parser.parse(text);

        assertEquals(2, result.questions().size());
        assertEquals("A", result.questions().get(0).getAnswer());
        assertEquals("D", result.questions().get(1).getAnswer());
        assertFalse(result.warnings().isEmpty());
    }
}
