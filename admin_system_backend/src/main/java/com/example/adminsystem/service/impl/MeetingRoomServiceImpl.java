package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.adminsystem.dto.RoomBatchDTO;
import com.example.adminsystem.dto.RoomDTO;
import com.example.adminsystem.entity.MeetingBooking;
import com.example.adminsystem.entity.MeetingRoom;
import com.example.adminsystem.entity.ResourceInstance;
import com.example.adminsystem.entity.ScheduleBooking;
import com.example.adminsystem.enums.BookingStatusEnum;
import com.example.adminsystem.enums.RoomStatusEnum;
import com.example.adminsystem.mapper.MeetingBookingMapper;
import com.example.adminsystem.mapper.MeetingRoomMapper;
import com.example.adminsystem.mapper.ResourceInstanceMapper;
import com.example.adminsystem.mapper.ResourceTypeMapper;
import com.example.adminsystem.mapper.ScheduleBookingMapper;
import com.example.adminsystem.plugin.impl.TimeExclusivePlugin;
import com.example.adminsystem.service.MeetingRoomService;
import com.example.adminsystem.util.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class MeetingRoomServiceImpl
        extends ServiceImpl<MeetingRoomMapper, MeetingRoom>
        implements MeetingRoomService {

    /** 会议室在资源实例表中的编码前缀：ROOM_{meeting_room.id} */
    private static final String INSTANCE_CODE_PREFIX = "ROOM_";

    @Resource
    private MeetingBookingMapper meetingBookingMapper;

    @Resource
    private ResourceInstanceMapper resourceInstanceMapper;

    @Resource
    private ResourceTypeMapper resourceTypeMapper;

    @Resource
    private ScheduleBookingMapper scheduleBookingMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        syncInstanceOnCreate(room);
        log.info("会议室新增成功 roomId={}, roomName={}", room.getId(), room.getRoomName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        syncInstanceOnUpdate(dto);
        log.info("会议室修改成功 roomId={}", dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoom(Long id) {
        if (getById(id) == null) {
            throw new BusinessException(404, "会议室不存在");
        }
        assertNoActiveBooking(id);
        if (!removeById(id)) {
            throw new BusinessException(400, "删除会议室失败");
        }
        resourceInstanceMapper.softDeleteByCode(instanceCode(id));
        log.info("会议室删除成功 roomId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableRoom(Long id) {
        changeStatus(id, RoomStatusEnum.ENABLE.getCode(), "启用会议室失败");
        log.info("会议室启用成功 roomId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableRoom(Long id) {
        changeStatus(id, RoomStatusEnum.DISABLE.getCode(), "禁用会议室失败");
        log.info("会议室禁用成功 roomId={}", id);
    }

    private void changeStatus(Long id, Integer status, String failMsg) {
        LambdaUpdateWrapper<MeetingRoom> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MeetingRoom::getId, id).set(MeetingRoom::getStatus, status);
        if (!update(wrapper)) {
            throw new BusinessException(400, failMsg);
        }
        resourceInstanceMapper.updateStatusByCode(instanceCode(id), status);
    }

    private void syncInstanceOnCreate(MeetingRoom room) {
        Long typeId = resourceTypeMapper.selectIdByTypeCode(TimeExclusivePlugin.TYPE_MEETING_ROOM);
        if (typeId == null) {
            throw new BusinessException(500, "资源类型未初始化，请先执行主线0迁移脚本");
        }
        ResourceInstance instance = new ResourceInstance();
        instance.setTypeId(typeId);
        instance.setInstanceCode(instanceCode(room.getId()));
        instance.setInstanceName(room.getRoomName());
        instance.setCapacity(room.getCapacity());
        instance.setLocation(room.getLocation());
        instance.setAttributesJson("{\"legacyRoomId\":" + room.getId() + "}");
        instance.setStatus(room.getStatus());
        instance.setDeleted(0);
        if (resourceInstanceMapper.insert(instance) != 1) {
            throw new BusinessException(400, "会议室资源实例同步失败");
        }
    }

    private void syncInstanceOnUpdate(RoomDTO dto) {
        if (dto.getRoomName() == null && dto.getCapacity() == null && dto.getLocation() == null) {
            return;
        }
        LambdaUpdateWrapper<ResourceInstance> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ResourceInstance::getInstanceCode, instanceCode(dto.getId()))
                .set(dto.getRoomName() != null, ResourceInstance::getInstanceName, dto.getRoomName())
                .set(dto.getCapacity() != null, ResourceInstance::getCapacity, dto.getCapacity())
                .set(dto.getLocation() != null, ResourceInstance::getLocation, dto.getLocation());
        resourceInstanceMapper.update(null, wrapper);
    }

    /**
     * 未完结预约校验：优先查统一预约表；实例缺失时回退老表，兼容迁移前的历史数据。
     */
    private void assertNoActiveBooking(Long roomId) {
        ResourceInstance instance = resourceInstanceMapper.selectByInstanceCode(instanceCode(roomId));
        Long count;
        if (instance != null) {
            LambdaQueryWrapper<ScheduleBooking> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ScheduleBooking::getResourceInstanceId, instance.getId())
                    .in(ScheduleBooking::getResStatus,
                            BookingStatusEnum.PENDING.getCode(),
                            BookingStatusEnum.PASS.getCode());
            count = scheduleBookingMapper.selectCount(wrapper);
        } else {
            LambdaQueryWrapper<MeetingBooking> legacyWrapper = new LambdaQueryWrapper<>();
            legacyWrapper.eq(MeetingBooking::getRoomId, roomId)
                    .in(MeetingBooking::getResStatus,
                            BookingStatusEnum.PENDING.getCode(),
                            BookingStatusEnum.PASS.getCode());
            count = meetingBookingMapper.selectCount(legacyWrapper);
        }
        if (count != null && count > 0) {
            throw new BusinessException(400, "该会议室存在未完结预约，无法删除");
        }
    }

    private String instanceCode(Long roomId) {
        return INSTANCE_CODE_PREFIX + roomId;
    }

    // ===================== 批量操作（P1-2）：一个事务内循环复用单次校验，任一失败整体回滚 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchEnable(RoomBatchDTO dto) {
        for (Long id : dto.getIds()) {
            try {
                enableRoom(id);
            } catch (BusinessException e) {
                throw new BusinessException(400, "批量启用失败：roomId=" + id + "，原因=" + e.getMessage());
            }
        }
        log.info("批量启用会议室完成 count={}", dto.getIds().size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDisable(RoomBatchDTO dto) {
        for (Long id : dto.getIds()) {
            try {
                disableRoom(id);
            } catch (BusinessException e) {
                throw new BusinessException(400, "批量禁用失败：roomId=" + id + "，原因=" + e.getMessage());
            }
        }
        log.info("批量禁用会议室完成 count={}", dto.getIds().size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(RoomBatchDTO dto) {
        for (Long id : dto.getIds()) {
            try {
                deleteRoom(id);
            } catch (BusinessException e) {
                throw new BusinessException(400, "批量删除失败：roomId=" + id + "，原因=" + e.getMessage());
            }
        }
        log.info("批量删除会议室完成 count={}", dto.getIds().size());
    }
}
