package com.example.adminsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.adminsystem.entity.SysLoginLog;
import com.example.adminsystem.entity.SysUser;
import com.example.adminsystem.mapper.SysUserMapper;
import com.example.adminsystem.service.SysLoginLogService;
import com.example.adminsystem.service.SysUserService;
import com.example.adminsystem.util.BusinessException;
import com.example.adminsystem.util.IpUtil;
import com.example.adminsystem.util.PasswordUtil;
import com.example.adminsystem.util.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.function.BiConsumer;

@Slf4j
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    // #2 登录状态魔法值抽常量（避免各处散写 0 / 1）
    private static final Integer LOGIN_STATUS_SUCCESS = 0;
    private static final Integer LOGIN_STATUS_FAIL = 1;
    // #2 用户启用状态魔法值抽常量
    private static final Integer USER_ENABLED_CODE = 1;

    @Resource
    private SysLoginLogService sysLoginLogService;

    @Override
    public boolean existUsername(String username) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        return this.exists(wrapper);
    }

    @Override
    public SysUser login(String username, String password) {
        String ip = getClientIp();
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getUsername, username);
        SysUser user = getOne(wrapper, false);

        if (user == null) {
            saveLoginLog(username, ip, LOGIN_STATUS_FAIL, "用户不存在");
            throw new BusinessException(Result.NOT_FOUND, "用户不存在");
        }
        // #2 用常量 + equals 替代硬编码数字 + 手动 null 判断
        // 原来：user.getEnabled() != null && user.getEnabled() != 1
        // 现在：!USER_ENABLED_CODE.equals(user.getEnabled())  —— User.enabled 为 null 时也能短路返回 true
        if (!USER_ENABLED_CODE.equals(user.getEnabled())) {
            saveLoginLog(username, ip, LOGIN_STATUS_FAIL, "账号已禁用");
            throw new BusinessException(Result.FORBIDDEN, "账号已被禁用，禁止登录");
        }
        if (!PasswordUtil.matches(password, user.getPassword())) {
            saveLoginLog(username, ip, LOGIN_STATUS_FAIL, "密码错误");
            throw new BusinessException(Result.BAD_REQUEST, "用户名或密码错误");
        }

        saveLoginLog(username, ip, LOGIN_STATUS_SUCCESS, "登录成功");
        return user;
    }

    @Override
    public boolean updateUserRole(Long id, Integer role) {
        // #6 泛型私有方法消除 3 段重复 "getById 判空 → new SysUser → setId → setXxx → updateById"
        return updateField(id, SysUser::setRole, role);
    }

    @Override
    public boolean updateUserEnabled(Long id, Integer enabled) {
        return updateField(id, SysUser::setEnabled, enabled);
    }

    @Override
    public boolean resetUserPassword(Long id, String rawPassword) {
        return updateField(id, SysUser::setPassword, PasswordUtil.encrypt(rawPassword));
    }

    /**
     * #6 泛型字段更新辅助：getById 判空 + 局部实体 + updateById 模板复用。
     */
    private <T> boolean updateField(Long id, BiConsumer<SysUser, T> setter, T value) {
        if (getById(id) == null) return false;
        SysUser u = new SysUser();
        u.setId(id);
        setter.accept(u, value);
        return updateById(u);
    }

    // ---- 私有辅助 ----

    private void saveLoginLog(String username, String ip, Integer status, String remark) {
        try {
            SysLoginLog logEntity = new SysLoginLog();
            logEntity.setUsername(username);
            logEntity.setIp(ip);
            logEntity.setStatus(status);
            logEntity.setRemark(remark);
            sysLoginLogService.save(logEntity);
        } catch (Exception e) {
            // #8 降级为 warn，不影响主业务
            log.warn("登录日志写入跳过(不影响主流程) username={}", username, e);
        }
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            // #1 IpUtil 统一
            return IpUtil.getClientIp(request);
        } catch (Exception e) {
            return null;
        }
    }
}
