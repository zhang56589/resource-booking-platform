package com.example.adminsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.adminsystem.dto.RoomBatchDTO;
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

    /** 批量启用：一个事务内循环，任一失败整体回滚 */
    void batchEnable(RoomBatchDTO dto);

    /** 批量禁用：一个事务内循环，任一失败整体回滚 */
    void batchDisable(RoomBatchDTO dto);

    /** 批量删除：复用单次删除的未完结预约校验，任一失败整体回滚 */
    void batchDelete(RoomBatchDTO dto);
}
