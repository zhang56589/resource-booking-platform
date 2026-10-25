package com.example.adminsystem.plugin.support;

import com.example.adminsystem.entity.ScheduleBooking;
import com.example.adminsystem.entity.SysOutbox;
import com.example.adminsystem.entity.SysUser;
import com.example.adminsystem.mapper.SysUserMapper;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.service.SysOutboxService;
import com.example.adminsystem.util.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 预约通知 outbox 写入器：在插件的业务事务内调用，保证预约与消息记录原子提交。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingOutboxWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SysOutboxService outboxService;
    private final SysUserMapper sysUserMapper;

    public void saveSubmit(ScheduleContext ctx, ScheduleBooking booking) {
        String username = ctx.getUsername();
        if (username == null || username.isBlank()) {
            username = queryUsername(booking.getUserId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("username", username);
        payload.put("roomName", ctx.getInstanceName() == null ? "" : ctx.getInstanceName());
        payload.put("startTime", String.valueOf(booking.getStartTime()));
        payload.put("endTime", String.valueOf(booking.getEndTime()));
        payload.put("operatorId", booking.getUserId());
        payload.put("ts", System.currentTimeMillis());
        save("booking-notify-submit", payload);
    }

    public void saveApprove(ScheduleContext ctx, ScheduleBooking booking, String result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("applicantUsername", queryUsername(booking.getUserId()));
        payload.put("result", result);
        payload.put("rejectReason", ctx.getRejectReason() == null ? "" : ctx.getRejectReason());
        payload.put("adminId", ctx.getAdminId());
        payload.put("ts", System.currentTimeMillis());
        save("booking-notify-approve", payload);
    }

    private void save(String topic, Map<String, Object> payload) {
        try {
            SysOutbox outbox = new SysOutbox();
            outbox.setMsgId(UUID.randomUUID().toString().replace("-", ""));
            outbox.setTopic(topic);
            outbox.setTag(null);
            outbox.setPayload(JSON.writeValueAsString(payload));
            outbox.setStatus(0);
            outbox.setRetryCount(0);
            outbox.setMaxRetry(3);
            outboxService.save(outbox);
        } catch (Exception ex) {
            log.error("Outbox 写入失败（触发事务回滚） topic={}", topic, ex);
            throw new BusinessException(500, "系统消息队列暂时不可用，请稍后重试");
        }
    }

    private String queryUsername(Long userId) {
        if (userId == null) {
            return "";
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null || user.getUsername() == null ? "" : user.getUsername();
    }
}
