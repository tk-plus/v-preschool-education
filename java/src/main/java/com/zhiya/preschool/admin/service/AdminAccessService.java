package com.zhiya.preschool.admin.service;

import com.zhiya.preschool.admin.mapper.AdminAccessMapper;
import com.zhiya.preschool.admin.mapper.AdminAccessMapper.Role;
import com.zhiya.preschool.admin.mapper.AdminAccessMapper.User;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAccessService {
    public static final String ACCESS_MANAGE = "ACCESS_MANAGE";
    public static final String IDIOM_READ = "IDIOM_READ";
    public static final String LEVEL_PUBLISH = "LEVEL_PUBLISH";
    private static final Set<String> CONTENT_PERMISSIONS = Set.of(IDIOM_READ, LEVEL_PUBLISH);
    private static final long SYSTEM_ROLE = 1;
    private static final int HASH_ITERATIONS = 600_000;
    private final SecureRandom random = new SecureRandom();
    private final AdminAccessMapper mapper;
    private final String initialUsername;
    private final String initialPassword;

    public AdminAccessService(
        AdminAccessMapper mapper,
        @Value("${admin.login.username}") String initialUsername,
        @Value("${admin.login.password}") String initialPassword
    ) {
        this.mapper = mapper;
        this.initialUsername = normalize(initialUsername);
        this.initialPassword = initialPassword;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SessionKey login(String username, String password) {
        if (!mapper.hasUsers()) {
            if (initialPassword.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请先设置 ADMIN_PASSWORD 初始化管理员");
            }
            if (!initialUsername.equals(normalize(username))
                || !MessageDigest.isEqual(initialPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    password.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw invalidLogin();
            }
            // 锁定系统角色后重新读取，避免并发首次登录重复创建管理员。
            mapper.lockSystemRole();
            if (!mapper.hasUsers()) {
                mapper.createUser(initialUsername, hash(initialPassword), SYSTEM_ROLE);
            }
        }
        var credentials = mapper.credentials(normalize(username));
        if (credentials == null || !credentials.enabled() || !matches(password, credentials.passwordHash())) {
            throw invalidLogin();
        }
        return new SessionKey(credentials.id(), credentials.authVersion());
    }

    public Account account(SessionKey key) {
        User user = mapper.user(key.userId());
        if (user == null || !user.enabled() || user.authVersion() != key.version()) {
            return null;
        }
        return new Account(user.id(), user.username(), user.roleName(), mapper.permissions(user.roleId()));
    }

    public List<User> users() {
        return mapper.users();
    }

    public List<RoleView> roles() {
        return mapper.roles().stream()
            .map(role -> new RoleView(role.id(), role.name(), role.systemRole(), mapper.permissions(role.id())))
            .toList();
    }

    @Transactional
    public void createUser(String username, String password, long roleId) {
        mapper.lockSystemRole();
        requireRole(roleId);
        String normalized = normalize(username);
        if (!normalized.matches("[a-z0-9][a-z0-9_.-]{2,63}")) {
            throw bad("账号须为 3～64 位字母、数字或 . _ -");
        }
        try {
            mapper.createUser(normalized, hash(password), roleId);
        } catch (DuplicateKeyException error) {
            throw conflict("账号已存在");
        }
    }

    @Transactional
    public void updateUser(long id, long roleId, boolean enabled, long operator) {
        mapper.lockSystemRole();
        User currentOperator = requireUser(operator);
        if (!currentOperator.enabled() || currentOperator.roleId() != SYSTEM_ROLE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号已无管理权限");
        }
        User user = requireUser(id);
        requireRole(roleId);
        if (id == operator && (!enabled || user.roleId() != roleId)) {
            throw bad("不能停用自己或修改自己的角色");
        }
        if (user.roleId() != roleId || user.enabled() != enabled) {
            mapper.updateUser(id, roleId, enabled);
        }
    }

    @Transactional
    public void resetPassword(long id, String password) {
        mapper.lockSystemRole();
        requireUser(id);
        mapper.resetPassword(id, hash(password));
    }

    @Transactional
    public void saveRole(Long id, String name, List<String> permissions) {
        mapper.lockSystemRole();
        if (!CONTENT_PERMISSIONS.containsAll(permissions)) {
            throw bad("角色包含不可分配的权限");
        }
        if (permissions.contains(LEVEL_PUBLISH) && !permissions.contains(IDIOM_READ)) {
            throw bad("发布关卡需要同时拥有查看词库权限");
        }
        try {
            if (id == null) {
                id = mapper.createRole(name.strip());
            } else {
                requireCustomRole(id);
                mapper.updateRole(id, name.strip());
            }
            mapper.replacePermissions(id, permissions.stream().distinct().toList());
        } catch (DuplicateKeyException error) {
            throw conflict("角色名称已存在");
        }
    }

    @Transactional
    public void deleteRole(long id) {
        mapper.lockSystemRole();
        requireCustomRole(id);
        if (mapper.users().stream().anyMatch(user -> user.roleId() == id)) {
            throw conflict("还有账号使用此角色，请先更换账号角色");
        }
        mapper.deleteRole(id);
    }

    private User requireUser(long id) {
        User user = mapper.user(id);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        return user;
    }

    private Role requireRole(long id) {
        return mapper.roles().stream().filter(role -> role.id() == id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在"));
    }

    private void requireCustomRole(long id) {
        if (requireRole(id).systemRole()) throw bad("系统角色不能修改或删除");
    }

    private String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" +
            Base64.getEncoder().encodeToString(derive(password, salt));
    }

    private boolean matches(String password, String stored) {
        String[] parts = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        return MessageDigest.isEqual(derive(password, salt), Base64.getDecoder().decode(parts[1]));
    }

    private byte[] derive(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("无法计算管理员密码哈希", error);
        } finally {
            spec.clearPassword();
        }
    }

    private static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException invalidLogin() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record SessionKey(long userId, long version) {
    }

    public record Account(long id, String username, String roleName, List<String> permissions) {
    }

    public record RoleView(long id, String name, boolean systemRole, List<String> permissions) {
    }
}
