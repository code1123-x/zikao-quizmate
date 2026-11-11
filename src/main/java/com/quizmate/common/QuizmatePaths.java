package com.quizmate.common;

import java.nio.file.Path;

/**
 * 统一数据目录。application.yml 里的 SQLite URL 已支持 QUIZMATE_DATA，
 * AI 配置、答案图、OCR 语言包和一次性迁移标记也必须跟着走，否则备份会漏文件。
 */
public final class QuizmatePaths {

    private static final Path DATA = Path.of(dataDir());

    private QuizmatePaths() {
    }

    private static String dataDir() {
        String env = System.getenv("QUIZMATE_DATA");
        return env == null || env.isBlank() ? "data" : env.strip();
    }

    public static Path data() {
        return DATA;
    }

    public static Path data(String... children) {
        Path path = DATA;
        for (String child : children) {
            path = path.resolve(child);
        }
        return path;
    }

    /** 用户数据库：1 号用户继续使用旧 data/quiz.db，其他用户各自一个数据库。 */
    public static Path userDatabase(long userId) {
        if (userId <= 1) {
            return DATA.resolve("quiz.db");
        }
        return DATA.resolve("users").resolve("u" + userId).resolve("quiz.db");
    }
}
