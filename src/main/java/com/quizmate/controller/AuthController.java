package com.quizmate.controller;

import com.quizmate.auth.AuthFilter;
import com.quizmate.auth.AuthService;
import com.quizmate.common.BizException;
import com.quizmate.common.CurrentUser;
import com.quizmate.dto.Dtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/status")
    public Dtos.AuthStatus status() {
        return new Dtos.AuthStatus(auth.count() > 0);
    }

    @PostMapping("/register")
    public Dtos.AuthUserView register(@RequestBody Dtos.AuthRequest req, HttpServletRequest request) {
        try {
            AuthService.User user = auth.register(req.username(), req.password());
            loginSession(request, user);
            return view(user, isMini(request) ? auth.issueToken(user) : null);
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
    }

    @PostMapping("/login")
    public Dtos.AuthUserView login(@RequestBody Dtos.AuthRequest req, HttpServletRequest request) {
        try {
            AuthService.User user = auth.login(req.username(), req.password());
            loginSession(request, user);
            return view(user, isMini(request) ? auth.issueToken(user) : null);
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
    }

    @GetMapping("/me")
    public Dtos.AuthUserView me() {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null) {
            throw new BizException("请先登录");
        }
        return new Dtos.AuthUserView(p.id(), p.username(), p.admin(), null);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        auth.revokeToken(AuthFilter.bearerToken(request));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private static Dtos.AuthUserView view(AuthService.User user, String token) {
        return new Dtos.AuthUserView(user.id(), user.username(), user.admin(), token);
    }

    /** 小程序登录时带 X-Client: mini，才签发长期 Token；网页继续只用 Session。 */
    private static boolean isMini(HttpServletRequest request) {
        String client = request.getHeader("X-Client");
        return client != null && "mini".equalsIgnoreCase(client.strip());
    }

    private static void loginSession(HttpServletRequest request, AuthService.User user) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute("userId", user.id());
    }
}
