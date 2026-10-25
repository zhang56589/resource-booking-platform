package com.example.adminsystem.service.impl;

import com.example.adminsystem.dto.ScheduleBookDTO;
import com.example.adminsystem.entity.ResourceInstance;
import com.example.adminsystem.entity.ScheduleBooking;
import com.example.adminsystem.mapper.ResourceInstanceMapper;
import com.example.adminsystem.mapper.ResourceTypeMapper;
import com.example.adminsystem.mapper.ScheduleBookingMapper;
import com.example.adminsystem.plugin.ScheduleActionEnum;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.plugin.ScheduleManager;
import com.example.adminsystem.service.GeneralScheduleService;
import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 通用预约薄门面：只做资源类型/实例校验与 DTO → ScheduleContext 转换。
 * 锁、事务、冲突校验、持久化全部由 ScheduleManager + 插件编排，新增同规则资源类型无需改本类。
 */
@Slf4j
@Service
public class GeneralScheduleServiceImpl implements GeneralScheduleService {

    @Resource
    private ScheduleManager scheduleManager;

    @Resource
    private ResourceTypeMapper resourceTypeMapper;

    @Resource
    private ResourceInstanceMapper resourceInstanceMapper;

    @Resource
    private ScheduleBookingMapper scheduleBookingMapper;

    @Override
    public void book(Long userId, ScheduleBookDTO dto) {
        String typeCode = dto.getResourceTypeCode() == null ? null : dto.getResourceTypeCode().trim();
        Long typeId = resourceTypeMapper.selectIdByTypeCode(typeCode);
        if (typeId == null) {
            throw new BusinessException(Result.BAD_REQUEST, "不支持的资源类型");
        }
        ResourceInstance instance = resourceInstanceMapper.selectById(dto.getInstanceId());
        if (instance == null) {
            throw new BusinessException(Result.BAD_REQUEST, "资源实例不存在");
        }
        // 防止拿其他类型的实例Id 冒充当前资源类型
        if (!typeId.equals(instance.getTypeId())) {
            throw new BusinessException(Result.BAD_REQUEST, "资源实例与资源类型不匹配");
        }

        ScheduleContext ctx = new ScheduleContext();
        ctx.setAction(ScheduleActionEnum.BOOK);
        ctx.setTypeCode(typeCode);
        ctx.setResourceTypeId(typeId);
        ctx.setResourceInstanceId(instance.getId());
        ctx.setInstanceCode(instance.getInstanceCode());
        ctx.setInstanceName(instance.getInstanceName());
        ctx.setUserId(userId);
        ctx.setStartTime(dto.getStartTime());
        ctx.setEndTime(dto.getEndTime());
        ctx.setQuantity(dto.getQuantity() == null ? 1 : dto.getQuantity());
        ctx.setTitle(dto.getTitle());
        ctx.setReason(dto.getReason());
        ctx.setSource("API");
        ctx.setUsername(currentUsername());

        scheduleManager.book(ctx);

        log.info("通用预约提交成功 typeCode={}, instanceId={}, bookingId={}, userId={}",
                typeCode, instance.getId(), ctx.getBookingId(), userId);
    }

    @Override
    public void cancel(Long bookingId, Long userId) {
        ScheduleBooking booking = scheduleBookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BusinessException(Result.BAD_REQUEST, "预约不存在");
        }
        String typeCode = resourceTypeMapper.selectTypeCodeById(booking.getResourceTypeId());
        if (typeCode == null) {
            throw new BusinessException(Result.BAD_REQUEST, "不支持的资源类型");
        }

        ScheduleContext ctx = new ScheduleContext();
        ctx.setAction(ScheduleActionEnum.CANCEL);
        ctx.setTypeCode(typeCode);
        ctx.setResourceTypeId(booking.getResourceTypeId());
        ctx.setResourceInstanceId(booking.getResourceInstanceId());
        ctx.setBookingId(bookingId);
        ctx.setUserId(userId);

        scheduleManager.cancel(ctx);

        log.info("通用预约取消成功 typeCode={}, bookingId={}, userId={}", typeCode, bookingId, userId);
    }

    private String currentUsername() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "";
        }
        Object username = attrs.getRequest().getAttribute("username");
        return username == null ? "" : username.toString();
    }
}
