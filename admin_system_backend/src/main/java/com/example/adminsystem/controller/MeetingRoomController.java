package com.example.adminsystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.adminsystem.annotation.RequireAdmin;
import com.example.adminsystem.annotation.RequiresPermission;
import com.example.adminsystem.dto.RoomBatchDTO;
import com.example.adminsystem.dto.RoomDTO;
import com.example.adminsystem.entity.MeetingRoom;
import com.example.adminsystem.enums.RoomStatusEnum;
import com.example.adminsystem.service.MeetingRoomService;
import com.example.adminsystem.util.Result;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/room")
public class MeetingRoomController {

    @Resource
    private MeetingRoomService meetingRoomService;

    @RequireAdmin
    @GetMapping("/page")
    public Result<Page<MeetingRoom>> page(@RequestParam Integer pageNum,
                                          @RequestParam Integer pageSize) {
        Page<MeetingRoom> page = new Page<>(pageNum, pageSize);
        Page<MeetingRoom> pageData = meetingRoomService.page(page, new LambdaQueryWrapper<>());
        return Result.success(pageData);
    }

    /**
     * 管理员新增会议室
     */
    @RequireAdmin
    @PostMapping("/add")
    public Result<String> add(@Valid @RequestBody RoomDTO dto) {
        meetingRoomService.addRoom(dto);
        return Result.success("新增成功");
    }

    /**
     * 管理员编辑会议室
     */
    @RequireAdmin
    @PutMapping("/update")
    public Result<String> update(@Valid @RequestBody RoomDTO dto) {
        meetingRoomService.updateRoom(dto);
        return Result.success("修改成功");
    }

    /**
     * 管理员删除会议室
     */
    @RequireAdmin
    @DeleteMapping("/delete/{id}")
    public Result<String> delete(@PathVariable Long id) {
        meetingRoomService.deleteRoom(id);
        return Result.success("删除成功");
    }

    /**
     * 员工查询可用会议室
     */
    @GetMapping("/list")
    public Result<?> list() {
        LambdaQueryWrapper<MeetingRoom> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MeetingRoom::getStatus, RoomStatusEnum.ENABLE.getCode());
        return Result.success(meetingRoomService.list(wrapper));
    }

    /**
     * 管理员启用会议室
     */
    @RequireAdmin
    @PutMapping("/enable/{id}")
    public Result<String> enable(@PathVariable Long id) {
        meetingRoomService.enableRoom(id);
        return Result.success("启用成功");
    }

    /**
     * 管理员禁用会议室
     */
    @RequireAdmin
    @PutMapping("/disable/{id}")
    public Result<String> disable(@PathVariable Long id) {
        meetingRoomService.disableRoom(id);
        return Result.success("禁用成功");
    }

    // ===================== 批量操作（P1-2）：一个事务内全部成功或整体回滚 =====================

    @RequireAdmin
    @RequiresPermission("room:write")
    @PutMapping("/batch-enable")
    public Result<String> batchEnable(@Valid @RequestBody RoomBatchDTO dto) {
        meetingRoomService.batchEnable(dto);
        return Result.success("批量启用成功");
    }

    @RequireAdmin
    @RequiresPermission("room:write")
    @PutMapping("/batch-disable")
    public Result<String> batchDisable(@Valid @RequestBody RoomBatchDTO dto) {
        meetingRoomService.batchDisable(dto);
        return Result.success("批量禁用成功");
    }

    @RequireAdmin
    @RequiresPermission("room:write")
    @DeleteMapping("/batch-delete")
    public Result<String> batchDelete(@Valid @RequestBody RoomBatchDTO dto) {
        meetingRoomService.batchDelete(dto);
        return Result.success("批量删除成功");
    }
}
