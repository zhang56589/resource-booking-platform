package com.example.adminsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（Transaction Outbox Pattern）。
 *
 * 业务事务内写 outbox（和业务表同事务原子提交）；
 * OutboxScheduler 定时任务轮询待发送记录，调 RocketMQTemplate 发 MQ。
 * 若发送失败，自动指数退避重试（默认 3 次），超过标记死信供人工排查。
 *
 * 字段 status 取值：0=待发送 1=已发送 2=死信（重试超限）。
 */
@Data
@TableName("sys_outbox")
public class SysOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一 ID（UUID，消费端去重用） */
    private String msgId;

    /** RocketMQ Topic */
    private String topic;

    /** RocketMQ Tag（可选） */
    private String tag;

    /** 消息体 JSON */
    private String payload;

    /** 0=待发送 1=已发送 2=死信 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /** 下次重试时间（指数退避计算） */
    private LocalDateTime nextRetryTime;

    /** 写入时间 */
    @TableField(fill = com.baomidou.mybatisplus.core.handlers.MetaObjectHandler.FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = com.baomidou.mybatisplus.core.handlers.MetaObjectHandler.FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
