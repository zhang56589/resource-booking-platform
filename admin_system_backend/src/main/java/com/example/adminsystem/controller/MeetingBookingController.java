package com.example.adminsystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.adminsystem.annotation.RequireAdmin;
import com.example.adminsystem.annotation.RequiresPermission;
import com.example.adminsystem.dto.ApproveBookingDTO;
import com.example.adminsystem.dto.BookingBatchApproveDTO;
import com.example.adminsystem.dto.BookingDTO;
import com.example.adminsystem.dto.BookingQueryDTO;
import com.example.adminsystem.service.MeetingBookingService;
import com.example.adminsystem.util.Result;
import com.example.adminsystem.vo.BookingVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * 预约相关 Controller。
 *
 * ======== 分布式锁与事务边界 ========
 * Redisson 分布式锁在 Controller 层持有，Service 的 @Transactional 方法在锁内执行（锁包事务）。
 *   Controller tryLock → Service @Transactional → 事务提交 → Controller finally unlock。
 *   事务提交在锁释放之前，避免并发请求读到上一个事务的未提交数据。
 *
 * cancelBooking 不走分布式锁：DB FOR UPDATE 行锁 + 业务前置校验已足够。
 *
 * ======== 锁粒度说明 ========
 *   submitBooking —— 会议室粒度（booking:room:{roomId}）：串行化同房间所有时段的并发提交请求，防止同时段冲突。
 *   approve       —— 预约单粒度（booking:approve:{bookingId}）：串行化同一个 bookingId 的并发审批请求；
 *                     不同 bookingId 但同房间的审批不再互斥，DB FOR UPDATE 行锁 + PENDING 状态校验兜底。
 */
@Slf4j
@RestController
@RequestMapping("/api/booking")
public class MeetingBookingController {

    /** ====== 分布式锁配置（P0-1：submit 保持会议室粒度，approve 改为预约单粒度） ====== */
    /** submitBooking 锁：会议室粒度，串行化同房间所有时段的预约提交请求 */
    private static final String LOCK_KEY_ROOM = "booking:room:";
    /** approve 锁：预约单粒度，串行化同一个 bookingId 的并发审批请求 */
    private static final String LOCK_KEY_APPROVE = "booking:approve:";
    private static final long LOCK_WAIT_SECONDS = 5L;
    private static final long LOCK_LEASE_TIME = -1L; // Redisson watch dog 自动续期

    /** ====== 统一业务提示（#4 魔法字符串收敛） ====== */
    private static final String MSG_UNAUTHORIZED = "未登录，请携带token";

    @Resource
    private MeetingBookingService meetingBookingService;

    @Resource
    private RedissonClient redissonClient;

    /**
     * #3 统一从 request 属性里取 userId，null 安全。
     */
    private Long requireUserId(HttpServletRequest request) {
        Object idObj = request.getAttribute("userId");
        return idObj == null ? null : Long.parseLong(idObj.toString());
    }

    @PostMapping("/submit")
    public Result<?> submit(@Valid @RequestBody BookingDTO dto, HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }

        // ====== 分布式锁（锁包事务） ======
        String lockKey = LOCK_KEY_ROOM + dto.getRoomId();
        RLock rLock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = rLock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!locked) {
                log.info("预约提交抢锁失败 lockKey={}, userId={}", lockKey, userId);
                return Result.fail(Result.BAD_REQUEST, "该会议室正在被他人预约，请稍后重试");
            }
            meetingBookingService.submitBooking(userId, dto);
            return Result.success("预约提交成功，等待管理员审批");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("预约提交线程被中断 lockKey={}", lockKey, e);
            return Result.fail(Result.BAD_REQUEST, "操作被中断，请稍后重试");
        } finally {
            if (locked && rLock.isHeldByCurrentThread()) {
                rLock.unlock();
            }
        }
    }

    @RequireAdmin
    @GetMapping("/admin/page")
    public Result<Page<BookingVO>> adminPage(@RequestParam Integer pageNum,
                                             @RequestParam Integer pageSize,
                                             BookingQueryDTO dto) {
        return Result.success(meetingBookingService.adminPage(pageNum, pageSize, dto));
    }

    @GetMapping("/user/page")
    public Result<Page<BookingVO>> userPage(@RequestParam Integer pageNum,
                                            @RequestParam Integer pageSize,
                                            BookingQueryDTO dto,
                                            HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }
        return Result.success(meetingBookingService.userPage(pageNum, pageSize, userId, dto));
    }

    @RequireAdmin(message = "无权限，仅管理员可以执行审批操作")
    @PostMapping("/approve")
    public Result<?> approve(@Valid @RequestBody ApproveBookingDTO dto,
                             HttpServletRequest request) {
        // adminId 仅用于 Service 层写操作日志，@RequireAdmin 注解已确保当前用户是管理员
        Long adminId = requireUserId(request);
        if (adminId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }

        // ====== 分布式锁（P0-1：approve 锁粒度改为预约单粒度 bookingId） ======
        // 同一 bookingId 的并发审批请求会被 Redisson 串行化；
        // 不同 bookingId 但同房间的审批不再互斥，Service 内 FOR UPDATE + PENDING 状态校验兜底。
        String lockKey = LOCK_KEY_APPROVE + dto.getBookingId();
        RLock rLock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = rLock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!locked) {
                log.info("预约审批抢锁失败 lockKey={}, adminId={}", lockKey, adminId);
                return Result.fail(Result.BAD_REQUEST, "该预约正在被其他人处理，请稍后重试");
            }
            meetingBookingService.approve(dto, adminId);
            return Result.success("审批完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("预约审批线程被中断 lockKey={}", lockKey, e);
            return Result.fail(Result.BAD_REQUEST, "操作被中断，请稍后重试");
        } finally {
            if (locked && rLock.isHeldByCurrentThread()) {
                rLock.unlock();
            }
        }
    }

    @DeleteMapping("/cancel/{id}")
    public Result<?> cancel(@PathVariable Long id, HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }
        meetingBookingService.cancelBooking(id, userId);
        return Result.success("取消预约成功");
    }

    /**
     * P1-2 批量审批：锁在 Service 循环内逐元素持有（booking:approve:{bookingId}），
     * 一个事务内全部成功或整体回滚，错误信息带第一个失败的 bookingId 和原因。
     */
    @RequireAdmin(message = "无权限，仅管理员可以执行审批操作")
    @RequiresPermission("booking:approve")
    @PostMapping("/batch-approve")
    public Result<?> batchApprove(@Valid @RequestBody BookingBatchApproveDTO dto,
                                  HttpServletRequest request) {
        Long adminId = requireUserId(request);
        if (adminId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }
        meetingBookingService.batchApprove(dto, adminId);
        return Result.success("批量审批完成");
    }

    @RequireAdmin
    @GetMapping("/admin/query")
    public Result<Page<BookingVO>> adminQuery(@RequestParam Integer pageNum,
                                              @RequestParam Integer pageSize,
                                              BookingQueryDTO dto) {
        return Result.success(meetingBookingService.queryPage(pageNum, pageSize, dto));
    }

    @GetMapping("/user/query")
    public Result<Page<BookingVO>> userQuery(@RequestParam Integer pageNum,
                                             @RequestParam Integer pageSize,
                                             BookingQueryDTO dto,
                                             HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }
        return Result.success(meetingBookingService.userQueryPage(pageNum, pageSize, dto, userId));
    }
}
