package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.adminsystem.dto.ApproveBookingDTO;
import com.example.adminsystem.dto.BookingBatchApproveDTO;
import com.example.adminsystem.dto.BookingDTO;
import com.example.adminsystem.dto.BookingQueryDTO;
import com.example.adminsystem.entity.OperationLog;
import com.example.adminsystem.entity.ResourceInstance;
import com.example.adminsystem.mapper.OperationLogMapper;
import com.example.adminsystem.mapper.ResourceInstanceMapper;
import com.example.adminsystem.mapper.ScheduleBookingMapper;
import com.example.adminsystem.plugin.ScheduleActionEnum;
import com.example.adminsystem.plugin.ScheduleContext;
import com.example.adminsystem.plugin.ScheduleManager;
import com.example.adminsystem.plugin.impl.TimeExclusivePlugin;
import com.example.adminsystem.service.MeetingBookingService;
import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.IpUtil;
import com.example.adminsystem.vo.BookingVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 预约薄门面：只负责 DTO → ScheduleContext 的转换与查询，校验、锁、事务、持久化全部由内核插件编排。
 * 单次 submit/approve/cancel 不再开启事务；事务边界在 ScheduleTxService，锁在 ScheduleManager。
 * batchApprove 保留 v1 的“一个事务、逐条加锁”语义。
 */
@Slf4j
@Service
public class MeetingBookingServiceImpl implements MeetingBookingService {

    @Resource
    private ScheduleManager scheduleManager;

    @Resource
    private ScheduleBookingMapper scheduleBookingMapper;

    @Resource
    private ResourceInstanceMapper resourceInstanceMapper;

    @Resource
    private OperationLogMapper operationLogMapper;

    @Override
    public void submitBooking(Long userId, BookingDTO dto) {
        if (dto.getRoomId() == null || dto.getStartTime() == null || dto.getEndTime() == null) {
            throw new BusinessException(400, "预约参数不能为空");
        }
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            throw new BusinessException(400, "会议主题不能为空");
        }

        ResourceInstance instance = loadInstance(dto.getRoomId());
        ScheduleContext ctx = new ScheduleContext();
        ctx.setAction(ScheduleActionEnum.BOOK);
        ctx.setTypeCode(TimeExclusivePlugin.TYPE_MEETING_ROOM);
        ctx.setResourceInstanceId(instance.getId());
        ctx.setInstanceCode(instance.getInstanceCode());
        ctx.setInstanceName(instance.getInstanceName());
        ctx.setRoomId(dto.getRoomId());
        ctx.setUserId(userId);
        ctx.setStartTime(dto.getStartTime());
        ctx.setEndTime(dto.getEndTime());
        ctx.setQuantity(1);
        ctx.setTitle(dto.getTitle());
        ctx.setReason(dto.getReason());
        ctx.setSource("API");
        ctx.setUsername(currentUsername());

        scheduleManager.book(ctx);

        saveOperateLogQuietly(userId, "预约会议室",
                "提交预约，roomId:" + dto.getRoomId() + "，时间段：" + dto.getStartTime()
                        + " ~ " + dto.getEndTime() + "，主题：" + dto.getTitle());
        log.info("预约提交成功 bookingId={}, roomId={}, userId={}",
                ctx.getBookingId(), dto.getRoomId(), userId);
    }

    @Override
    public void approve(ApproveBookingDTO dto, Long adminId) {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setAction(ScheduleActionEnum.APPROVE);
        ctx.setTypeCode(TimeExclusivePlugin.TYPE_MEETING_ROOM);
        ctx.setBookingId(dto.getBookingId());
        ctx.setTargetStatus(dto.getStatus());
        ctx.setRejectReason(dto.getRejectReason());
        ctx.setAdminId(adminId);

        scheduleManager.approve(ctx);

        String resultText = Integer.valueOf(1).equals(dto.getStatus()) ? "通过" : "驳回";
        saveOperateLogQuietly(adminId, "预约审批",
                "审批预约id=" + dto.getBookingId() + "，审批结果：" + resultText);
        log.info("预约审批完成 bookingId={}, result={}, adminId={}",
                dto.getBookingId(), resultText, adminId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchApprove(BookingBatchApproveDTO dto, Long adminId) {
        for (Long bookingId : dto.getBookingIds()) {
            try {
                ApproveBookingDTO single = new ApproveBookingDTO();
                single.setBookingId(bookingId);
                single.setStatus(dto.getStatus());
                single.setRejectReason(dto.getRejectReason());
                approve(single, adminId);
            } catch (BusinessException e) {
                throw new BusinessException(400,
                        "批量审批失败：bookingId=" + bookingId + "，原因=" + e.getMessage());
            }
        }
        log.info("批量审批完成 count={}, adminId={}", dto.getBookingIds().size(), adminId);
    }

    @Override
    public void cancelBooking(Long bookingId, Long userId) {
        ScheduleContext ctx = new ScheduleContext();
        ctx.setAction(ScheduleActionEnum.CANCEL);
        ctx.setTypeCode(TimeExclusivePlugin.TYPE_MEETING_ROOM);
        ctx.setBookingId(bookingId);
        ctx.setUserId(userId);

        scheduleManager.cancel(ctx);

        saveOperateLogQuietly(userId, "取消预约", "取消预约id:" + bookingId);
        log.info("预约取消成功 bookingId={}, userId={}", bookingId, userId);
    }

    @Override
    public Page<BookingVO> adminPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto) {
        return scheduleBookingMapper.selectBookingVOPage(buildPage(pageNum, pageSize), safeQuery(dto));
    }

    @Override
    public Page<BookingVO> userPage(Integer pageNum, Integer pageSize, Long userId, BookingQueryDTO dto) {
        return scheduleBookingMapper.selectUserBookingVOPage(buildPage(pageNum, pageSize), userId, safeQuery(dto));
    }

    @Override
    public Page<BookingVO> queryPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto) {
        return scheduleBookingMapper.selectBookingVOPage(buildPage(pageNum, pageSize), safeQuery(dto));
    }

    @Override
    public Page<BookingVO> userQueryPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto, Long userId) {
        return scheduleBookingMapper.selectUserBookingVOPage(buildPage(pageNum, pageSize), userId, safeQuery(dto));
    }

    @Override
    public Long getInstanceIdByRoomId(Long roomId) {
        return loadInstance(roomId).getId();
    }

    private ResourceInstance loadInstance(Long roomId) {
        if (roomId == null) {
            throw new BusinessException(400, "会议室不存在");
        }
        ResourceInstance instance = resourceInstanceMapper.selectByInstanceCode("ROOM_" + roomId);
        if (instance == null) {
            throw new BusinessException(400, "会议室不存在");
        }
        return instance;
    }

    private Page<BookingVO> buildPage(Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);
        return new Page<>(num, size);
    }

    private BookingQueryDTO safeQuery(BookingQueryDTO dto) {
        return dto == null ? new BookingQueryDTO() : dto;
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

    private void saveOperateLogQuietly(Long userId, String operateType, String content) {
        try {
            saveOperateLog(userId, operateType, content);
        } catch (Exception e) {
            log.warn("操作日志写入失败，不影响主流程 userId={}, operateType={}", userId, operateType, e);
        }
    }

    private void saveOperateLog(Long userId, String operateType, String content) {
        OperationLog logEntity = new OperationLog();
        logEntity.setUserId(userId);
        logEntity.setOperateType(operateType);
        logEntity.setContent(content);

        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            Object username = request.getAttribute("username");
            if (username != null) {
                logEntity.setUsername(username.toString());
            }
            logEntity.setMethod(request.getMethod());
            logEntity.setIp(IpUtil.getClientIp(request));
        }
        operationLogMapper.insert(logEntity);
    }
}
