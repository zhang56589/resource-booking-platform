package com.example.adminsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.adminsystem.entity.SysOutbox;

import java.util.List;

/**
 * 本地消息表 Service（Transaction Outbox Pattern）。
 *
 * 业务事务内调用 save() 把 outbox 记录和业务表同事务原子提交；
 * OutboxScheduler 定时任务轮询 outbox 发 MQ，自动指数退避重试。
 */
public interface SysOutboxService extends IService<SysOutbox> {

    /**
     * 抢占一批待发送记录（标记为处理中）。
     * @return 被标记的记录数
     */
    int markProcessing(int limit);

    /** 查询所有 status=3（处理中）的记录，准备发 MQ */
    List<SysOutbox> selectProcessing();

    /** 发送成功：标记 status=1（已发送） */
    int markSent(Long id);

    /** 发送失败：retry_count++，指数退避；超重试次数标记死信 */
    int markFailed(Long id);
}
