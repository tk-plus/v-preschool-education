package com.zhiya.preschool.admin.config;

import com.zhiya.preschool.admin.service.AdminAccessService;
import com.zhiya.preschool.admin.service.AdminAccessService.Account;
import com.zhiya.preschool.admin.service.AdminAccessService.SessionKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 在管理端路由入口统一校验访问来源和登录会话。 */
@Configuration
@Profile("local")
public class AdminWebConfig implements WebMvcConfigurer {
    public static final String ADMIN_SESSION = "admin.session";
    public static final String ADMIN_ACCOUNT = "admin.account";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private final AdminAccessService access;

    public AdminWebConfig(AdminAccessService access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAccess(access)).addPathPatterns("/api/admin/**");
    }

    private static class AdminAccess implements HandlerInterceptor {
        private final AdminAccessService access;

        AdminAccess(AdminAccessService access) {
            this.access = access;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
            response.setHeader("Cache-Control", "no-store");
            if (!LOOPBACK.contains(request.getRemoteAddr())) {
                return reject(response, 403, "管理端仅供本机使用");
            }
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                String origin = request.getHeader("Origin");
                String expectedOrigin = request.getScheme() + "://" + request.getHeader("Host");
                if (!"1".equals(request.getHeader("X-Admin-Request"))
                    || (origin != null && !origin.equals(expectedOrigin))) {
                    return reject(response, 403, "请求来源不受信任");
                }
            }
            if ("POST".equals(request.getMethod()) && "/api/admin/login".equals(request.getServletPath())) {
                return true;
            }
            HttpSession session = request.getSession(false);
            if (session == null || !(session.getAttribute(ADMIN_SESSION) instanceof SessionKey key)) {
                return reject(response, 401, "请先登录管理端");
            }
            Account account = access.account(key);
            if (account == null) {
                session.invalidate();
                return reject(response, 401, "会话已失效，请重新登录");
            }
            if (handler instanceof HandlerMethod method) {
                var required = method.getMethodAnnotation(RequireAdminPermission.class);
                if (required == null) {
                    required = method.getBeanType().getAnnotation(RequireAdminPermission.class);
                }
                if (required != null && !account.permissions().contains(required.value())) {
                    return reject(response, 403, "当前角色没有此权限");
                }
            }
            request.setAttribute(ADMIN_ACCOUNT, account);
            return true;
        }

        private boolean reject(HttpServletResponse response, int status, String message)
            throws java.io.IOException {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"" + message + "\"}");
            return false;
        }
    }
}
