package com.example.adminsystem.service.impl;

import com.example.adminsystem.enums.BookingStatusEnum;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.plugin.ScheduleRulePlugin;
import com.example.adminsystem.service.ScheduleTxService;
import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调度事务实现：所有插件钩子在同一事务内执行。
 * 独立 Bean 是为了让 @Transactional 代理生效，避免 ScheduleManager 自调用。
 */
@Slf4j
@Service
public class ScheduleTxServiceImpl implements ScheduleTxService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void book(ScheduleContext ctx, ScheduleRulePlugin plugin) {
        plugin.validate(ctx);
        plugin.onBooked(ctx);
        log.info("调度预约生效 typeCode={}, bookingId={}, plugin={}",
                ctx.getTypeCode(), ctx.getBookingId(), plugin.pluginCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(ScheduleContext ctx, ScheduleRulePlugin plugin) {
        Integer targetStatus = ctx.getTargetStatus();
        if (BookingStatusEnum.PASS.getCode().equals(targetStatus)) {
            plugin.validate(ctx);
            plugin.onBooked(ctx);
            log.info("调度审批通过 typeCode={}, bookingId={}, plugin={}",
                    ctx.getTypeCode(), ctx.getBookingId(), plugin.pluginCode());
            return;
        }
        if (BookingStatusEnum.REJECT.getCode().equals(targetStatus)) {
            log.info("调度审批驳回 typeCode={}, bookingId={}", ctx.getTypeCode(), ctx.getBookingId());
            return;
        }
        throw new BusinessException(Result.BAD_REQUEST, "审批状态不合法");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(ScheduleContext ctx, ScheduleRulePlugin plugin) {
        plugin.onCancelled(ctx);
        log.info("调度取消完成 typeCode={}, bookingId={}, plugin={}",
                ctx.getTypeCode(), ctx.getBookingId(), plugin.pluginCode());
    }
}
