package com.example.adminsystem.scheduler;

import com.example.adminsystem.mapper.ScheduleBookingMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期待审批预约自动取消（批量懒失效兜底）。
 * 冲突校验 SQL 已对过期待审批做懒失效，本任务负责把状态最终落为 3=已取消。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.expire.enabled", havingValue = "true", matchIfMissing = true)
public class BookingExpireScheduler {

    @Resource
    private ScheduleBookingMapper scheduleBookingMapper;

    @Scheduled(cron = "${booking.expire.cron:0 0/5 * * * ?}")
    public void cancelExpiredPending() {
        try {
            int count = scheduleBookingMapper.cancelExpiredPending();
            if (count > 0) {
                log.info("过期待审批预约自动取消 count={}", count);
            }
        } catch (Exception ex) {
            log.error("过期待审批预约自动取消失败", ex);
        }
    }
}
