package com.example.adminsystem.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 细粒度权限注解。标注在 Controller 方法上，要求当前用户拥有指定权限码。
 * 权限码格式统一为 "模块:动作"，例如 booking:approve / room:write / user:write。
 *
 * 执行链路（两层独立并行）：
 *   TokenInterceptor   → 拦截 @RequireAdmin / @RequireRole（检查角色 Integer）
 *   PermissionAspect   → 拦截本注解 @RequiresPermission（查 sys_role_permission 映射）
 *
 * 两者互不依赖，TokenInterceptor 是红线模块，本注解 + 切面新增不改动它。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 权限码，如 "booking:approve" */
    String value();

    /** 无权限时的提示消息 */
    String message() default "无权限";
}
