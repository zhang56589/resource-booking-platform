package com.example.adminsystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.adminsystem.dto.ApproveBookingDTO;
import com.example.adminsystem.dto.BookingBatchApproveDTO;
import com.example.adminsystem.dto.BookingDTO;
import com.example.adminsystem.dto.BookingQueryDTO;
import com.example.adminsystem.vo.BookingVO;

public interface MeetingBookingService {

    void submitBooking(Long userId, BookingDTO dto);

    void approve(ApproveBookingDTO dto, Long adminId);

    /**
     * 批量审批：一个事务内循环调单次 approve 逻辑，任一失败整体回滚。
     * 错误信息带第一个失败的 bookingId 和原因。
     */
    void batchApprove(BookingBatchApproveDTO dto, Long adminId);

    /**管理员：查询全部预约分页 返回VO*/
    Page<BookingVO> adminPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto);

    /**员工：查询本人预约分页 返回VO*/
    Page<BookingVO> userPage(Integer pageNum, Integer pageSize, Long userId, BookingQueryDTO dto);

    /**员工取消预约*/
    void cancelBooking(Long bookingId, Long userId);

    /**管理员多条件筛选分页 返回VO*/
    Page<BookingVO> queryPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto);

    /**员工本人预约带条件筛选 返回VO*/
    Page<BookingVO> userQueryPage(Integer pageNum, Integer pageSize, BookingQueryDTO dto, Long userId);

    /**
     * 仅用于 Controller 层分布式锁 key 构造：根据会议室ID拿到资源实例ID。
     * 发生在抢锁之前，只读、不带 FOR UPDATE。
     */
    Long getInstanceIdByRoomId(Long roomId);
}
