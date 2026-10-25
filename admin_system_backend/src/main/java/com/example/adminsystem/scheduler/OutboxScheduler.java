package com.example.adminsystem.scheduler;

import com.example.adminsystem.entity.SysOutbox;
import com.example.adminsystem.service.SysOutboxService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地消息表定时转发器（Transaction Outbox Pattern 第 2 阶段）。
 *
 * 执行流程（每 5s 一次）：
 *   1. markProcessing(100) —— 把 status=0 且到重试时间的记录标记为 3（处理中）
 *   2. selectProcessing()  —— 取出所有 status=3 的记录
 *   3. 逐条发 MQ → 成功 markSent → 失败 markFailed（指数退避）
 *
 * 并发安全：
 *   - markProcessing 用 UPDATE + LIMIT，多实例 Scheduler 不会标记同一批
 *   - RocketMQ 保证 "至少一次" 投递，msg_id 保证消费端可去重
 *
 * 可选加载：
 *   - RocketMQTemplate @Autowired(required=false)：不配 rocketmq.name-server 时 Bean 不加载
 *   - MQ 未加载时本任务直接返回，outbox 记录保持 status=0 待发送，不抢占、不累加重试次数
 *   - 开关：booking.outbox.enabled=false 可整体停用转发
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private static final int BATCH_SIZE = 100;

    @Resource
    private SysOutboxService outboxService;

    /** RocketMQTemplate 未配置 name-server 时为 null → 跳过 MQ 发送 */
    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @Scheduled(cron = "0/5 * * * * ?")
    public void relayOutbox() {
        // MQ 未配置：保持 status=0 待发送。若此时抢占再回退，retry_count 会累加至死信
        if (rocketMQTemplate == null) {
            return;
        }

        int grabbed = 0;
        try {
            grabbed = outboxService.markProcessing(BATCH_SIZE);
            if (grabbed == 0) {
                return; // 没有待发送记录，快速退出
            }
        } catch (Exception ex) {
            log.error("Outbox 抢占待发送批次失败", ex);
            return;
        }

        List<SysOutbox> processing;
        try {
            processing = outboxService.selectProcessing();
        } catch (Exception ex) {
            log.error("Outbox 查询处理中记录失败", ex);
            return;
        }

        if (processing.isEmpty()) {
            log.debug("Outbox 本次抢占 {} 条但查询为空（可能已被其他实例处理）", grabbed);
            return;
        }

        int success = 0, fail = 0;
        for (SysOutbox outbox : processing) {
            try {
                String destination = outbox.getTopic()
                        + (outbox.getTag() != null ? ":" + outbox.getTag() : "");
                rocketMQTemplate.asyncSend(destination, outbox.getPayload(),
                        new SendCallback() {
                            @Override
                            public void onSuccess(SendResult sendResult) {
                                if (SendStatus.SEND_OK.equals(sendResult.getSendStatus())) {
                                    try {
                                        outboxService.markSent(outbox.getId());
                                    } catch (Exception ignored) {
                                    }
                                    log.info("Outbox 消息发送成功 msgId={}, topic={}",
                                            outbox.getMsgId(), outbox.getTopic());
                                } else {
                                    log.warn("Outbox 消息发送回调失败 msgId={}, status={}",
                                            outbox.getMsgId(), sendResult.getSendStatus());
                                    try {
                                        outboxService.markFailed(outbox.getId());
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            @Override
                            public void onException(Throwable e) {
                                log.warn("Outbox 消息发送异常 msgId={}, error={}",
                                        outbox.getMsgId(), e.getMessage());
                                try {
                                    outboxService.markFailed(outbox.getId());
                                } catch (Exception ignored) {
                                }
                            }
                        });
                success++;
            } catch (Exception ex) {
                fail++;
                log.warn("Outbox 消息发送抛异常 msgId={}, topic={}, error={}",
                        outbox.getMsgId(), outbox.getTopic(), ex.getMessage());
                try {
                    outboxService.markFailed(outbox.getId());
                } catch (Exception ignored) {
                }
            }
        }
        log.info("Outbox 轮询完成 grabbed={}, success={}, fail={}", grabbed, success, fail);
    }
}
