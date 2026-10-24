package com.example.adminsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.adminsystem.dto.BookingQueryDTO;
import com.example.adminsystem.entity.MeetingBooking;
import com.example.adminsystem.vo.BookingVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MeetingBookingMapper extends BaseMapper<MeetingBooking> {

    // 管理员分页查询（对应xml: selectAdminBookingPage）
    Page<BookingVO> selectAdminBookingPage(Page<BookingVO> page,
                                           @Param("roomId") Long roomId,
                                           @Param("resStatus") Integer resStatus);

    // 用户个人分页查询（对应xml: selectUserBookingPage）
    Page<BookingVO> selectUserBookingPage(Page<BookingVO> page,
                                          @Param("userId") Long userId,
                                          @Param("roomId") Long roomId,
                                          @Param("resStatus") Integer resStatus);

    /**
     * 加行锁的自定义查询：@Select("SELECT *") 不走 MyBatis-Plus 全局驼峰映射，
     * 必须显式声明 @Results，否则 status → resStatus 等字段将为 null，
     * 后续 equals 校验直接 NPE 被全局兜底成 500。
     */
    @Select("SELECT * FROM meeting_booking WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    @Results(id = "meetingBookingResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "room_id", property = "roomId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "start_time", property = "startTime"),
            @Result(column = "end_time", property = "endTime"),
            @Result(column = "status", property = "resStatus"),
            @Result(column = "reject_reason", property = "rejectReason"),
            @Result(column = "title", property = "title"),
            @Result(column = "reason", property = "reason"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    MeetingBooking selectByIdForUpdate(@Param("id") Long id);

    Page<BookingVO> selectBookingVOPage(Page<BookingVO> page, @Param("dto") BookingQueryDTO dto);

    Page<BookingVO> selectUserBookingVOPage(Page<BookingVO> page,
                                            @Param("userId") Long userId,
                                            @Param("dto") BookingQueryDTO dto);
}
