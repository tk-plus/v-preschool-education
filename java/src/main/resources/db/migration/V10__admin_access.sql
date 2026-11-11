CREATE TABLE admin_role (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    name VARCHAR(64) NOT NULL COMMENT '角色名称',
    system_role BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否为受保护的系统角色',
    PRIMARY KEY (id),
    UNIQUE KEY uq_admin_role_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理端角色';

CREATE TABLE admin_role_permission (
    role_id BIGINT NOT NULL COMMENT '角色ID',
    permission_code VARCHAR(32) NOT NULL COMMENT '后端定义的权限编码',
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_admin_permission_role FOREIGN KEY (role_id) REFERENCES admin_role (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理端角色权限';

CREATE TABLE admin_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '管理员ID',
    username VARCHAR(64) NOT NULL COMMENT '登录账号',
    password_hash VARCHAR(255) NOT NULL COMMENT '带随机盐的密码哈希',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否允许登录',
    auth_version BIGINT NOT NULL DEFAULT 0 COMMENT '会话版本，停用或重置密码时递增',
    PRIMARY KEY (id),
    UNIQUE KEY uq_admin_username (username),
    CONSTRAINT fk_admin_user_role FOREIGN KEY (role_id) REFERENCES admin_role (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '管理端账号';

INSERT INTO admin_role (id, name, system_role) VALUES (1, '超级管理员', TRUE);
INSERT INTO admin_role_permission (role_id, permission_code)
VALUES (1, 'ACCESS_MANAGE'), (1, 'IDIOM_READ'), (1, 'LEVEL_PUBLISH');
