package com.example.adminsystem.controller;

import com.example.adminsystem.annotation.RequiresPermission;
import com.example.adminsystem.dto.ScheduleBookDTO;
import com.example.adminsystem.service.GeneralScheduleService;
import com.example.adminsystem.util.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用预约入口：按资源类型路由到对应插件，接口本身不感知具体资源。
 * 锁与事务由 ScheduleManager 编排（锁在事务外），本层不持锁。
 */
@Slf4j
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private static final String MSG_UNAUTHORIZED = "未登录，请携带token";

    @Resource
    private GeneralScheduleService generalScheduleService;

    private Long requireUserId(HttpServletRequest request) {
        Object idObj = request.getAttribute("userId");
        return idObj == null ? null : Long.parseLong(idObj.toString());
    }

    @RequiresPermission("booking:submit")
    @PostMapping("/book")
    public Result<?> book(@Valid @RequestBody ScheduleBookDTO dto, HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }
        generalScheduleService.book(userId, dto);
        return Result.success("预约提交成功，等待管理员审批");
    }

    @DeleteMapping("/cancel/{id}")
    public Result<?> cancel(@PathVariable Long id, HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, MSG_UNAUTHORIZED);
        }
        generalScheduleService.cancel(id, userId);
        return Result.success("取消预约成功");
    }
}
