package com.example.adminsystem.plugin;

import com.example.adminsystem.service.ScheduleTxService;
import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 调度管理器：解析插件 → 分布式锁 → 独立事务 Bean → 释放锁。
 * 锁在事务外，事务在 ScheduleTxService 内，锁包事务骨架与 v1 保持一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleManager {

    private static final long LOCK_WAIT_SECONDS = 5L;
    private static final long LOCK_LEASE_TIME = -1L;

    private final PluginRegistry pluginRegistry;
    private final ScheduleTxService scheduleTxService;
    private final RedissonClient redissonClient;

    public void book(ScheduleContext ctx) {
        ScheduleRulePlugin plugin = pluginRegistry.resolveByTypeCode(ctx.getTypeCode());
        executeWithLock("book", ctx, plugin, () -> scheduleTxService.book(ctx, plugin));
    }

    public void approve(ScheduleContext ctx) {
        ScheduleRulePlugin plugin = pluginRegistry.resolveByTypeCode(ctx.getTypeCode());
        executeWithLock("approve", ctx, plugin, () -> scheduleTxService.approve(ctx, plugin));
    }

    public void cancel(ScheduleContext ctx) {
        ScheduleRulePlugin plugin = pluginRegistry.resolveByTypeCode(ctx.getTypeCode());
        executeWithLock("cancel", ctx, plugin, () -> scheduleTxService.cancel(ctx, plugin));
    }

    private void executeWithLock(String action, ScheduleContext ctx,
                                 ScheduleRulePlugin plugin, Runnable callback) {
        String lockKey = plugin.buildLockKey(ctx);
        if (lockKey == null || lockKey.isBlank()) {
            callback.run();
            return;
        }

        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!locked) {
                log.info("调度抢锁失败 action={}, typeCode={}, bookingId={}, lockKey={}",
                        action, ctx.getTypeCode(), ctx.getBookingId(), lockKey);
                throw new BusinessException(Result.BAD_REQUEST, "系统繁忙，请稍后重试");
            }
            callback.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("调度线程被中断 action={}, lockKey={}", action, lockKey, e);
            throw new BusinessException(Result.BAD_REQUEST, "操作被中断，请稍后重试");
        } finally {
            unlockQuietly(lock, locked, action, lockKey);
        }
    }

    private void unlockQuietly(RLock lock, boolean locked, String action, String lockKey) {
        if (!locked) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            // 释放失败不覆盖主流程异常
            log.warn("调度释放锁失败 action={}, lockKey={}", action, lockKey, e);
        }
    }
}
