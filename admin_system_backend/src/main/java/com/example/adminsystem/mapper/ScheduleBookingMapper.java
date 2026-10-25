package com.example.adminsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.adminsystem.dto.BookingQueryDTO;
import com.example.adminsystem.entity.ScheduleBooking;
import com.example.adminsystem.vo.BookingVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduleBookingMapper extends BaseMapper<ScheduleBooking> {

    Page<BookingVO> selectBookingVOPage(Page<BookingVO> page, @Param("dto") BookingQueryDTO dto);

    Page<BookingVO> selectUserBookingVOPage(Page<BookingVO> page,
                                            @Param("userId") Long userId,
                                            @Param("dto") BookingQueryDTO dto);

    @Select("SELECT * FROM schedule_booking WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    @Results(id = "scheduleBookingResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "booking_no", property = "bookingNo"),
            @Result(column = "resource_type_id", property = "resourceTypeId"),
            @Result(column = "resource_instance_id", property = "resourceInstanceId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "start_time", property = "startTime"),
            @Result(column = "end_time", property = "endTime"),
            @Result(column = "quantity", property = "quantity"),
            @Result(column = "status", property = "resStatus"),
            @Result(column = "plugin_code", property = "pluginCode"),
            @Result(column = "source", property = "source"),
            @Result(column = "ext_json", property = "extJson"),
            @Result(column = "deleted", property = "deleted"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    ScheduleBooking selectByIdForUpdate(@Param("id") Long id);

    /**
     * 时间冲突计数：只统计待审批/已通过，过期待审批不占用时段（懒失效）。
     */
    @Select("""
            SELECT COUNT(*) FROM schedule_booking
            WHERE resource_instance_id = #{instanceId}
              AND deleted = 0
              AND status IN (0, 1)
              AND start_time < #{endTime}
              AND end_time > #{startTime}
              AND NOT (status = 0 AND start_time < NOW())
              AND (#{excludeId} IS NULL OR id <> #{excludeId})
            """)
    Long countConflict(@Param("instanceId") Long instanceId,
                       @Param("startTime") java.time.LocalDateTime startTime,
                       @Param("endTime") java.time.LocalDateTime endTime,
                       @Param("excludeId") Long excludeId);

    @Update("UPDATE schedule_booking SET status = 3, update_time = NOW() "
            + "WHERE status = 0 AND deleted = 0 AND start_time < NOW()")
    int cancelExpiredPending();
}
