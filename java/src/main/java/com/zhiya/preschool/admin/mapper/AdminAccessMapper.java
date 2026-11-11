package com.zhiya.preschool.admin.mapper;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAccessMapper {
    private static final RowMapper<User> USER = (rs, index) -> new User(
        rs.getLong("id"), rs.getString("username"), rs.getLong("role_id"), rs.getString("role_name"),
        rs.getBoolean("enabled"), rs.getLong("auth_version")
    );
    private static final String USERS = """
        SELECT u.id, u.username, u.role_id, r.name AS role_name, u.enabled, u.auth_version
        FROM admin_user u JOIN admin_role r ON r.id = u.role_id
        """;
    private final JdbcTemplate jdbc;

    public AdminAccessMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockSystemRole() {
        jdbc.queryForObject("SELECT id FROM admin_role WHERE id = 1 FOR UPDATE", Long.class);
    }

    public List<User> users() {
        return jdbc.query(USERS + " ORDER BY u.id", USER);
    }

    public User user(long id) {
        return jdbc.query(USERS + " WHERE u.id = ?", USER, id).stream().findFirst().orElse(null);
    }

    public Credentials credentials(String username) {
        return jdbc.query("""
            SELECT id, password_hash, auth_version, enabled FROM admin_user WHERE username = ?
            """, (rs, index) -> new Credentials(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4)),
            username).stream().findFirst().orElse(null);
    }

    public boolean hasUsers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Long.class) > 0;
    }

    public List<Role> roles() {
        return jdbc.query("SELECT id, name, system_role FROM admin_role ORDER BY id",
            (rs, index) -> new Role(rs.getLong(1), rs.getString(2), rs.getBoolean(3)));
    }

    public List<String> permissions(long roleId) {
        return jdbc.queryForList(
            "SELECT permission_code FROM admin_role_permission WHERE role_id = ? ORDER BY permission_code",
            String.class, roleId);
    }

    public void createUser(String username, String hash, long roleId) {
        jdbc.update("INSERT INTO admin_user (username, password_hash, role_id) VALUES (?, ?, ?)",
            username, hash, roleId);
    }

    public void updateUser(long id, long roleId, boolean enabled) {
        jdbc.update("UPDATE admin_user SET role_id = ?, enabled = ?, auth_version = auth_version + 1 WHERE id = ?",
            roleId, enabled, id);
    }

    public void resetPassword(long id, String hash) {
        jdbc.update("UPDATE admin_user SET password_hash = ?, auth_version = auth_version + 1 WHERE id = ?", hash, id);
    }

    public long createRole(String name) {
        jdbc.update("INSERT INTO admin_role (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM admin_role WHERE name = ?", Long.class, name);
    }

    public void updateRole(long id, String name) {
        jdbc.update("UPDATE admin_role SET name = ? WHERE id = ?", name, id);
    }

    public void replacePermissions(long id, List<String> permissions) {
        jdbc.update("DELETE FROM admin_role_permission WHERE role_id = ?", id);
        for (String permission : permissions) {
            jdbc.update("INSERT INTO admin_role_permission (role_id, permission_code) VALUES (?, ?)", id, permission);
        }
    }

    public void deleteRole(long id) {
        jdbc.update("DELETE FROM admin_role WHERE id = ?", id);
    }

    public record User(long id, String username, long roleId, String roleName, boolean enabled, long authVersion) {
    }

    public record Credentials(long id, String passwordHash, long authVersion, boolean enabled) {
    }

    public record Role(long id, String name, boolean systemRole) {
    }
}
