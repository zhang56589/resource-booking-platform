package com.example.adminsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.adminsystem.entity.SysOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 本地消息表 Mapper。
 *
 * 轮询策略（MySQL 5.7 兼容）：
 *   1. 先 UPDATE 把 status=0 且 next_retry_time<=NOW() 的记录标记为 status=3（处理中），
 *      WHERE 条件自带索引，多实例 Scheduler 不会重复标记；
 *   2. 再 SELECT status=3 的记录发送 MQ；
 *   3. 成功 → markSent，失败 → markFailed（指数退避 + 重试计数）。
 */
@Mapper
public interface SysOutboxMapper extends BaseMapper<SysOutbox> {

    /**
     * 抢占一批待发送记录（先标记处理中，避免多实例 Scheduler 重复取）。
     * MySQL 5.7 不支持 FOR UPDATE SKIP LOCKED，改用 "先 UPDATE 再 SELECT" 方案。
     *
     * @param limit 批次上限
     * @return 本次被标记为处理中的记录数
     */
    int markProcessing(@Param("limit") int limit);

    /**
     * 查询所有 status=3（处理中）的记录，准备发 MQ。
     */
    List<SysOutbox> selectProcessing();

    /** 发送成功：标记 status=1（已发送） */
    int markSent(@Param("id") Long id);

    /**
     * 发送失败：retry_count++，指数退避计算 next_retry_time。
     * 若 retry_count+1 >= max_retry，标记 status=2（死信），不再重试。
     */
    int markFailed(@Param("id") Long id);
}
