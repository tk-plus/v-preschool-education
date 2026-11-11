package com.zhiya.preschool.admin.controller;

import com.zhiya.preschool.admin.config.AdminWebConfig;
import com.zhiya.preschool.admin.service.AdminAccessService;
import com.zhiya.preschool.admin.service.AdminAccessService.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员登录和会话管理。 */
@Profile("local")
@RestController
@RequestMapping("/api/admin")
public class AdminLoginController {
    private final AdminAccessService access;

    public AdminLoginController(AdminAccessService access) {
        this.access = access;
    }

    @PostMapping(path = "/login", consumes = "application/json")
    public Account login(
        @Valid @RequestBody Credentials credentials, HttpServletRequest request
    ) {
        var key = access.login(credentials.username(), credentials.password());
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(AdminWebConfig.ADMIN_SESSION, key);
        session.setMaxInactiveInterval(30 * 60);
        return access.account(key);
    }

    @GetMapping("/session")
    public Account session(@RequestAttribute(AdminWebConfig.ADMIN_ACCOUNT) Account account) {
        return account;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        request.getSession(false).invalidate();
        return ResponseEntity.noContent().build();
    }

    public record Credentials(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 256) String password
    ) {
    }
}
