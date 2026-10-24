package com.example.adminsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.adminsystem.dto.RoomDTO;
import com.example.adminsystem.entity.MeetingRoom;

public interface MeetingRoomService extends IService<MeetingRoom> {
    /** 新增会议室 */
    void addRoom(RoomDTO dto);

    /** 修改会议室（LambdaUpdateWrapper 只更新非空字段） */
    void updateRoom(RoomDTO dto);

    /** 删除会议室（前置校验未完结预约由 Service 内部完成） */
    void deleteRoom(Long id);

    /** 启用会议室 */
    void enableRoom(Long id);

    /** 禁用会议室 */
    void disableRoom(Long id);
}
