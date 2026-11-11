package com.quizmate.common;

import java.util.regex.Pattern;

/**
 * 文本清洗：去掉 Word/PDF 里带出来的私用区字符、替换符和零宽字符；
 * 再把中文里常见的半角标点、多余空格和括号统一成试卷的写法。
 */
public final class Texts {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    private Texts() {
    }

    /** 只去掉不可见字符，保留原始标点（用于 JSON 等结构文本） */
    public static String clean(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean drop = (c >= 0xE000 && c <= 0xF8FF)   // 私用区
                    || c == 0xFFFD                          // 替换符
                    || c == 0x200B || c == 0x200C || c == 0x200D
                    || c == 0xFEFF
                    || (c < 0x20 && c != '\n' && c != '\t');
            if (!drop) {
                sb.append(c);
            }
        }
        return sb.toString()
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    /**
     * 面向试卷文本的规范化：
     * - 中文之间被排版拆开的空格（「实 现」→「实现」）
     * - 半角括号 → 全角，空括号统一成「（ ）」
     * - 中文后面跟着的 , ; : ? ! . → ，；：？！。
     * - 标点前的多余空格
     */
    public static String normalize(String s) {
        String t = clean(s);
        if (t == null || t.isEmpty() || !CJK.matcher(t).find()) {
            return t;
        }

        // 中文之间被拆开的空格
        t = t.replaceAll("(?<=[\\u4e00-\\u9fff])[ \\t\\u3000]+(?=[\\u4e00-\\u9fff])", "");
        // 标点前的空格（不动右括号，避免把「（ ）」压成「（）」）
        t = t.replaceAll("[ \\t\\u3000]+(?=[，。；：？！、])", "");
        // 半角括号统一成全角，空括号统一成「（ ）」
        t = t.replace('(', '（').replace(')', '）');
        t = t.replaceAll("（[ \\t\\u3000]*）", "（ ）");
        // 中文后的半角标点
        t = t.replaceAll("(?<=[\\u4e00-\\u9fff]),", "，");
        t = t.replaceAll("(?<=[\\u4e00-\\u9fff]);", "；");
        t = t.replaceAll("(?<=[\\u4e00-\\u9fff]):", "：");
        t = t.replaceAll("(?<=[\\u4e00-\\u9fff])\\?", "？");
        t = t.replaceAll("(?<=[\\u4e00-\\u9fff])!", "！");
        t = t.replaceAll("(?<=[\\u4e00-\\u9fff])\\.(?![0-9A-Za-z])", "。");
        // 合并重复空格
        t = t.replaceAll("[ \\t\\u3000]{2,}", " ");
        return t.strip();
    }
}

