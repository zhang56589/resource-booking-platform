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
    `real_name`    VARCHAR(50)  DEFAULT NULL COMMENT '真实姓名',
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
    `deleted`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删',
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
    `deleted`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删',
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
-- 预置账号（密码均为 BCrypt 加密后的 123456；与 docs/seed.sql 同源，修改需同步两处）
-- ========================================================================
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `phone`, `role`, `enabled`) VALUES
    ('admin', '$2a$10$9VeYqh.kg96L7vOEeZ2JXOHhkhKT34qkio9vhcnWyV4fXu1Pu5jeC', '系统管理员', '13800000000', 1, 1),
    ('user1', '$2a$10$3a4iy0l/1NoK82g30qdbMelcfnW2bMKnJokhElTK1XppBHm7k3tBW', '员工一号', '13800000001', 0, 1),
    ('user2', '$2a$10$Lx4xCm0JJGBmKd8evuHspuKn48Q7MgIQzMNFrPy91quU7pp0.OLX2', '员工二号', '13800000002', 0, 1);

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

-- ========================================================================
-- P1-5 新增：动态菜单两张表
--   sys_menu.component 存前端 views 相对路径字符串（如 booking/MyBooking），
--   前端用 import.meta.glob 动态解析，避免后端硬编码 import 路径
-- ========================================================================
DROP TABLE IF EXISTS `sys_role_menu`;
DROP TABLE IF EXISTS `sys_menu`;

CREATE TABLE `sys_menu` (
    `id`         INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`  INT          NOT NULL DEFAULT 0 COMMENT '父菜单ID，0=顶级',
    `name`       VARCHAR(64)  NOT NULL COMMENT '菜单名称',
    `path`       VARCHAR(128) NOT NULL COMMENT '路由路径',
    `component`  VARCHAR(128) DEFAULT NULL COMMENT '前端组件路径（views 相对，无扩展名）',
    `icon`       VARCHAR(64)  DEFAULT NULL COMMENT 'Element Plus 图标名',
    `permission` VARCHAR(64)  DEFAULT NULL COMMENT '关联权限码（如 booking:approve），可空',
    `order_num`  INT          NOT NULL DEFAULT 0 COMMENT '排序号，升序',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态菜单表';

CREATE TABLE `sys_role_menu` (
    `role_id` TINYINT NOT NULL COMMENT '外键 sys_role.id',
    `menu_id` INT     NOT NULL COMMENT '外键 sys_menu.id',
    PRIMARY KEY (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单关联表';

-- 菜单预置数据（path 与前端实际路由对齐）
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `path`, `component`, `icon`, `permission`, `order_num`) VALUES
    (1, 0, '我的预约',   '/home/my-bookings',    'employee/MyBookings',  'Tickets',        'booking:submit',  1),
    (2, 0, '会议室列表', '/home/rooms',          'employee/RoomList',    'OfficeBuilding', 'room:list',      2),
    (3, 0, '预约审批',   '/home/booking-approve','admin/BookingAdmin',   'Tickets',        'booking:approve',3),
    (4, 0, '会议室管理', '/home/room-manage',    'admin/RoomManage',     'OfficeBuilding', 'room:write',     4),
    (5, 0, '用户管理',   '/home/users',          'admin/UserManage',     'User',           'user:write',     5),
    (6, 0, '操作日志',   '/home/logs',           'admin/LogList',        'Document',       'log:page',       6),
    (7, 0, '工作台',     '/home/dashboard',      'Dashboard',           'Odometer',       NULL,              0),
    (8, 0, '预约日历',   '/home/calendar',       'RoomCalendar',        'Calendar',       NULL,              1);

-- 员工菜单（工作台 + 预约日历 + 我的预约 + 会议室列表）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (0, 7), (0, 8), (0, 1), (0, 2);
-- 管理员菜单（工作台 + 预约日历 + 审批/会议室/用户/日志；员工专属菜单 1、2 不授予）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1, 7), (1, 8), (1, 3), (1, 4), (1, 5), (1, 6);

-- ========================================================================
-- P1-1 增量迁移（已存在的库执行本段；新库上方 CREATE TABLE 已含全部字段/索引）
-- ========================================================================
-- ALTER TABLE `meeting_booking` ADD INDEX `idx_booking_room_time` (`room_id`, `start_time`, `end_time`);
-- ALTER TABLE `meeting_booking` ADD INDEX `idx_booking_user_status` (`user_id`, `status`);
-- ALTER TABLE `operation_log`   ADD INDEX `idx_oplog_create_time` (`create_time`);
-- ALTER TABLE `meeting_room`    ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删';
-- ALTER TABLE `meeting_booking` ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删';
--
-- 回滚：
-- ALTER TABLE `meeting_booking` DROP INDEX `idx_booking_room_time`, DROP INDEX `idx_booking_user_status`;
-- ALTER TABLE `operation_log`   DROP INDEX `idx_oplog_create_time`;
-- ALTER TABLE `meeting_room`    DROP COLUMN `deleted`;
-- ALTER TABLE `meeting_booking` DROP COLUMN `deleted`;

-- 菜单可见性修复（存量库执行）：管理员角色不应看到员工专属菜单「我的预约」「会议室列表」
DELETE FROM `sys_role_menu` WHERE `role_id` = 1 AND `menu_id` IN (1, 2);

-- ========================================================================
-- v2 内核表（主线0）
--   资源类型 / 资源实例 / 统一预约主表 / 预约明细表
--   老表 meeting_room / meeting_booking 保留不动，数据迁移见 docs/migrate_v2.sql
-- ========================================================================
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `schedule_booking_item`;
DROP TABLE IF EXISTS `schedule_booking`;
DROP TABLE IF EXISTS `resource_instance`;
DROP TABLE IF EXISTS `resource_type`;

CREATE TABLE `resource_type` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `type_code`               VARCHAR(50)  NOT NULL COMMENT '资源类型编码：MEETING_ROOM/WORKSTATION/GPU_POOL',
    `type_name`               VARCHAR(100) NOT NULL COMMENT '资源类型名称',
    `plugin_code`             VARCHAR(50)  NOT NULL COMMENT '调度插件编码：TIME_EXCLUSIVE/QUOTA_SHARE',
    `approval_template_code`  VARCHAR(50)  DEFAULT NULL COMMENT '审批模板编码（加分A 预留）',
    `enabled`                 TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    `deleted`                 TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删',
    `create_time`             DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`             DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_resource_type_code` (`type_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源类型字典';

CREATE TABLE `resource_instance` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `type_id`          BIGINT       NOT NULL COMMENT '资源类型ID',
    `instance_code`    VARCHAR(64)  NOT NULL COMMENT '实例编码（类型内唯一）',
    `instance_name`    VARCHAR(100) NOT NULL COMMENT '实例名称',
    `capacity`         INT          DEFAULT NULL COMMENT '容量（会议室人数/工位数等）',
    `location`         VARCHAR(100) DEFAULT NULL COMMENT '位置',
    `attributes_json`  JSON         DEFAULT NULL COMMENT '差异化属性 JSON（设备/楼层/GPU型号等）',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    `deleted`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删',
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_resource_instance_code` (`type_id`, `instance_code`),
    INDEX `idx_resource_instance_type_status` (`type_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源实例';

CREATE TABLE `schedule_booking` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `booking_no`            VARCHAR(64)  NOT NULL COMMENT '预约单号（唯一）',
    `resource_type_id`      BIGINT       NOT NULL COMMENT '资源类型ID',
    `resource_instance_id`  BIGINT       NOT NULL COMMENT '资源实例ID',
    `user_id`               BIGINT       NOT NULL COMMENT '预约人ID',
    `start_time`            DATETIME     NOT NULL COMMENT '开始时间',
    `end_time`              DATETIME     NOT NULL COMMENT '结束时间',
    `quantity`              INT          NOT NULL DEFAULT 1 COMMENT '数量（时段类固定1，配额类为申请量）',
    `status`                TINYINT      NOT NULL COMMENT '0=待审批 1=已通过 2=已驳回 3=已取消',
    `plugin_code`           VARCHAR(50)  NOT NULL COMMENT '调度插件编码',
    `source`                VARCHAR(20)  NOT NULL DEFAULT 'API' COMMENT '来源：LEGACY/API',
    `ext_json`              JSON         DEFAULT NULL COMMENT '插件扩展字段（主题/理由/驳回原因等）',
    `deleted`               TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删',
    `create_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_schedule_booking_no` (`booking_no`),
    INDEX `idx_schedule_instance_time` (`resource_instance_id`, `start_time`, `end_time`),
    INDEX `idx_schedule_user_status` (`user_id`, `status`),
    INDEX `idx_schedule_type_time` (`resource_type_id`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一预约主表';

CREATE TABLE `schedule_booking_item` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `booking_id`  BIGINT       NOT NULL COMMENT '预约主表ID',
    `item_type`   VARCHAR(20)  NOT NULL COMMENT '明细类型：SLOT/SEAT/QUOTA',
    `item_key`    VARCHAR(100) NOT NULL COMMENT '明细键（实例编码/时间段/配额维度）',
    `quantity`    INT          NOT NULL DEFAULT 1 COMMENT '数量',
    `unit`        VARCHAR(20)  DEFAULT NULL COMMENT '单位',
    `ext_json`    JSON         DEFAULT NULL COMMENT '扩展字段',
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_booking_item_booking` (`booking_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约明细表';

-- 预置资源类型：会议室（迁移脚本可重复补种，保持幂等）
INSERT INTO `resource_type` (`type_code`, `type_name`, `plugin_code`, `approval_template_code`, `enabled`, `deleted`)
VALUES ('MEETING_ROOM', '会议室', 'TIME_EXCLUSIVE', NULL, 1, 0);

SET FOREIGN_KEY_CHECKS = 1;
