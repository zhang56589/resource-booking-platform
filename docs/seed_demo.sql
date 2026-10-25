-- ========================================================================
-- 会议室预约系统｜中等规模演示数据初始化脚本（seed_demo.sql）
-- 适用：MySQL 5.7+
-- 前置：先执行 docs/schema.sql（建表与基础字典/账号/权限/菜单）
-- 可选：docs/seed_v2.sql（工位字典，本脚本也会幂等补种 WORKSTATION）
--
-- 特性：
--   1. 可重复执行：所有写入按业务唯一键（username / room_name /
--      instance_code / booking_no）判重，重复执行不会产生重复数据
--   2. 只追加演示数据，不修改、不删除既有业务数据
--   3. 演示数据统一前缀，便于识别与回滚：
--      员工账号 emp01~emp20、会议室「演示会议室01~12」、
--      房间实例 ROOM_{meeting_room.id}、工位实例 WS_DEMO_01~08、
--      预约单号 DEMO-{instance_code}-{三位序号}
--
-- 数据规模（共 400 条预约）：
--   员工 20 / 会议室 12 / 会议室实例 12 / 工位实例 8
--   会议室预约 12×28=336 / 工位预约 8×8=64
--   每条预约 1 条 schedule_booking_item（item_type=SLOT）
--
-- 状态分布：待审批只放在「未来时段」（避免被 BookingExpireScheduler 取消），
--           历史时段为 通过 / 驳回 / 取消 混合，便于演示审批与统计页面。
--
-- 注意：仅用于开发/演示环境，请勿在生产库执行。
--       演示会议室含未完结预约，按现有「未完结预约不可删除」规则，
--       如需删除演示会议室，请先按回滚段清理其预约。
-- ========================================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------------------
-- 0. 会话内数字辅助表（0~99），MySQL 5.7 无递归 CTE，用交叉连接生成
-- ------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS `tmp_demo_seq`;
CREATE TEMPORARY TABLE `tmp_demo_seq` (`n` INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;

INSERT INTO `tmp_demo_seq` (`n`)
SELECT d1.`n` * 10 + d2.`n`
FROM (
    SELECT 0 AS `n` UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d1
CROSS JOIN (
    SELECT 0 AS `n` UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d2;

START TRANSACTION;

-- ------------------------------------------------------------------------
-- 1. 资源类型：确保会议室、工位两个字典存在（与 seed_v2.sql 幂等共存）
-- ------------------------------------------------------------------------
INSERT INTO `resource_type`
    (`type_code`, `type_name`, `plugin_code`, `approval_template_code`, `enabled`, `deleted`)
SELECT 'MEETING_ROOM', '会议室', 'TIME_EXCLUSIVE', NULL, 1, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `resource_type` WHERE `type_code` = 'MEETING_ROOM'
);

INSERT INTO `resource_type`
    (`type_code`, `type_name`, `plugin_code`, `approval_template_code`, `enabled`, `deleted`)
SELECT 'WORKSTATION', '工位', 'TIME_EXCLUSIVE', NULL, 1, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `resource_type` WHERE `type_code` = 'WORKSTATION'
);

-- ------------------------------------------------------------------------
-- 2. 员工账号 20 个（emp01~emp20，密码均为 123456，复用 schema.sql 的 BCrypt 密文）
-- ------------------------------------------------------------------------
INSERT INTO `sys_user` (`username`, `password`, `real_name`, `phone`, `role`, `enabled`)
SELECT
    CONCAT('emp', LPAD(s.`n`, 2, '0')),
    '$2a$10$3a4iy0l/1NoK82g30qdbMelcfnW2bMKnJokhElTK1XppBHm7k3tBW',
    CONCAT('演示员工', LPAD(s.`n`, 2, '0')),
    CONCAT('1390000', LPAD(s.`n`, 4, '0')),
    0,
    1
FROM `tmp_demo_seq` s
WHERE s.`n` BETWEEN 1 AND 20
  AND NOT EXISTS (
      SELECT 1 FROM `sys_user` u
      WHERE u.`username` = CONCAT('emp', LPAD(s.`n`, 2, '0'))
  );

-- ------------------------------------------------------------------------
-- 3. 会议室 12 间（演示会议室01~12）
-- ------------------------------------------------------------------------
INSERT INTO `meeting_room`
    (`room_name`, `capacity`, `location`, `description`, `enabled`, `deleted`)
SELECT
    CONCAT('演示会议室', LPAD(s.`n` + 1, 2, '0')),
    6 + (s.`n` % 5) * 2,
    CONCAT('演示楼', 1 + FLOOR(s.`n` / 4), '层-', LPAD(s.`n` % 4 + 1, 2, '0')),
    CONCAT('演示数据：可容纳 ', 6 + (s.`n` % 5) * 2, ' 人'),
    1,
    0
FROM `tmp_demo_seq` s
WHERE s.`n` < 12
  AND NOT EXISTS (
      SELECT 1 FROM `meeting_room` mr
      WHERE mr.`room_name` = CONCAT('演示会议室', LPAD(s.`n` + 1, 2, '0'))
  );

-- ------------------------------------------------------------------------
-- 4. 会议室实例：为所有 meeting_room 补齐 resource_instance
--    instance_code = ROOM_{meeting_room.id}
--    attributes_json.legacyRoomId 供列表/日历反查 roomId，与 Mapper 约定一致
--    （含 seed.sql 预置的「大会议室 A」等 3 间，保证可正常下单）
-- ------------------------------------------------------------------------
INSERT INTO `resource_instance`
    (`type_id`, `instance_code`, `instance_name`, `capacity`, `location`,
     `attributes_json`, `status`, `deleted`, `create_time`, `update_time`)
SELECT
    rt.`id`,
    CONCAT('ROOM_', mr.`id`),
    mr.`room_name`,
    mr.`capacity`,
    mr.`location`,
    JSON_OBJECT(
        'legacyRoomId', mr.`id`,
        'description', mr.`description`
    ),
    mr.`enabled`,
    mr.`deleted`,
    mr.`create_time`,
    mr.`update_time`
FROM `meeting_room` mr
JOIN `resource_type` rt ON rt.`type_code` = 'MEETING_ROOM'
WHERE NOT EXISTS (
    SELECT 1 FROM `resource_instance` ri
    WHERE ri.`type_id` = rt.`id`
      AND ri.`instance_code` = CONCAT('ROOM_', mr.`id`)
);

-- ------------------------------------------------------------------------
-- 5. 工位实例 8 个（WS_DEMO_01~08，避开 seed_v2.sql 的 WS_A_01/WS_A_02）
-- ------------------------------------------------------------------------
INSERT INTO `resource_instance`
    (`type_id`, `instance_code`, `instance_name`, `capacity`, `location`,
     `attributes_json`, `status`, `deleted`)
SELECT
    rt.`id`,
    CONCAT('WS_DEMO_', LPAD(s.`n` + 1, 2, '0')),
    CONCAT('演示工位-', LPAD(s.`n` + 1, 2, '0')),
    1,
    CONCAT('演示楼', 3 + FLOOR(s.`n` / 4), '层'),
    JSON_OBJECT(
        'floor', 3 + FLOOR(s.`n` / 4),
        'zone', IF(s.`n` % 2 = 0, 'A', 'B'),
        'hasMonitor', IF(s.`n` % 3 = 0, 0, 1)
    ),
    1,
    0
FROM `tmp_demo_seq` s
JOIN `resource_type` rt ON rt.`type_code` = 'WORKSTATION'
WHERE s.`n` < 8
  AND NOT EXISTS (
      SELECT 1 FROM `resource_instance` ri
      WHERE ri.`type_id` = rt.`id`
        AND ri.`instance_code` = CONCAT('WS_DEMO_', LPAD(s.`n` + 1, 2, '0'))
  );

-- ------------------------------------------------------------------------
-- 6. 会议室预约 336 条（12 间 × 28 天）
--    每个实例每天最多 1 条，时间不重叠；
--    日期范围：今天前 21 天 ~ 今天后 6 天
--    时段：09:00~16:00 中按序号取 1 小时
--    状态：未来时段 → 待审批(部分) / 通过；历史时段 → 通过 / 驳回 / 取消
-- ------------------------------------------------------------------------
INSERT INTO `schedule_booking`
    (`booking_no`, `resource_type_id`, `resource_instance_id`, `user_id`,
     `start_time`, `end_time`, `quantity`, `status`, `plugin_code`, `source`,
     `ext_json`, `deleted`, `create_time`, `update_time`)
SELECT
    CONCAT('DEMO-', ri.`instance_code`, '-', LPAD(x.`n`, 3, '0')),
    rt.`id`,
    ri.`id`,
    u.`id`,
    x.`start_time`,
    x.`end_time`,
    1,
    x.`status`,
    'TIME_EXCLUSIVE',
    'API',
    CASE WHEN x.`status` = 2
         THEN JSON_OBJECT(
                  'title', x.`title`,
                  'reason', x.`reason`,
                  'rejectReason', '与部门全员会议冲突，建议改期')
         ELSE JSON_OBJECT(
                  'title', x.`title`,
                  'reason', x.`reason`)
    END,
    0,
    DATE_SUB(LEAST(x.`start_time`, NOW()), INTERVAL 2 DAY),
    DATE_SUB(LEAST(x.`start_time`, NOW()), INTERVAL 2 DAY)
FROM (
    SELECT
        s.`n`,
        TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL (s.`n` - 21) DAY),
                  MAKETIME(9 + (s.`n` % 8), 0, 0)) AS `start_time`,
        TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL (s.`n` - 21) DAY),
                  MAKETIME(10 + (s.`n` % 8), 0, 0)) AS `end_time`,
        CASE
            WHEN TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL (s.`n` - 21) DAY),
                           MAKETIME(9 + (s.`n` % 8), 0, 0)) > NOW()
                 THEN IF(s.`n` % 3 = 0, 0, 1)
            WHEN s.`n` % 11 = 0 THEN 3
            WHEN s.`n` % 9 = 0 THEN 2
            ELSE 1
        END AS `status`,
        CONCAT('演示会议-', LPAD(s.`n` + 1, 2, '0')) AS `title`,
        IF(s.`n` % 4 = 0, '部门例会', '项目沟通') AS `reason`
    FROM `tmp_demo_seq` s
    WHERE s.`n` < 28
) x
JOIN `meeting_room` mr
  ON mr.`room_name` LIKE '演示会议室%'
JOIN `resource_instance` ri
  ON ri.`instance_code` = CONCAT('ROOM_', mr.`id`)
JOIN `resource_type` rt
  ON rt.`id` = ri.`type_id`
 AND rt.`type_code` = 'MEETING_ROOM'
JOIN `sys_user` u
  ON u.`username` = CONCAT('emp', LPAD((x.`n` % 20) + 1, 2, '0'))
WHERE NOT EXISTS (
    SELECT 1 FROM `schedule_booking` sb
    WHERE sb.`booking_no` = CONCAT('DEMO-', ri.`instance_code`, '-', LPAD(x.`n`, 3, '0'))
);

-- ------------------------------------------------------------------------
-- 7. 工位预约 64 条（8 个工位 × 8 天）
--    日期范围：今天前 4 天 ~ 今天后 3 天；时段：14:00~17:00
-- ------------------------------------------------------------------------
INSERT INTO `schedule_booking`
    (`booking_no`, `resource_type_id`, `resource_instance_id`, `user_id`,
     `start_time`, `end_time`, `quantity`, `status`, `plugin_code`, `source`,
     `ext_json`, `deleted`, `create_time`, `update_time`)
SELECT
    CONCAT('DEMO-', ri.`instance_code`, '-', LPAD(x.`n`, 3, '0')),
    rt.`id`,
    ri.`id`,
    u.`id`,
    x.`start_time`,
    x.`end_time`,
    1,
    x.`status`,
    'TIME_EXCLUSIVE',
    'API',
    CASE WHEN x.`status` = 2
         THEN JSON_OBJECT(
                  'title', x.`title`,
                  'reason', x.`reason`,
                  'rejectReason', '工位当日已被占用，建议改期')
         ELSE JSON_OBJECT(
                  'title', x.`title`,
                  'reason', x.`reason`)
    END,
    0,
    DATE_SUB(LEAST(x.`start_time`, NOW()), INTERVAL 1 DAY),
    DATE_SUB(LEAST(x.`start_time`, NOW()), INTERVAL 1 DAY)
FROM (
    SELECT
        s.`n`,
        TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL (s.`n` - 4) DAY),
                  MAKETIME(14 + (s.`n` % 4), 0, 0)) AS `start_time`,
        TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL (s.`n` - 4) DAY),
                  MAKETIME(15 + (s.`n` % 4), 0, 0)) AS `end_time`,
        CASE
            WHEN TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL (s.`n` - 4) DAY),
                           MAKETIME(14 + (s.`n` % 4), 0, 0)) > NOW()
                 THEN IF(s.`n` % 3 = 0, 0, 1)
            WHEN s.`n` = 0 THEN 3
            WHEN s.`n` = 3 THEN 2
            ELSE 1
        END AS `status`,
        CONCAT('演示工位使用-', LPAD(s.`n` + 1, 2, '0')) AS `title`,
        IF(s.`n` % 2 = 0, '驻场办公', '远程协作') AS `reason`
    FROM `tmp_demo_seq` s
    WHERE s.`n` < 8
) x
JOIN `resource_instance` ri
  ON LEFT(ri.`instance_code`, 8) = 'WS_DEMO_'
JOIN `resource_type` rt
  ON rt.`id` = ri.`type_id`
 AND rt.`type_code` = 'WORKSTATION'
JOIN `sys_user` u
  ON u.`username` = CONCAT('emp', LPAD((x.`n` % 20) + 1, 2, '0'))
WHERE NOT EXISTS (
    SELECT 1 FROM `schedule_booking` sb
    WHERE sb.`booking_no` = CONCAT('DEMO-', ri.`instance_code`, '-', LPAD(x.`n`, 3, '0'))
);

-- ------------------------------------------------------------------------
-- 8. 预约明细：每条演示预约 1 条 SLOT 明细（item_key = 实例编码）
-- ------------------------------------------------------------------------
INSERT INTO `schedule_booking_item`
    (`booking_id`, `item_type`, `item_key`, `quantity`, `unit`, `ext_json`, `deleted`)
SELECT
    sb.`id`,
    'SLOT',
    ri.`instance_code`,
    1,
    'SLOT',
    JSON_OBJECT('demo', TRUE),
    0
FROM `schedule_booking` sb
JOIN `resource_instance` ri ON ri.`id` = sb.`resource_instance_id`
WHERE sb.`booking_no` LIKE 'DEMO-%'
  AND NOT EXISTS (
      SELECT 1 FROM `schedule_booking_item` item
      WHERE item.`booking_id` = sb.`id`
        AND item.`item_type` = 'SLOT'
        AND item.`item_key` = ri.`instance_code`
  );

COMMIT;

DROP TEMPORARY TABLE IF EXISTS `tmp_demo_seq`;

-- ========================================================================
-- 9. 校验段（只读）
--    期望：预订数量 400、明细 400、缺实例信息 0、重叠冲突 0
-- ========================================================================
SELECT 'sys_user(emp)' AS check_item,
       COUNT(*) AS actual_count,
       20 AS expect_count
FROM `sys_user`
WHERE `username` LIKE 'emp__'
UNION ALL
SELECT 'meeting_room(演示)', COUNT(*), 12
FROM `meeting_room`
WHERE `room_name` LIKE '演示会议室%'
UNION ALL
SELECT 'resource_instance(会议室)', COUNT(*), NULL
FROM `resource_instance` ri
JOIN `meeting_room` mr ON ri.`instance_code` = CONCAT('ROOM_', mr.`id`)
UNION ALL
SELECT 'resource_instance(演示工位)', COUNT(*), 8
FROM `resource_instance`
WHERE LEFT(`instance_code`, 8) = 'WS_DEMO_'
UNION ALL
SELECT 'schedule_booking(演示)', COUNT(*), 400
FROM `schedule_booking`
WHERE `booking_no` LIKE 'DEMO-%'
UNION ALL
SELECT 'schedule_booking_item(演示)', COUNT(*), 400
FROM `schedule_booking_item` item
JOIN `schedule_booking` sb ON sb.`id` = item.`booking_id`
WHERE sb.`booking_no` LIKE 'DEMO-%'
  AND item.`item_type` = 'SLOT';

-- 预约状态分布（0 待审批 / 1 通过 / 2 驳回 / 3 取消）
SELECT `status`, COUNT(*) AS booking_count
FROM `schedule_booking`
WHERE `booking_no` LIKE 'DEMO-%'
GROUP BY `status`
ORDER BY `status`;

-- 预约-实例-用户关联完整性（期望 missing_room_info = 0）
SELECT COUNT(*) AS missing_room_info
FROM `schedule_booking` sb
LEFT JOIN `resource_instance` ri ON ri.`id` = sb.`resource_instance_id`
LEFT JOIN `sys_user` u ON u.`id` = sb.`user_id`
WHERE sb.`booking_no` LIKE 'DEMO-%'
  AND (
      ri.`id` IS NULL
      OR u.`id` IS NULL
      OR JSON_UNQUOTE(JSON_EXTRACT(sb.`ext_json`, '$.title')) IS NULL
      OR ri.`instance_name` IS NULL
      OR CAST(JSON_UNQUOTE(JSON_EXTRACT(ri.`attributes_json`, '$.legacyRoomId')) AS UNSIGNED) IS NULL
  );

-- 演示预约时段重叠检查（仅统计待审批/已通过，期望 overlap_pairs = 0）
SELECT COUNT(*) AS overlap_pairs
FROM `schedule_booking` a
JOIN `schedule_booking` b
  ON b.`resource_instance_id` = a.`resource_instance_id`
 AND b.`id` > a.`id`
 AND b.`start_time` < a.`end_time`
 AND a.`start_time` < b.`end_time`
WHERE a.`booking_no` LIKE 'DEMO-%'
  AND b.`booking_no` LIKE 'DEMO-%'
  AND a.`deleted` = 0 AND b.`deleted` = 0
  AND a.`status` IN (0, 1) AND b.`status` IN (0, 1);

-- 未来待审批数量（用于演示审批流程；过期任务不会取消这些记录）
SELECT COUNT(*) AS future_pending
FROM `schedule_booking`
WHERE `booking_no` LIKE 'DEMO-%'
  AND `status` = 0
  AND `start_time` > NOW();

-- ========================================================================
-- 10. 回滚段（默认注释，确认后手动执行）
--     按演示前缀清理，顺序：明细 → 预约 → 实例 → 会议室 → 员工
-- ========================================================================
-- DELETE item FROM `schedule_booking_item` item
--   JOIN `schedule_booking` sb ON sb.`id` = item.`booking_id`
--  WHERE sb.`booking_no` LIKE 'DEMO-%';
--
-- DELETE FROM `schedule_booking` WHERE `booking_no` LIKE 'DEMO-%';
--
-- DELETE ri FROM `resource_instance` ri
--   JOIN `meeting_room` mr ON ri.`instance_code` = CONCAT('ROOM_', mr.`id`)
--  WHERE mr.`room_name` LIKE '演示会议室%';
--
-- DELETE FROM `resource_instance` WHERE LEFT(`instance_code`, 8) = 'WS_DEMO_';
--
-- DELETE FROM `meeting_room` WHERE `room_name` LIKE '演示会议室%';
--
-- DELETE FROM `sys_user` WHERE `username` LIKE 'emp__';
--
-- 说明：资源类型 MEETING_ROOM / WORKSTATION 由 schema.sql、seed_v2.sql 共同维护，
--       回滚演示数据时不删除字典，避免影响其他数据。
