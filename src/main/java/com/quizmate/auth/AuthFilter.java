package com.quizmate.auth;

import com.quizmate.common.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AuthService auth;

    public AuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute("userId");
        AuthService.User user = id instanceof Number n ? auth.findById(n.longValue()) : null;
        if (user == null) {
            user = auth.findByToken(bearerToken(request));
        }
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请先登录\"}");
            return;
        }
        CurrentUser.set(auth.principal(user));
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
        }
    }

    private static boolean isPublic(String path) {
        return path.equals("/api/health")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/status");
    }

    /** 支持标准的 Authorization: Bearer xxx，也兼容 X-Auth-Token。 */
    public static String bearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).strip();
            return token.isEmpty() ? null : token;
        }
        String header = request.getHeader("X-Auth-Token");
        return header == null || header.isBlank() ? null : header.strip();
    }
}
