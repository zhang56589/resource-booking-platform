package com.example.adminsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.adminsystem.entity.MeetingRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MeetingRoomMapper extends BaseMapper<MeetingRoom> {

    /**
     * 加行锁的自定义查询：@Select("SELECT *") 不走 MyBatis-Plus 全局驼峰映射，
     * 必须显式声明 @Results，否则 enabled → status 等字段将为 null，
     * 后续 room.getStatus() 等于 null 导致状态判断失效/NPE。
     */
    @Select("SELECT * FROM meeting_room WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    @Results(id = "meetingRoomResult", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "room_name", property = "roomName"),
            @Result(column = "capacity", property = "capacity"),
            @Result(column = "location", property = "location"),
            @Result(column = "description", property = "description"),
            @Result(column = "enabled", property = "status"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime")
    })
    MeetingRoom selectByIdForUpdate(@Param("id") Long id);
}
