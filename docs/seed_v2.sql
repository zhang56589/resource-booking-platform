-- ========================================================================
-- 会议室预约系统 → 资源调度内核｜主线4 工位演示数据
-- 适用：MySQL 5.7+
-- 特性：按业务唯一键判重，可重复执行
-- 说明：工位复用 TIME_EXCLUSIVE 插件，仅新增字典与实例，不改任何 Java 插件代码
-- ========================================================================

SET NAMES utf8mb4;

-- 1. 资源类型：工位（绑定同一时段独占插件）
INSERT INTO `resource_type` (`type_code`, `type_name`, `plugin_code`, `approval_template_code`, `enabled`, `deleted`)
SELECT 'WORKSTATION', '工位', 'TIME_EXCLUSIVE', NULL, 1, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM `resource_type` WHERE `type_code` = 'WORKSTATION'
);

-- 2. 工位实例 2 个
INSERT INTO `resource_instance` (`type_id`, `instance_code`, `instance_name`, `capacity`, `location`, `attributes_json`, `status`, `deleted`)
SELECT t.`id`, 'WS_A_01', '工位A-01', 1, '3F 东区',
       JSON_OBJECT('floor', 3, 'zone', 'A', 'hasMonitor', 1), 1, 0
FROM `resource_type` t
WHERE t.`type_code` = 'WORKSTATION'
  AND NOT EXISTS (
      SELECT 1 FROM `resource_instance` i
      WHERE i.`type_id` = t.`id` AND i.`instance_code` = 'WS_A_01'
  );

INSERT INTO `resource_instance` (`type_id`, `instance_code`, `instance_name`, `capacity`, `location`, `attributes_json`, `status`, `deleted`)
SELECT t.`id`, 'WS_A_02', '工位A-02', 1, '3F 东区',
       JSON_OBJECT('floor', 3, 'zone', 'A', 'hasMonitor', 0), 1, 0
FROM `resource_type` t
WHERE t.`type_code` = 'WORKSTATION'
  AND NOT EXISTS (
      SELECT 1 FROM `resource_instance` i
      WHERE i.`type_id` = t.`id` AND i.`instance_code` = 'WS_A_02'
  );

-- ------------------------------------------------------------------------
-- 校验：工位字典与实例
-- ------------------------------------------------------------------------
SELECT t.`id` AS type_id, t.`type_code`, t.`plugin_code`,
       i.`id` AS instance_id, i.`instance_code`, i.`instance_name`, i.`status`
FROM `resource_type` t
LEFT JOIN `resource_instance` i
       ON i.`type_id` = t.`id` AND i.`deleted` = 0
WHERE t.`type_code` = 'WORKSTATION';

-- ------------------------------------------------------------------------
-- 回滚段（手工执行，默认注释）
-- ------------------------------------------------------------------------
-- UPDATE `schedule_booking` sb
--   JOIN `resource_type` t ON t.`id` = sb.`resource_type_id`
--    SET sb.`deleted` = 1, sb.`update_time` = NOW()
--  WHERE t.`type_code` = 'WORKSTATION';
-- DELETE i FROM `resource_instance` i
--   JOIN `resource_type` t ON t.`id` = i.`type_id`
--  WHERE t.`type_code` = 'WORKSTATION';
-- DELETE FROM `resource_type` WHERE `type_code` = 'WORKSTATION';
