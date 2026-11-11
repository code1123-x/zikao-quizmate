package com.quizmate.ai;

import com.quizmate.common.CurrentUser;
import com.quizmate.common.Json;
import com.quizmate.common.QuizmatePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 接口配置，存在 data/ai.json（不进数据库，方便手动改/备份）。
 */
@Slf4j
@Component
public class AiConfigStore {

    public record Settings(String provider, String baseUrl, String apiKey, String model,
                           boolean thinking) {

        /** 本地地址（Ollama 之类）不需要 API Key */
        public boolean local() {
            String u = baseUrl == null ? "" : baseUrl.toLowerCase(java.util.Locale.ROOT);
            return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("0.0.0.0");
        }

        public boolean ready() {
            return (apiKey != null && !apiKey.isBlank()) || local();
        }
    }

    private final Map<Long, Settings> cache = new ConcurrentHashMap<>();

    private static Path file() {
        long userId = CurrentUser.id();
        return userId <= 1
                ? QuizmatePaths.data("ai.json")
                : QuizmatePaths.data("ai", "u" + userId + ".json");
    }

    public Settings get() {
        long userId = CurrentUser.id();
        Settings s = cache.get(userId);
        if (s != null) {
            return s;
        }
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                s = Json.read(Files.readString(f, StandardCharsets.UTF_8), Settings.class);
            }
        } catch (Exception e) {
            log.warn("读取 AI 配置失败", e);
        }
        if (s == null) {
            s = defaults();
        }
        cache.put(userId, s);
        return s;
    }

    public void save(Settings s) {
        long userId = CurrentUser.id();
        cache.put(userId, s);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("保存 AI 配置失败", e);
        }
    }

    public static Settings defaults() {
        return new Settings("deepseek", "https://api.deepseek.com/v1", "", "deepseek-flash", false);
    }
}
