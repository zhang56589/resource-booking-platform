-- ========================================================================
-- 会议室预约系统 → 资源调度内核｜主线0 数据迁移脚本
-- 适用：MySQL 5.7+
-- 前置：先执行 docs/schema.sql（已追加 resource_type / resource_instance /
--       schedule_booking / schedule_booking_item 四张内核表）
-- 特性：
--   1. 可重复执行：所有写入均按业务唯一键判重，重复执行不会产生重复数据
--   2. 只增量迁移：不覆盖已迁移数据，老表只读、不修改、不删除
--   3. 末尾提供校验段与回滚段
-- ========================================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------------------
-- 0. 确保 MEETING_ROOM 资源类型存在
-- ------------------------------------------------------------------------
INSERT INTO `resource_type`
    (`type_code`, `type_name`, `plugin_code`, `approval_template_code`, `enabled`, `deleted`)
SELECT 'MEETING_ROOM', '会议室', 'TIME_EXCLUSIVE', NULL, 1, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `resource_type` WHERE `type_code` = 'MEETING_ROOM'
);

SET @meeting_type_id = (
    SELECT `id` FROM `resource_type` WHERE `type_code` = 'MEETING_ROOM' LIMIT 1
);

START TRANSACTION;

-- ------------------------------------------------------------------------
-- 1. meeting_room → resource_instance
--    instance_code = ROOM_{原会议室ID}
--    attributes_json 保留原 description 与 legacyRoomId
-- ------------------------------------------------------------------------
INSERT INTO `resource_instance`
    (`type_id`, `instance_code`, `instance_name`, `capacity`, `location`,
     `attributes_json`, `status`, `deleted`, `create_time`, `update_time`)
SELECT
    @meeting_type_id,
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
WHERE NOT EXISTS (
    SELECT 1 FROM `resource_instance` ri
    WHERE ri.`type_id` = @meeting_type_id
      AND ri.`instance_code` = CONCAT('ROOM_', mr.`id`)
);

-- ------------------------------------------------------------------------
-- 1.1 为老预约中引用、但会议室已物理删除的 room_id 补建占位实例
--     占位实例 status=0（禁用）、deleted=1（已删），只用于保留历史预约，
--     避免 JOIN 丢失孤儿预约；attributes_json.orphan=true 便于识别。
-- ------------------------------------------------------------------------
INSERT INTO `resource_instance`
    (`type_id`, `instance_code`, `instance_name`, `capacity`, `location`,
     `attributes_json`, `status`, `deleted`, `create_time`, `update_time`)
SELECT
    @meeting_type_id,
    CONCAT('ROOM_', x.`room_id`),
    CONCAT('历史会议室#', x.`room_id`),
    NULL,
    NULL,
    JSON_OBJECT('legacyRoomId', x.`room_id`, 'orphan', TRUE),
    0,
    1,
    NOW(),
    NOW()
FROM (
    SELECT DISTINCT `room_id` FROM `meeting_booking`
) x
WHERE NOT EXISTS (
    SELECT 1 FROM `meeting_room` mr WHERE mr.`id` = x.`room_id`
)
AND NOT EXISTS (
    SELECT 1 FROM `resource_instance` ri
    WHERE ri.`type_id` = @meeting_type_id
      AND ri.`instance_code` = CONCAT('ROOM_', x.`room_id`)
);

-- ------------------------------------------------------------------------
-- 2. meeting_booking → schedule_booking
--    booking_no = LEGACY-{原预约ID}
--    status 编码保持 0/1/2/3；plugin_code=TIME_EXCLUSIVE；source=LEGACY
--    title / reason / rejectReason 等老字段写入 ext_json，供主线3 VO 兼容
-- ------------------------------------------------------------------------
INSERT INTO `schedule_booking`
    (`booking_no`, `resource_type_id`, `resource_instance_id`, `user_id`,
     `start_time`, `end_time`, `quantity`, `status`, `plugin_code`, `source`,
     `ext_json`, `deleted`, `create_time`, `update_time`)
SELECT
    CONCAT('LEGACY-', mb.`id`),
    @meeting_type_id,
    ri.`id`,
    mb.`user_id`,
    mb.`start_time`,
    mb.`end_time`,
    1,
    mb.`status`,
    'TIME_EXCLUSIVE',
    'LEGACY',
    JSON_OBJECT(
        'legacyBookingId', mb.`id`,
        'legacyRoomId', mb.`room_id`,
        'title', mb.`title`,
        'reason', mb.`reason`,
        'rejectReason', mb.`reject_reason`
    ),
    mb.`deleted`,
    mb.`create_time`,
    mb.`update_time`
FROM `meeting_booking` mb
JOIN `resource_instance` ri
  ON ri.`type_id` = @meeting_type_id
 AND ri.`instance_code` = CONCAT('ROOM_', mb.`room_id`)
WHERE NOT EXISTS (
    SELECT 1 FROM `schedule_booking` sb
    WHERE sb.`booking_no` = CONCAT('LEGACY-', mb.`id`)
);

-- ------------------------------------------------------------------------
-- 3. 每条预约生成 1 条 schedule_booking_item（item_type=SLOT）
-- ------------------------------------------------------------------------
INSERT INTO `schedule_booking_item`
    (`booking_id`, `item_type`, `item_key`, `quantity`, `unit`, `ext_json`, `deleted`)
SELECT
    sb.`id`,
    'SLOT',
    ri.`instance_code`,
    1,
    'SLOT',
    JSON_OBJECT('legacyBookingId', mb.`id`),
    0
FROM `meeting_booking` mb
JOIN `schedule_booking` sb
  ON sb.`booking_no` = CONCAT('LEGACY-', mb.`id`)
JOIN `resource_instance` ri
  ON ri.`id` = sb.`resource_instance_id`
WHERE NOT EXISTS (
    SELECT 1 FROM `schedule_booking_item` item
    WHERE item.`booking_id` = sb.`id`
      AND item.`item_type` = 'SLOT'
      AND item.`item_key` = ri.`instance_code`
);

COMMIT;

-- ========================================================================
-- 4. 校验段（只读，不修改数据）
--    期望：所有 check_result = OK，time_mismatch=0，status_mismatch=0
-- ========================================================================
SELECT 'resource_instance' AS check_item,
       (SELECT COUNT(*) FROM `meeting_room`) AS legacy_count,
       (SELECT COUNT(*) FROM `resource_instance` ri
         JOIN `meeting_room` mr ON ri.`instance_code` = CONCAT('ROOM_', mr.`id`)
         WHERE ri.`type_id` = @meeting_type_id) AS new_count,
       IF(
           (SELECT COUNT(*) FROM `meeting_room`) =
           (SELECT COUNT(*) FROM `resource_instance` ri
             JOIN `meeting_room` mr ON ri.`instance_code` = CONCAT('ROOM_', mr.`id`)
             WHERE ri.`type_id` = @meeting_type_id),
           'OK', 'MISMATCH'
       ) AS check_result
UNION ALL
SELECT 'resource_instance_orphan',
       (SELECT COUNT(DISTINCT mb.`room_id`)
         FROM `meeting_booking` mb
         LEFT JOIN `meeting_room` mr ON mr.`id` = mb.`room_id`
         WHERE mr.`id` IS NULL),
       (SELECT COUNT(*) FROM `resource_instance` ri
         WHERE ri.`type_id` = @meeting_type_id
           AND JSON_UNQUOTE(JSON_EXTRACT(ri.`attributes_json`, '$.orphan')) = 'true'),
       IF(
           (SELECT COUNT(DISTINCT mb.`room_id`)
             FROM `meeting_booking` mb
             LEFT JOIN `meeting_room` mr ON mr.`id` = mb.`room_id`
             WHERE mr.`id` IS NULL) =
           (SELECT COUNT(*) FROM `resource_instance` ri
             WHERE ri.`type_id` = @meeting_type_id
               AND JSON_UNQUOTE(JSON_EXTRACT(ri.`attributes_json`, '$.orphan')) = 'true'),
           'OK', 'MISMATCH'
       )
UNION ALL
SELECT 'schedule_booking',
       (SELECT COUNT(*) FROM `meeting_booking`),
       (SELECT COUNT(*) FROM `schedule_booking`
         WHERE `source` = 'LEGACY' AND `booking_no` LIKE 'LEGACY-%'),
       IF(
           (SELECT COUNT(*) FROM `meeting_booking`) =
           (SELECT COUNT(*) FROM `schedule_booking`
             WHERE `source` = 'LEGACY' AND `booking_no` LIKE 'LEGACY-%'),
           'OK', 'MISMATCH'
       )
UNION ALL
SELECT 'schedule_booking_item',
       (SELECT COUNT(*) FROM `meeting_booking`),
       (SELECT COUNT(*) FROM `schedule_booking_item` item
         JOIN `schedule_booking` sb ON sb.`id` = item.`booking_id`
         WHERE sb.`source` = 'LEGACY' AND item.`item_type` = 'SLOT'),
       IF(
           (SELECT COUNT(*) FROM `meeting_booking`) =
           (SELECT COUNT(*) FROM `schedule_booking_item` item
             JOIN `schedule_booking` sb ON sb.`id` = item.`booking_id`
             WHERE sb.`source` = 'LEGACY' AND item.`item_type` = 'SLOT'),
           'OK', 'MISMATCH'
       );

-- 时间区间与状态比对（期望两列均为 0）
SELECT
    (SELECT COUNT(*) FROM `meeting_booking` mb
      JOIN `schedule_booking` sb ON sb.`booking_no` = CONCAT('LEGACY-', mb.`id`)
      WHERE sb.`start_time` <> mb.`start_time`
         OR sb.`end_time` <> mb.`end_time`) AS time_mismatch,
    (SELECT COUNT(*) FROM `meeting_booking` mb
      JOIN `schedule_booking` sb ON sb.`booking_no` = CONCAT('LEGACY-', mb.`id`)
      WHERE sb.`status` <> mb.`status`) AS status_mismatch;

-- ========================================================================
-- 5. 回滚段（默认全部注释，确认后手动执行）
-- ========================================================================
-- 5.1 仅回滚迁移数据，保留内核表结构：
-- DELETE FROM `schedule_booking_item`
--  WHERE `booking_id` IN (
--      SELECT `id` FROM `schedule_booking` WHERE `source` = 'LEGACY'
--  );
-- DELETE FROM `schedule_booking` WHERE `source` = 'LEGACY';
-- DELETE FROM `resource_instance`
--  WHERE `type_id` = (
--      SELECT `id` FROM `resource_type` WHERE `type_code` = 'MEETING_ROOM'
--  )
--    AND LEFT(`instance_code`, 5) = 'ROOM_';
--
-- 5.2 整体回滚主线0（DROP 新 4 表；老表 meeting_room / meeting_booking 不受影响）：
-- DROP TABLE IF EXISTS `schedule_booking_item`;
-- DROP TABLE IF EXISTS `schedule_booking`;
-- DROP TABLE IF EXISTS `resource_instance`;
-- DROP TABLE IF EXISTS `resource_type`;
