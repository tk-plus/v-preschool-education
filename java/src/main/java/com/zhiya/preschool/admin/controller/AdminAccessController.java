package com.zhiya.preschool.admin.controller;

import com.zhiya.preschool.admin.config.AdminWebConfig;
import com.zhiya.preschool.admin.config.RequireAdminPermission;
import com.zhiya.preschool.admin.mapper.AdminAccessMapper.User;
import com.zhiya.preschool.admin.service.AdminAccessService;
import com.zhiya.preschool.admin.service.AdminAccessService.Account;
import com.zhiya.preschool.admin.service.AdminAccessService.RoleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@RequestMapping("/api/admin/access")
@RequireAdminPermission(AdminAccessService.ACCESS_MANAGE)
public class AdminAccessController {
    private final AdminAccessService access;

    public AdminAccessController(AdminAccessService access) {
        this.access = access;
    }

    @GetMapping
    public AccessOverview overview() {
        return new AccessOverview(access.users(), access.roles(), List.of(
            new Permission(AdminAccessService.IDIOM_READ, "查看成语词库"),
            new Permission(AdminAccessService.LEVEL_PUBLISH, "预览和发布关卡")
        ));
    }

    @PostMapping(path = "/users", consumes = "application/json")
    public ResponseEntity<Void> createUser(@Valid @RequestBody NewUser input) {
        access.createUser(input.username(), input.password(), input.roleId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/users/{id}", consumes = "application/json")
    public ResponseEntity<Void> updateUser(
        @PathVariable long id, @Valid @RequestBody UserChange input,
        @RequestAttribute(AdminWebConfig.ADMIN_ACCOUNT) Account operator
    ) {
        access.updateUser(id, input.roleId(), input.enabled(), operator.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/users/{id}/password", consumes = "application/json")
    public ResponseEntity<Void> resetPassword(@PathVariable long id, @Valid @RequestBody Password input) {
        access.resetPassword(id, input.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/roles", consumes = "application/json")
    public ResponseEntity<Void> createRole(@Valid @RequestBody RoleInput input) {
        return saveRole(null, input);
    }

    @PostMapping(path = "/roles/{id}", consumes = "application/json")
    public ResponseEntity<Void> updateRole(@PathVariable long id, @Valid @RequestBody RoleInput input) {
        return saveRole(id, input);
    }

    @PostMapping("/roles/{id}/delete")
    public ResponseEntity<Void> deleteRole(@PathVariable long id) {
        access.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Void> saveRole(Long id, RoleInput input) {
        access.saveRole(id, input.name(), input.permissions());
        return ResponseEntity.noContent().build();
    }

    public record Permission(String code, String label) {
    }

    public record AccessOverview(List<User> users, List<RoleView> roles, List<Permission> permissions) {
    }

    public record NewUser(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(min = 8, max = 256) String password,
        @NotNull @Positive Long roleId
    ) {
    }

    public record UserChange(@NotNull @Positive Long roleId, @NotNull Boolean enabled) {
    }

    public record Password(@NotBlank @Size(min = 8, max = 256) String password) {
    }

    public record RoleInput(
        @NotBlank @Size(max = 64) String name,
        @NotNull @Size(max = 2) List<@NotBlank String> permissions
    ) {
    }
}
