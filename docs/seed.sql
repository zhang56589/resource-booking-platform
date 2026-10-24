-- ========================================================================
-- 会议室预约管理系统 初始化数据脚本（seed.sql）
-- 用途：只重置数据不重建表的场景；新库请直接执行 docs/schema.sql（已含本文件全部内容）
-- 注意：本文件数据与 schema.sql 的 INSERT 段同源，修改时需同步两处
-- ========================================================================

USE `admin_system`;

SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------------------
-- sys_user 预置账号（密码均为 BCrypt 加密后的 123456，spring-security-crypto 生成）
-- 注意：实体 SysUser.nickname 映射数据库 real_name 列
-- ------------------------------------------------------------------------
TRUNCATE TABLE `sys_user`;
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `phone`, `role`, `enabled`) VALUES
    ('admin', '$2a$10$9VeYqh.kg96L7vOEeZ2JXOHhkhKT34qkio9vhcnWyV4fXu1Pu5jeC', '系统管理员', '13800000000', 1, 1),
    ('user1', '$2a$10$3a4iy0l/1NoK82g30qdbMelcfnW2bMKnJokhElTK1XppBHm7k3tBW', '员工一号', '13800000001', 0, 1),
    ('user2', '$2a$10$Lx4xCm0JJGBmKd8evuHspuKn48Q7MgIQzMNFrPy91quU7pp0.OLX2', '员工二号', '13800000002', 0, 1);

-- ------------------------------------------------------------------------
-- P0-3 RBAC：角色 / 权限 / 角色-权限
-- ------------------------------------------------------------------------
TRUNCATE TABLE `sys_role_permission`;
TRUNCATE TABLE `sys_permission`;
TRUNCATE TABLE `sys_role`;

INSERT INTO `sys_role` (`id`, `code`, `name`, `description`) VALUES
    (0, 'employee', '员工',   '普通员工：提交/取消预约、查看会议室'),
    (1, 'admin',    '管理员', '系统管理员：全部权限');

INSERT INTO `sys_permission` (`id`, `code`, `name`) VALUES
    (1, 'booking:submit',  '提交预约'),
    (2, 'booking:cancel',  '取消预约'),
    (3, 'booking:approve', '审批预约'),
    (4, 'booking:admin',   '预约管理查询'),
    (5, 'room:list',       '会议室列表查询'),
    (6, 'room:write',      '会议室增删改启停'),
    (7, 'user:page',       '用户分页查询'),
    (8, 'user:write',      '用户角色/状态/密码管理'),
    (9, 'log:page',        '日志查询');

-- 员工权限（3 条）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
    (0, 1), (0, 2), (0, 5);
-- 管理员权限（全部 9 条）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9);

-- ------------------------------------------------------------------------
-- P1-5 动态菜单：菜单 / 角色-菜单（path 与前端实际路由对齐）
-- ------------------------------------------------------------------------
TRUNCATE TABLE `sys_role_menu`;
TRUNCATE TABLE `sys_menu`;

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
-- 管理员菜单（全部）
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (1, 7), (1, 8), (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6);

-- ------------------------------------------------------------------------
-- meeting_room 演示数据（可选）
-- ------------------------------------------------------------------------
INSERT INTO `meeting_room` (`room_name`, `capacity`, `location`, `description`, `enabled`) VALUES
    ('大会议室 A', 20, '3F-301', '投影仪 + 视频会议', 1),
    ('小会议室 B', 6,  '3F-302', '白板',            1),
    ('洽谈室 C',   4,  '4F-401', '电话会议',         1)
ON DUPLICATE KEY UPDATE `room_name` = VALUES(`room_name`);

SET FOREIGN_KEY_CHECKS = 1;
