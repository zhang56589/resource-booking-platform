-- ========================================================================
-- 会议室预约系统 → 资源调度内核｜主线2 幂等索引脚本
-- 适用：MySQL 5.7+
-- 用途：已有库补齐内核表索引；新库执行 docs/schema.sql 时已包含，无需重复执行
-- 特性：按 information_schema 判重，可重复执行
-- ========================================================================

SET NAMES utf8mb4;

-- 使用连接时 -D 指定的库（information_schema 校验基于 DATABASE()）

DROP PROCEDURE IF EXISTS `sp_add_index_if_absent`;

DELIMITER $$
CREATE PROCEDURE `sp_add_index_if_absent`(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 冲突校验 / 分页查询索引
CALL sp_add_index_if_absent('schedule_booking',
    'idx_schedule_instance_time', '`resource_instance_id`, `start_time`, `end_time`');
CALL sp_add_index_if_absent('schedule_booking',
    'idx_schedule_user_status', '`user_id`, `status`');
CALL sp_add_index_if_absent('schedule_booking',
    'idx_schedule_type_time', '`resource_type_id`, `start_time`');
-- 主线3：用户分页 ORDER BY create_time DESC 覆盖索引
CALL sp_add_index_if_absent('schedule_booking',
    'idx_schedule_user_create', '`user_id`, `create_time`');
CALL sp_add_index_if_absent('schedule_booking_item',
    'idx_booking_item_booking', '`booking_id`');
CALL sp_add_index_if_absent('resource_instance',
    'idx_resource_instance_type_status', '`type_id`, `status`');

DROP PROCEDURE IF EXISTS `sp_add_index_if_absent`;

-- ------------------------------------------------------------------------
-- 校验：确认索引已存在
-- ------------------------------------------------------------------------
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN ('idx_schedule_instance_time', 'idx_schedule_user_status',
                     'idx_schedule_type_time', 'idx_schedule_user_create',
                     'idx_booking_item_booking', 'idx_resource_instance_type_status')
GROUP BY table_name, index_name;
