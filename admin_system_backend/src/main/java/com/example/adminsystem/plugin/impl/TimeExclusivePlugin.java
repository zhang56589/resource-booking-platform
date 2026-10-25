package com.example.adminsystem.plugin.impl;

import com.example.adminsystem.entity.ResourceInstance;
import com.example.adminsystem.entity.ScheduleBooking;
import com.example.adminsystem.entity.ScheduleBookingItem;
import com.example.adminsystem.enums.BookingStatusEnum;
import com.example.adminsystem.mapper.ResourceInstanceMapper;
import com.example.adminsystem.mapper.ResourceTypeMapper;
import com.example.adminsystem.mapper.ScheduleBookingItemMapper;
import com.example.adminsystem.mapper.ScheduleBookingMapper;
import com.example.adminsystem.plugin.ScheduleActionEnum;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.plugin.ScheduleRulePlugin;
import com.example.adminsystem.plugin.support.BookingOutboxWriter;
import com.example.adminsystem.util.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 会议室时段独占插件：一次只能有一个生效预约占用某实例的某个时间段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeExclusivePlugin implements ScheduleRulePlugin {

    public static final String TYPE_MEETING_ROOM = "MEETING_ROOM";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ResourceTypeMapper resourceTypeMapper;
    private final ResourceInstanceMapper resourceInstanceMapper;
    private final ScheduleBookingMapper scheduleBookingMapper;
    private final ScheduleBookingItemMapper scheduleBookingItemMapper;
    private final BookingOutboxWriter bookingOutboxWriter;

    @Override
    public String pluginCode() {
        return "TIME_EXCLUSIVE";
    }

    @Override
    public boolean supports(String typeCode) {
        return TYPE_MEETING_ROOM.equals(typeCode);
    }

    @Override
    public String buildLockKey(ScheduleContext ctx) {
        ScheduleActionEnum action = ctx.getAction();
        if (action == ScheduleActionEnum.CANCEL) {
            return null;
        }
        if (action == ScheduleActionEnum.APPROVE) {
            return "schedule:approve:" + ctx.getBookingId();
        }
        if (ctx.getBookingId() != null) {
            return "schedule:approve:" + ctx.getBookingId();
        }
        return "schedule:room:" + ctx.getResourceInstanceId();
    }

    @Override
    public void validate(ScheduleContext ctx) {
        ScheduleActionEnum action = ctx.getAction();
        if (action == ScheduleActionEnum.BOOK) {
            validateBook(ctx);
            return;
        }
        if (action == ScheduleActionEnum.APPROVE || ctx.getBookingId() != null) {
            validateApprove(ctx);
            return;
        }
        throw new BusinessException(400, "非法调度动作");
    }

    private void validateBook(ScheduleContext ctx) {
        if (ctx.getResourceInstanceId() == null) {
            throw new BusinessException(400, "会议室不存在");
        }
        LocalDateTime startTime = ctx.getStartTime();
        LocalDateTime endTime = ctx.getEndTime();
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BusinessException(400, "开始时间必须早于结束时间");
        }
        if (startTime.isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "不能预约过去时间");
        }
        ResourceInstance instance = resourceInstanceMapper.selectById(ctx.getResourceInstanceId());
        if (instance == null) {
            throw new BusinessException(400, "会议室不存在");
        }
        if (!Integer.valueOf(1).equals(instance.getStatus())) {
            throw new BusinessException(400, "该会议室已禁用，无法预约");
        }
        if (hasConflict(ctx.getResourceInstanceId(), startTime, endTime, null)) {
            throw new BusinessException(400, "该会议室此时间段已被预约，时间冲突");
        }
    }

    private void validateApprove(ScheduleContext ctx) {
        if (ctx.getBookingId() == null) {
            throw new BusinessException(400, "预约id不能为空");
        }
        ScheduleBooking booking = scheduleBookingMapper.selectByIdForUpdate(ctx.getBookingId());
        if (booking == null) {
            throw new BusinessException(400, "预约记录不存在");
        }
        // 与 submit 使用同一实例行锁，串行化同房间的提交与审批
        resourceInstanceMapper.selectByIdForUpdate(booking.getResourceInstanceId());
        if (!BookingStatusEnum.PENDING.getCode().equals(booking.getResStatus())) {
            throw new BusinessException(400, "该预约已审批，不可重复操作");
        }
        Integer targetStatus = ctx.getTargetStatus();
        if (!BookingStatusEnum.PASS.getCode().equals(targetStatus)
                && !BookingStatusEnum.REJECT.getCode().equals(targetStatus)) {
            throw new BusinessException(400, "非法审批状态");
        }
        if (BookingStatusEnum.REJECT.getCode().equals(targetStatus)
                && (ctx.getRejectReason() == null || ctx.getRejectReason().trim().isEmpty())) {
            throw new BusinessException(400, "驳回必须填写驳回原因");
        }
        if (BookingStatusEnum.PASS.getCode().equals(targetStatus)
                && hasConflict(booking.getResourceInstanceId(), booking.getStartTime(),
                booking.getEndTime(), booking.getId())) {
            throw new BusinessException(400, "该会议室此时间段已被预约，时间冲突");
        }
    }

    @Override
    public void onBooked(ScheduleContext ctx) {
        if (ctx.getAction() == ScheduleActionEnum.BOOK) {
            createBooking(ctx);
            return;
        }
        approvePass(ctx);
    }

    private void createBooking(ScheduleContext ctx) {
        Long typeId = ctx.getResourceTypeId();
        if (typeId == null) {
            typeId = resourceTypeMapper.selectIdByTypeCode(ctx.getTypeCode());
        }
        if (typeId == null) {
            throw new BusinessException(500, "资源类型未初始化，请先执行主线0迁移脚本");
        }

        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("title", ctx.getTitle() == null ? "" : ctx.getTitle());
        ext.put("reason", ctx.getReason() == null ? "" : ctx.getReason());
        ext.put("roomName", ctx.getInstanceName() == null ? "" : ctx.getInstanceName());
        if (ctx.getRoomId() != null) {
            ext.put("legacyRoomId", ctx.getRoomId());
        }

        ScheduleBooking booking = new ScheduleBooking();
        booking.setBookingNo("SB" + UUID.randomUUID().toString().replace("-", ""));
        booking.setResourceTypeId(typeId);
        booking.setResourceInstanceId(ctx.getResourceInstanceId());
        booking.setUserId(ctx.getUserId());
        booking.setStartTime(ctx.getStartTime());
        booking.setEndTime(ctx.getEndTime());
        booking.setQuantity(ctx.getQuantity() == null ? 1 : ctx.getQuantity());
        booking.setResStatus(BookingStatusEnum.PENDING.getCode());
        booking.setPluginCode(pluginCode());
        booking.setSource(ctx.getSource() == null ? "API" : ctx.getSource());
        booking.setExtJson(writeJson(ext));
        booking.setDeleted(0);
        if (scheduleBookingMapper.insert(booking) != 1) {
            throw new BusinessException(500, "预约写入失败");
        }
        ctx.setBookingId(booking.getId());

        ScheduleBookingItem item = new ScheduleBookingItem();
        item.setBookingId(booking.getId());
        item.setItemType("SLOT");
        item.setItemKey(ctx.getInstanceCode());
        item.setQuantity(booking.getQuantity());
        item.setUnit("SLOT");
        Map<String, Object> itemExt = new LinkedHashMap<>();
        itemExt.put("roomId", ctx.getRoomId() == null ? "" : ctx.getRoomId());
        item.setExtJson(writeJson(itemExt));
        item.setDeleted(0);
        if (scheduleBookingItemMapper.insert(item) != 1) {
            throw new BusinessException(500, "预约明细写入失败");
        }

        bookingOutboxWriter.saveSubmit(ctx, booking);
        log.info("内核预约创建 bookingId={}, instanceId={}, userId={}",
                booking.getId(), ctx.getResourceInstanceId(), ctx.getUserId());
    }

    private void approvePass(ScheduleContext ctx) {
        ScheduleBooking booking = scheduleBookingMapper.selectByIdForUpdate(ctx.getBookingId());
        if (booking == null) {
            throw new BusinessException(400, "预约记录不存在");
        }
        Map<String, Object> ext = readJson(booking.getExtJson());
        ext.remove("rejectReason");
        booking.setResStatus(BookingStatusEnum.PASS.getCode());
        booking.setExtJson(writeJson(ext));
        if (scheduleBookingMapper.updateById(booking) != 1) {
            throw new BusinessException(500, "预约审批更新失败");
        }
        bookingOutboxWriter.saveApprove(ctx, booking, "PASS");
        log.info("内核预约通过 bookingId={}, adminId={}", booking.getId(), ctx.getAdminId());
    }

    @Override
    public void onRejected(ScheduleContext ctx) {
        ScheduleBooking booking = scheduleBookingMapper.selectByIdForUpdate(ctx.getBookingId());
        if (booking == null) {
            throw new BusinessException(400, "预约记录不存在");
        }
        Map<String, Object> ext = readJson(booking.getExtJson());
        ext.put("rejectReason", ctx.getRejectReason());
        booking.setResStatus(BookingStatusEnum.REJECT.getCode());
        booking.setExtJson(writeJson(ext));
        if (scheduleBookingMapper.updateById(booking) != 1) {
            throw new BusinessException(500, "预约驳回更新失败");
        }
        bookingOutboxWriter.saveApprove(ctx, booking, "REJECT");
        log.info("内核预约驳回 bookingId={}, adminId={}", booking.getId(), ctx.getAdminId());
    }

    @Override
    public void onCancelled(ScheduleContext ctx) {
        ScheduleBooking current = scheduleBookingMapper.selectById(ctx.getBookingId());
        if (current == null) {
            throw new BusinessException(400, "预约不存在");
        }
        resourceInstanceMapper.selectByIdForUpdate(current.getResourceInstanceId());
        ScheduleBooking booking = scheduleBookingMapper.selectByIdForUpdate(ctx.getBookingId());
        if (booking == null) {
            throw new BusinessException(400, "预约不存在");
        }
        if (!booking.getUserId().equals(ctx.getUserId())) {
            throw new BusinessException(400, "只能取消本人的预约");
        }
        if (!(BookingStatusEnum.PENDING.getCode().equals(booking.getResStatus())
                || BookingStatusEnum.PASS.getCode().equals(booking.getResStatus()))) {
            throw new BusinessException(400, "仅待审批、已通过的预约可以取消");
        }
        if (booking.getStartTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "会议已开始，无法取消");
        }
        booking.setResStatus(BookingStatusEnum.CANCEL.getCode());
        if (scheduleBookingMapper.updateById(booking) != 1) {
            throw new BusinessException(500, "预约取消失败");
        }
        log.info("内核预约取消 bookingId={}, userId={}", booking.getId(), ctx.getUserId());
    }

    private boolean hasConflict(Long instanceId, LocalDateTime startTime, LocalDateTime endTime, Long excludeId) {
        Long count = scheduleBookingMapper.countConflict(instanceId, startTime, endTime, excludeId);
        return count != null && count > 0;
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("预约 ext_json 解析失败，按空对象处理 json={}", json);
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            throw new BusinessException(500, "预约扩展字段序列化失败");
        }
    }
}
