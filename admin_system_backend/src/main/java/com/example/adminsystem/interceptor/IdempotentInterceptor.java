package com.example.adminsystem.interceptor;

import com.example.adminsystem.util.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 幂等拦截器 —— 基于 Redis SETNX 防重复提交（P0-4）。
 *
 * 工作模式（降级优先，不阻断现有前端）：
 *   1. X-Request-Id Header 为可选项：存在则校验，不存在则跳过（现有前端不强制改）
 *   2. Redis 不可达 → log.warn + 放行（幂等是增强，不是安全屏障）
 *   3. 只匹配写操作路径（POST/PUT/DELETE），GET 只读接口不受影响
 *
 * 拦截器顺序（WebConfig 里 TokenInterceptor 先注册，本拦截器后注册）：
 *   TokenInterceptor  preHandle → 校验 JWT → 写 userId 到 request
 *   IdempotentInterceptor preHandle → 取 userId + X-Request-Id → SETNX
 *
 * Key 设计：idempotent:{userId}:{requestId}，TTL=60s。
 * requestId 建议前端为每次写请求生成 UUID，同一业务动作重放时命中拦截。
 */
@Slf4j
@Component
public class IdempotentInterceptor implements HandlerInterceptor {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String KEY_PREFIX = "idempotent:";
    private static final long TTL_SECONDS = 60L;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj == null) {
            return true; // 未登录，TokenInterceptor 负责拦截；这里直接放行
        }

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            return true; // 未带 X-Request-Id，降级跳过
        }

        String key = KEY_PREFIX + userIdObj.toString() + ":" + requestId;
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            // Redisson trySet = SETNX + EX，返回 true 表示首次设置成功，false 表示已存在
            boolean isFirst = bucket.trySet("1", TTL_SECONDS, TimeUnit.SECONDS);
            if (!isFirst) {
                log.info("幂等拦截命中 userId={}, requestId={}, uri={}",
                        userIdObj, requestId, request.getRequestURI());
                writeFail(response, 400, "请勿重复提交");
                return false;
            }
        } catch (Exception ex) {
            // Redis 异常（不可达/超时等）→ warn + 放行，避免阻断主业务
            log.warn("幂等拦截器 Redis 异常，放行 key={}, uri={}", key, request.getRequestURI(), ex);
        }

        return true;
    }

    private void writeFail(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, msg)));
    }
}
