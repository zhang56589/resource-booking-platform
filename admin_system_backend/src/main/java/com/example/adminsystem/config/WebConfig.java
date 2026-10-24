package com.example.adminsystem.config;

import com.example.adminsystem.interceptor.IdempotentInterceptor;
import com.example.adminsystem.interceptor.TokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Resource
    private TokenInterceptor tokenInterceptor;

    @Resource
    private IdempotentInterceptor idempotentInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ① TokenInterceptor 先注册（认证，写 userId 到 request）
        registry.addInterceptor(tokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/login",
                        "/api/register",
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                );

        // ② IdempotentInterceptor 后注册（依赖 TokenInterceptor 写入的 userId）
        //    只匹配写操作路径（POST/PUT/DELETE 对应 Controller 的写接口）
        registry.addInterceptor(idempotentInterceptor)
                .addPathPatterns(
                        "/api/booking/submit",
                        "/api/booking/approve",
                        "/api/booking/cancel/**",
                        "/api/room/add",
                        "/api/room/update",
                        "/api/room/delete/**",
                        "/api/room/enable/**",
                        "/api/room/disable/**",
                        "/api/user/role",
                        "/api/user/status",
                        "/api/user/resetPassword"
                );
    }
}
