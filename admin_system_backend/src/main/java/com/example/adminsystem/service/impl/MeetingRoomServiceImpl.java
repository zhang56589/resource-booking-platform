package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.adminsystem.dto.RoomDTO;
import com.example.adminsystem.entity.MeetingBooking;
import com.example.adminsystem.entity.MeetingRoom;
import com.example.adminsystem.enums.BookingStatusEnum;
import com.example.adminsystem.enums.RoomStatusEnum;
import com.example.adminsystem.mapper.MeetingBookingMapper;
import com.example.adminsystem.mapper.MeetingRoomMapper;
import com.example.adminsystem.service.MeetingRoomService;
import com.example.adminsystem.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MeetingRoomServiceImpl
        extends ServiceImpl<MeetingRoomMapper, MeetingRoom>
        implements MeetingRoomService {

    @Resource
    private MeetingBookingMapper meetingBookingMapper;

    @Override
    public void addRoom(RoomDTO dto) {
        MeetingRoom room = new MeetingRoom();
        room.setRoomName(dto.getRoomName());
        room.setCapacity(dto.getCapacity());
        room.setLocation(dto.getLocation());
        room.setDescription(dto.getDescription());
        room.setStatus(RoomStatusEnum.ENABLE.getCode());
        if (!save(room)) {
            throw new BusinessException(400, "新增会议室失败");
        }
        log.info("会议室新增成功 roomId={}, roomName={}", room.getId(), room.getRoomName());
    }

    @Override
    public void updateRoom(RoomDTO dto) {
        if (dto.getId() == null || getById(dto.getId()) == null) {
            throw new BusinessException(404, "会议室不存在");
        }
        LambdaUpdateWrapper<MeetingRoom> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MeetingRoom::getId, dto.getId())
                .set(dto.getRoomName() != null, MeetingRoom::getRoomName, dto.getRoomName())
                .set(dto.getCapacity() != null, MeetingRoom::getCapacity, dto.getCapacity())
                .set(dto.getLocation() != null, MeetingRoom::getLocation, dto.getLocation())
                .set(dto.getDescription() != null, MeetingRoom::getDescription, dto.getDescription());
        if (!update(wrapper)) {
            throw new BusinessException(400, "修改会议室失败");
        }
        log.info("会议室修改成功 roomId={}", dto.getId());
    }

    @Override
    public void deleteRoom(Long id) {
        if (getById(id) == null) {
            throw new BusinessException(404, "会议室不存在");
        }
        // 前置校验：存在待审批/已通过的未完结预约时禁止删除
        LambdaQueryWrapper<MeetingBooking> bookingWrapper = new LambdaQueryWrapper<>();
        bookingWrapper.eq(MeetingBooking::getRoomId, id)
                .in(MeetingBooking::getResStatus,
                        BookingStatusEnum.PENDING.getCode(),
                        BookingStatusEnum.PASS.getCode());
        Long count = meetingBookingMapper.selectCount(bookingWrapper);
        if (count != null && count > 0) {
            throw new BusinessException(400, "该会议室存在未完结预约，无法删除");
        }
        if (!removeById(id)) {
            throw new BusinessException(400, "删除会议室失败");
        }
        log.info("会议室删除成功 roomId={}", id);
    }

    @Override
    public void enableRoom(Long id) {
        LambdaUpdateWrapper<MeetingRoom> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MeetingRoom::getId, id).set(MeetingRoom::getStatus, RoomStatusEnum.ENABLE.getCode());
        if (!update(wrapper)) {
            throw new BusinessException(400, "启用会议室失败");
        }
        log.info("会议室启用成功 roomId={}", id);
    }

    @Override
    public void disableRoom(Long id) {
        LambdaUpdateWrapper<MeetingRoom> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MeetingRoom::getId, id).set(MeetingRoom::getStatus, RoomStatusEnum.DISABLE.getCode());
        if (!update(wrapper)) {
            throw new BusinessException(400, "禁用会议室失败");
        }
        log.info("会议室禁用成功 roomId={}", id);
    }
}
