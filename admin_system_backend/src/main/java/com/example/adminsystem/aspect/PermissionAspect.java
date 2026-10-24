package com.example.adminsystem.aspect;

import com.example.adminsystem.annotation.RequiresPermission;
import com.example.adminsystem.mapper.SysRolePermissionMapper;
import com.example.adminsystem.util.BusinessException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 细粒度权限切面 —— 拦截 @RequiresPermission 注解。
 *
 * 执行时机：在 TokenInterceptor（拦截器，preHandle 阶段）之后执行。
 *   TokenInterceptor 已经完成：JWT 校验 → request.setAttribute("role", Integer roleId)
 *   PermissionAspect 直接取 roleId → 查 sys_role_permission → 校验权限码
 *
 * 两层独立并行：
 *   @RequireAdmin / @RequireRole → TokenInterceptor 处理
 *   @RequiresPermission          → 本 AOP 处理
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Resource
    private SysRolePermissionMapper sysRolePermissionMapper;

    @Around("@annotation(rp)")
    public Object checkPermission(ProceedingJoinPoint pjp, RequiresPermission rp) throws Throwable {
        Integer roleId = resolveRoleId();
        if (roleId == null) {
            throw new BusinessException(401, "未登录");
        }

        List<String> codes = sysRolePermissionMapper.selectPermissionCodesByRoleId(roleId);
        if (codes != null && codes.contains(rp.value())) {
            return pjp.proceed();
        }

        log.warn("权限校验失败 roleId={}, required={}", roleId, rp.value());
        String msg = rp.message() == null || rp.message().isEmpty() ? "无权限" : rp.message();
        throw new BusinessException(403, msg);
    }

    /**
     * 从 request.getAttribute("role") 取 roleId。
     * TokenInterceptor 已经从 JWT claims 写入此值（Integer 类型，0=员工 / 1=管理员）。
     */
    private Integer resolveRoleId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        HttpServletRequest request = attrs.getRequest();
        Object roleObj = request.getAttribute("role");
        return roleObj instanceof Integer i ? i : null;
    }
}
