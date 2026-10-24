package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.adminsystem.dto.ApproveBookingDTO;
import com.example.adminsystem.dto.BookingDTO;
import com.example.adminsystem.dto.BookingQueryDTO;
import com.example.adminsystem.entity.MeetingBooking;
import com.example.adminsystem.entity.MeetingRoom;
import com.example.adminsystem.entity.OperationLog;
import com.example.adminsystem.entity.SysOutbox;
import com.example.adminsystem.entity.SysUser;
import com.example.adminsystem.enums.BookingStatusEnum;
import com.example.adminsystem.enums.RoomStatusEnum;
import com.example.adminsystem.mapper.MeetingBookingMapper;
import com.example.adminsystem.mapper.MeetingRoomMapper;
import com.example.adminsystem.mapper.OperationLogMapper;
import com.example.adminsystem.mapper.SysUserMapper;
import com.example.adminsystem.service.MeetingBookingService;
import com.example.adminsystem.service.SysOutboxService;
import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.IpUtil;
import com.example.adminsystem.vo.BookingVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Service 内职责：
 *   1. DB FOR UPDATE 行锁（分布式锁的 DB 兜底）
 *   2. 业务校验 + 时间冲突校验
 *   3. insert/update 持久化 + 操作日志
 *   4. 预约通知 —— 本地消息表（P0-2）：outbox 记录和业务表同事务原子提交，
 *      OutboxScheduler 定时任务轮询 outbox 调 RocketMQTemplate 发 MQ，指数退避自动重试。
 *      解决之前 afterCommit Producer 异常静默吞掉 → 预约成功但通知永远没发的问题。
 *
 * 分布式锁（Redisson）由外层 MeetingBookingController 持有 —— 锁包事务，
 * 事务提交发生在 Controller finally unlock 之前，避免并发请求读到未提交数据。
 */
@Slf4j
@Service
public class MeetingBookingServiceImpl
        extends ServiceImpl<MeetingBookingMapper, MeetingBooking>
        implements MeetingBookingService {

    @Resource
    private OperationLogMapper operationLogMapper;

    @Resource
    private MeetingRoomMapper meetingRoomMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    /** P0-2：本地消息表 Service，事务内直接写 outbox（与业务表原子提交） */
    @Resource
    private SysOutboxService outboxService;

    private static final ObjectMapper JSON = new ObjectMapper();

    // ===================== submitBooking =====================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitBooking(Long userId, BookingDTO dto) {
        if (dto.getRoomId() == null || dto.getStartTime() == null || dto.getEndTime() == null) {
            throw new BusinessException(400, "预约参数不能为空");
        }
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            throw new BusinessException(400, "会议主题不能为空");
        }

        LocalDateTime startTime = dto.getStartTime();
        LocalDateTime endTime = dto.getEndTime();
        Long roomId = dto.getRoomId();

        if (!startTime.isBefore(endTime)) {
            throw new BusinessException(400, "开始时间必须早于结束时间");
        }
        if (startTime.isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "不能预约过去时间");
        }

        MeetingRoom room = meetingRoomMapper.selectByIdForUpdate(roomId);
        if (room == null) {
            throw new BusinessException(400, "会议室不存在");
        }
        if (RoomStatusEnum.DISABLE.getCode().equals(room.getStatus())) {
            throw new BusinessException(400, "该会议室已禁用，无法预约");
        }

        if (hasTimeConflict(roomId, startTime, endTime, null)) {
            throw new BusinessException(400, "该会议室此时间段已被预约，时间冲突");
        }

        MeetingBooking booking = new MeetingBooking();
        booking.setRoomId(roomId);
        booking.setUserId(userId);
        booking.setStartTime(startTime);
        booking.setEndTime(endTime);
        booking.setTitle(dto.getTitle());
        booking.setReason(dto.getReason());
        booking.setResStatus(BookingStatusEnum.PENDING.getCode());
        save(booking);

        saveOperateLog(userId, "预约会议室",
                "提交预约，roomId:" + roomId + "，时间段：" + startTime + " ~ " + endTime + "，主题：" + dto.getTitle());

        // ===== P0-2：本地消息表 —— outbox 记录和 booking 同事务原子提交 =====
        // 不再用 afterCommit 回调（P0-2 前的方案），改为事务内直接写 outbox：
        //   事务回滚 → outbox 一起回滚（不会出现"预约回滚但 MQ 已发"）
        //   事务提交 → outbox status=0 → OutboxScheduler 定时任务自动发 MQ，重试兜底
        try {
            final ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            final String finalUsername = attrs != null && attrs.getRequest().getAttribute("username") != null
                    ? attrs.getRequest().getAttribute("username").toString() : "";
            SysOutbox outbox = buildSubmitOutbox(booking.getId(), finalUsername, room.getRoomName(),
                    startTime.toString(), endTime.toString(), userId);
            outboxService.save(outbox);
        } catch (Exception ex) {
            // outbox 写入失败必须让主事务回滚 —— 保证"预约成功但通知永远没发"不会发生
            log.error("Outbox 写入失败（触发事务回滚） bookingId={}", booking.getId(), ex);
            throw new BusinessException(500, "系统消息队列暂时不可用，请稍后重试");
        }

        log.info("预约提交成功 bookingId={}, roomId={}, userId={}", booking.getId(), roomId, userId);
    }

    // ===================== approve =====================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(ApproveBookingDTO dto, Long adminId) {
        MeetingBooking current = this.getById(dto.getBookingId());
        if (current == null) {
            throw new BusinessException(400, "预约记录不存在");
        }

        meetingRoomMapper.selectByIdForUpdate(current.getRoomId());
        MeetingBooking booking = baseMapper.selectByIdForUpdate(dto.getBookingId());
        if (booking == null) {
            throw new BusinessException(400, "预约记录不存在");
        }
        if (!BookingStatusEnum.PENDING.getCode().equals(booking.getResStatus())) {
            throw new BusinessException(400, "该预约已审批，不可重复操作");
        }

        Integer status = dto.getStatus();
        if (!BookingStatusEnum.PASS.getCode().equals(status)
                && !BookingStatusEnum.REJECT.getCode().equals(status)) {
            throw new BusinessException(400, "非法审批状态");
        }

        if (BookingStatusEnum.PASS.getCode().equals(status)
                && hasTimeConflict(booking.getRoomId(), booking.getStartTime(), booking.getEndTime(), booking.getId())) {
            throw new BusinessException(400, "该会议室此时间段已被预约，时间冲突");
        }

        if (BookingStatusEnum.REJECT.getCode().equals(dto.getStatus())) {
            if (dto.getRejectReason() == null || dto.getRejectReason().trim().isEmpty()) {
                throw new BusinessException(400, "驳回必须填写驳回原因");
            }
            booking.setRejectReason(dto.getRejectReason());
        } else {
            booking.setRejectReason(null);
        }
        booking.setResStatus(dto.getStatus());
        this.updateById(booking);

        String resultText = BookingStatusEnum.PASS.getCode().equals(dto.getStatus()) ? "通过" : "驳回";
        saveOperateLog(adminId, "预约审批", "审批预约id=" + dto.getBookingId() + "，审批结果：" + resultText);

        // ===== P0-2：本地消息表 —— outbox 记录和审批更新同事务原子提交 =====
        final String resultCode = BookingStatusEnum.PASS.getCode().equals(dto.getStatus()) ? "PASS" : "REJECT";
        try {
            String applicantUsername = getApplicantUsername(booking.getUserId());
            SysOutbox outbox = buildApproveOutbox(booking.getId(), applicantUsername,
                    resultCode, booking.getRejectReason(), adminId);
            outboxService.save(outbox);
        } catch (Exception ex) {
            log.error("Outbox 写入失败（触发事务回滚） bookingId={}", booking.getId(), ex);
            throw new BusinessException(500, "系统消息队列暂时不可用，请稍后重试");
        }

        log.info("预约审批完成 bookingId={}, result={}, adminId={}", booking.getId(), resultText, adminId);
    }

    // ===================== cancelBooking =====================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelBooking(Long bookingId, Long userId) {
        MeetingBooking current = getById(bookingId);
        if (current == null) {
            throw new BusinessException(400, "预约不存在");
        }

        meetingRoomMapper.selectByIdForUpdate(current.getRoomId());
        MeetingBooking booking = baseMapper.selectByIdForUpdate(bookingId);
        if (booking == null) {
            throw new BusinessException(400, "预约不存在");
        }
        if (!booking.getUserId().equals(userId)) {
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
        updateById(booking);
        saveOperateLog(userId, "取消预约", "取消预约id:" + bookingId);

        log.info("预约取消成功 bookingId={}, userId={}", bookingId, userId);
    }

    // ===================== 查询类方法（原样不动） =====================
    @Override
    public Page<BookingVO> adminPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto) {
        return queryPage(pageNum, pageSize, dto);
    }

    @Override
    public Page<BookingVO> userPage(Integer pageNum, Integer pageSize, Long userId, BookingQueryDTO dto) {
        return userQueryPage(pageNum, pageSize, dto, userId);
    }

    @Override
    public Page<BookingVO> queryPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto) {
        Page<BookingVO> page = buildPage(pageNum, pageSize);
        // #7 safeQuery 收敛 null 模板
        return baseMapper.selectBookingVOPage(page, safeQuery(dto));
    }

    @Override
    public Page<BookingVO> userQueryPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto, Long userId) {
        Page<BookingVO> page = buildPage(pageNum, pageSize);
        // #7 safeQuery 收敛 null 模板
        return baseMapper.selectUserBookingVOPage(page, userId, safeQuery(dto));
    }

    @Override
    public Long getRoomIdOfBooking(Long bookingId) {
        if (bookingId == null) return null;
        MeetingBooking booking = baseMapper.selectById(bookingId);
        return booking == null ? null : booking.getRoomId();
    }

    // ===================== 私有辅助 =====================

    private boolean hasTimeConflict(Long roomId, LocalDateTime startTime, LocalDateTime endTime, Long excludeBookingId) {
        LambdaQueryWrapper<MeetingBooking> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MeetingBooking::getRoomId, roomId)
                .in(MeetingBooking::getResStatus,
                        BookingStatusEnum.PENDING.getCode(), BookingStatusEnum.PASS.getCode())
                .lt(MeetingBooking::getStartTime, endTime)
                .gt(MeetingBooking::getEndTime, startTime);
        if (excludeBookingId != null) {
            wrapper.ne(MeetingBooking::getId, excludeBookingId);
        }
        return baseMapper.selectCount(wrapper) > 0;
    }

    private Page<BookingVO> buildPage(Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);
        return new Page<>(num, size);
    }

    /**
     * #7 safeQuery：4 处 "dto == null ? new BookingQueryDTO() : dto" 收敛到一个私有方法。
     */
    private BookingQueryDTO safeQuery(BookingQueryDTO dto) {
        return dto == null ? new BookingQueryDTO() : dto;
    }

    /**
     * H2: approve 场景下，在事务内查好申请人 username，
     * 避免异步发送时 request ThreadLocal 可能已被清理。
     */
    private String getApplicantUsername(Long userId) {
        if (userId == null) return "";
        SysUser applicant = sysUserMapper.selectById(userId);
        return applicant != null && applicant.getUsername() != null ? applicant.getUsername() : "";
    }

    // ===================== P0-2 Outbox 构建辅助 =====================

    private SysOutbox buildSubmitOutbox(Long bookingId, String username, String roomName,
                                        String startTimeStr, String endTimeStr, Long userId) {
        try {
            String payload = JSON.writeValueAsString(Map.of(
                    "bookingId", bookingId,
                    "username", username == null ? "" : username,
                    "roomName", roomName == null ? "" : roomName,
                    "startTime", startTimeStr,
                    "endTime", endTimeStr,
                    "operatorId", userId == null ? "" : userId,
                    "ts", System.currentTimeMillis()
            ));
            return buildOutbox("booking-notify-submit", null, payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox payload 序列化失败", e);
        }
    }

    private SysOutbox buildApproveOutbox(Long bookingId, String applicantUsername,
                                         String result, String rejectReason, Long adminId) {
        try {
            String payload = JSON.writeValueAsString(Map.of(
                    "bookingId", bookingId,
                    "applicantUsername", applicantUsername == null ? "" : applicantUsername,
                    "result", result,
                    "rejectReason", rejectReason == null ? "" : rejectReason,
                    "adminId", adminId == null ? "" : adminId,
                    "ts", System.currentTimeMillis()
            ));
            return buildOutbox("booking-notify-approve", null, payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox payload 序列化失败", e);
        }
    }

    private SysOutbox buildOutbox(String topic, String tag, String payload) {
        SysOutbox o = new SysOutbox();
        o.setMsgId(UUID.randomUUID().toString().replace("-", ""));
        o.setTopic(topic);
        o.setTag(tag);
        o.setPayload(payload);
        o.setStatus(0);
        o.setRetryCount(0);
        o.setMaxRetry(3);
        return o;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveOperateLog(Long userId, String operateType, String content) {
        OperationLog logEntity = new OperationLog();
        logEntity.setUserId(userId);
        logEntity.setOperateType(operateType);
        logEntity.setContent(content);

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            Object username = request.getAttribute("username");
            if (username != null) {
                logEntity.setUsername(username.toString());
            }
            logEntity.setMethod(request.getMethod());
            // #1 IpUtil 统一
            logEntity.setIp(IpUtil.getClientIp(request));
        }

        operationLogMapper.insert(logEntity);
    }
}
