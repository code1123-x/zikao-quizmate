package com.quizmate.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** 题目去重用的指纹：同一学科 + 题干（忽略空白与标点） */
public final class Hashes {

    private Hashes() {
    }

    public static String question(Long subjectId, String stem) {
        String norm = stem == null ? "" : stem
                .replaceAll("[\\s\\p{Punct}，。？！；：、“”‘’（）《》【】…—]", "")
                .toLowerCase(Locale.ROOT);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((subjectId + "|" + norm).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(norm.hashCode());
        }
    }
}
