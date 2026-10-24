package com.example.adminsystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.adminsystem.annotation.RequireAdmin;
import com.example.adminsystem.dto.UserPasswordDTO;
import com.example.adminsystem.dto.UserRoleDTO;
import com.example.adminsystem.dto.UserStatusDTO;
import com.example.adminsystem.entity.SysOperateLog;
import com.example.adminsystem.entity.SysUser;
import com.example.adminsystem.service.SysOperateLogService;
import com.example.adminsystem.service.SysUserService;
import com.example.adminsystem.util.IpUtil;
import com.example.adminsystem.util.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Resource
    private SysUserService sysUserService;

    @Resource
    private SysOperateLogService sysOperateLogService;

    /**
     * 管理员分页查询用户列表
     */
    @RequireAdmin
    @GetMapping("/page")
    public Result<Page<SysUser>> page(@RequestParam Integer pageNum,
                                      @RequestParam Integer pageSize) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SysUser::getCreateTime);
        Page<SysUser> pageData = sysUserService.page(page, wrapper);
        return Result.success(pageData);
    }

    /**
     * 修改用户角色
     */
    @RequireAdmin
    @PutMapping("/role")
    public Result<Void> updateRole(@Valid @RequestBody UserRoleDTO dto) {
        boolean ok = sysUserService.updateUserRole(dto.getId(), dto.getRole());
        if (!ok) {
            return Result.fail(Result.NOT_FOUND, "用户不存在或更新失败");
        }
        return Result.success();
    }

    /**
     * 启用/禁用用户
     */
    @RequireAdmin
    @PutMapping("/status")
    public Result<Void> updateStatus(@Valid @RequestBody UserStatusDTO dto) {
        boolean ok = sysUserService.updateUserEnabled(dto.getId(), dto.getEnabled());
        if (!ok) {
            return Result.fail(Result.NOT_FOUND, "用户不存在或更新失败");
        }
        return Result.success();
    }

    /**
     * 重置用户密码
     */
    @RequireAdmin
    @PutMapping("/resetPassword")
    public Result<Void> resetPassword(@Valid @RequestBody UserPasswordDTO dto,
                                      HttpServletRequest request) {
        boolean ok = sysUserService.resetUserPassword(dto.getId(), dto.getPassword());
        if (!ok) {
            return Result.fail(Result.NOT_FOUND, "用户不存在或重置失败");
        }
        saveOperateLog(request, dto.getId(), "RESET_PASSWORD");
        return Result.success();
    }

    // ---- 私有辅助 ----

    private void saveOperateLog(HttpServletRequest request, Long targetId, String operateType) {
        try {
            SysOperateLog logEntity = new SysOperateLog();
            logEntity.setOperatorId((Long) request.getAttribute("userId"));
            logEntity.setOperatorName((String) request.getAttribute("username"));
            logEntity.setTargetId(targetId);
            logEntity.setOperateType(operateType);
            // #1 IpUtil 统一
            logEntity.setIp(IpUtil.getClientIp(request));
            sysOperateLogService.save(logEntity);
        } catch (Exception e) {
            // #8 日志写入异常不影响主流程，降级为 warn
            log.warn("操作日志写入跳过(不影响主流程) targetId={}", targetId, e);
        }
    }
}
