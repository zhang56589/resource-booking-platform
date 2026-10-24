-- ========================================================================
-- 会议室预约系统｜数据库初始化脚本
-- 适用 MySQL 5.7+
-- ========================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- sys_user（系统用户）
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`     VARCHAR(50)  NOT NULL COMMENT '账号',
    `password`     VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 密文）',
    `nickname`     VARCHAR(50)  DEFAULT NULL COMMENT '真实姓名',
    `phone`        VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `role`         TINYINT      NOT NULL COMMENT '角色 0=普通用户 1=管理员',
    `enabled`      TINYINT      DEFAULT 1 COMMENT '是否启用 1=启用 0=禁用',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

-- ----------------------------
-- meeting_room（会议室）
-- ----------------------------
DROP TABLE IF EXISTS `meeting_room`;
CREATE TABLE `meeting_room` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会议室ID',
    `room_name`    VARCHAR(100) NOT NULL COMMENT '会议室名称',
    `capacity`     INT          NOT NULL COMMENT '容纳人数',
    `location`     VARCHAR(100) DEFAULT NULL COMMENT '位置',
    `description`  VARCHAR(255) DEFAULT NULL COMMENT '描述',
    `enabled`      TINYINT      DEFAULT 1 COMMENT '1=启用 0=禁用',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议室';

-- ----------------------------
-- meeting_booking（会议室预约记录）
-- ----------------------------
DROP TABLE IF EXISTS `meeting_booking`;
CREATE TABLE `meeting_booking` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '预约ID',
    `room_id`      BIGINT       NOT NULL COMMENT '会议室ID',
    `user_id`      BIGINT       NOT NULL COMMENT '预约人ID',
    `title`        VARCHAR(100) NOT NULL COMMENT '会议标题',
    `start_time`   DATETIME     NOT NULL COMMENT '开始时间',
    `end_time`     DATETIME     NOT NULL COMMENT '结束时间',
    `status`       TINYINT      NOT NULL COMMENT '状态 0=待审批 1=已通过 2=已驳回 3=已取消',
    `reject_reason` VARCHAR(255) DEFAULT NULL COMMENT '驳回理由',
    `reason`       VARCHAR(255) DEFAULT NULL COMMENT '申请理由',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_booking_room_time` (`room_id`, `start_time`, `end_time`),
    INDEX `idx_booking_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会议室预约记录';

-- ----------------------------
-- operation_log（通用操作日志）
-- ----------------------------
DROP TABLE IF EXISTS `operation_log`;
CREATE TABLE `operation_log` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`      BIGINT       DEFAULT NULL COMMENT '操作人ID',
    `username`     VARCHAR(50)  DEFAULT NULL COMMENT '操作账号',
    `operation`    VARCHAR(255) DEFAULT NULL COMMENT '操作类型',
    `method`       VARCHAR(100) DEFAULT NULL COMMENT '请求方法',
    `params`       TEXT         COMMENT '请求参数',
    `ip`           VARCHAR(50)  DEFAULT NULL COMMENT '客户端IP',
    `cost_time`    BIGINT       DEFAULT NULL COMMENT '耗时ms',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_oplog_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志';

-- ----------------------------
-- sys_login_log（登录日志）
-- ----------------------------
DROP TABLE IF EXISTS `sys_login_log`;
CREATE TABLE `sys_login_log` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `username`     VARCHAR(50)  DEFAULT NULL COMMENT '登录账号',
    `ip`           VARCHAR(50)  DEFAULT NULL COMMENT '客户端IP',
    `status`       TINYINT      DEFAULT NULL COMMENT '0=成功 1=失败',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `login_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志';

-- ----------------------------
-- sys_operate_log（管理员关键操作日志）
-- ----------------------------
DROP TABLE IF EXISTS `sys_operate_log`;
CREATE TABLE `sys_operate_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `operator_id`   BIGINT       DEFAULT NULL COMMENT '操作人ID',
    `operator_name` VARCHAR(50)  DEFAULT NULL COMMENT '操作人账号',
    `target_id`     BIGINT       DEFAULT NULL COMMENT '目标对象ID',
    `operate_type`  VARCHAR(64)  DEFAULT NULL COMMENT '操作类型',
    `ip`            VARCHAR(50)  DEFAULT NULL COMMENT '客户端IP',
    `operate_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员关键操作日志';

-- ========================================================================
-- P0-2 新增：sys_outbox（本地消息表，Transaction Outbox Pattern）
--   保证 DB 业务操作与 MQ 消息的原子提交。
--   业务事务内写 outbox（与业务表同事务）；定时任务轮询 outbox 发 MQ，指数退避重试。
-- ========================================================================
DROP TABLE IF EXISTS `sys_outbox`;
CREATE TABLE `sys_outbox` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `msg_id`          VARCHAR(64)  NOT NULL COMMENT '消息唯一ID（UUID，消费端去重用）',
    `topic`           VARCHAR(128) NOT NULL COMMENT 'RocketMQ Topic',
    `tag`             VARCHAR(64)  DEFAULT NULL COMMENT 'RocketMQ Tag（可选）',
    `payload`         TEXT         NOT NULL COMMENT '消息体 JSON',
    `status`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=死信',
    `retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry`       INT          NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `next_retry_time` DATETIME     DEFAULT NULL COMMENT '下次重试时间（指数退避计算）',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_msg_id` (`msg_id`),
    INDEX `idx_outbox_pending` (`status`, `next_retry_time`),
    INDEX `idx_outbox_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表（Transaction Outbox Pattern）';

-- ========================================================================
-- P0-3 新增：RBAC 细粒度权限三张表
--   sys_role.id 与 SysUser.role(Integer) 对齐：0=employee, 1=admin
--   sys_permission.code 统一 "模块:动作" 格式，如 booking:approve
--   sys_role_permission 多对多中间表
-- ========================================================================
DROP TABLE IF EXISTS `sys_role_permission`;
DROP TABLE IF EXISTS `sys_permission`;
DROP TABLE IF EXISTS `sys_role`;

CREATE TABLE `sys_role` (
    `id`          TINYINT      NOT NULL COMMENT '与 SysUser.role 对齐：0=employee, 1=admin',
    `code`        VARCHAR(32)  NOT NULL COMMENT '角色编码：employee / admin',
    `name`        VARCHAR(64)  NOT NULL COMMENT '角色显示名',
    `description` VARCHAR(256) DEFAULT NULL COMMENT '角色描述',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE `sys_permission` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`        VARCHAR(64)  NOT NULL COMMENT '权限码，模块:动作，如 booking:approve',
    `name`        VARCHAR(128) NOT NULL COMMENT '权限显示名',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_perm_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点表';

CREATE TABLE `sys_role_permission` (
    `role_id`       TINYINT NOT NULL COMMENT '外键 sys_role.id',
    `permission_id` INT     NOT NULL COMMENT '外键 sys_permission.id',
    PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联表';

-- ========================================================================
-- P0-3 RBAC 预置数据
-- ========================================================================
INSERT INTO `sys_role` (`id`, `code`, `name`, `description`) VALUES
    (0, 'employee', '普通员工', '可提交/取消预约'),
    (1, 'admin',    '管理员',   '审批预约/会议室/用户管理/日志');

INSERT INTO `sys_permission` (`code`, `name`) VALUES
    ('booking:submit',  '提交预约'),
    ('booking:cancel',  '取消预约'),
    ('booking:approve', '审批预约'),
    ('booking:admin',   '查看全部预约'),
    ('room:list',       '查看会议室'),
    ('room:write',      '增删改启停用会议室'),
    ('user:page',       '用户列表'),
    ('user:write',      '启用/禁用/改角色/重置密码'),
    ('log:page',        '查看日志');

-- 员工权限（提交预约 + 取消预约 + 查看会议室）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
    (0, 1), (0, 2), (0, 5);

-- 管理员权限（全部）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9);

SET FOREIGN_KEY_CHECKS = 1;
