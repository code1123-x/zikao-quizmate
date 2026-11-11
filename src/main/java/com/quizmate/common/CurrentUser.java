package com.quizmate.common;

import java.nio.file.Path;

/**
 * 当前请求的登录账号。HTTP 请求由 AuthFilter 设置，后台任务在启动线程前保存并恢复。
 * 没有登录上下文时按 0 处理，开机初始化/旧数据迁移走默认数据库。
 */
public final class CurrentUser {

    public record Principal(long id, String username, String rawDir, boolean admin) {
    }

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void set(Principal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    public static Principal get() {
        return CURRENT.get();
    }

    public static long id() {
        Principal p = CURRENT.get();
        return p == null ? 0L : p.id();
    }

    public static String username() {
        Principal p = CURRENT.get();
        return p == null ? "" : p.username();
    }

    public static Path rawDir() {
        Principal p = CURRENT.get();
        String dir = p == null || p.rawDir() == null || p.rawDir().isBlank()
                ? "raw" : p.rawDir();
        return Path.of(dir).toAbsolutePath().normalize();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
