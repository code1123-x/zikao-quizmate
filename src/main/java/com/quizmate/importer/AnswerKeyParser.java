package com.quizmate.importer;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「参考答案」文件里抽出 题号 → 答案 的映射。
 * 形如：1．A  2．D  3．B … 或 1.A 2.D
 */
@Component
public class AnswerKeyParser {

    private static final Pattern PAIR = Pattern.compile(
            "(?<!\\d)(\\d{1,3})\\s*[.、．·:：]?\\s*([A-F]{1,4}|正确|错误|对|错|√|×)(?![A-Za-z])");

    public Map<Integer, String> parse(String text) {
        Map<Integer, String> map = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return map;
        }
        for (String line : text.split("\\R")) {
            Matcher m = PAIR.matcher(line);
            while (m.find()) {
                try {
                    int no = Integer.parseInt(m.group(1));
                    if (no >= 1 && no <= 200) {
                        map.putIfAbsent(no, m.group(2));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return map;
    }
}
