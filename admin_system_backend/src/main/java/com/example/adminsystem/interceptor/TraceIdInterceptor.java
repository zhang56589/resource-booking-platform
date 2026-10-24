package com.example.adminsystem.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * P1-4：全链路 TraceId 拦截器（注册顺序在 TokenInterceptor 之前）。
 * 入站请求头自带 X-Trace-Id 则继承（前端/网关全链路串联），否则生成 UUID；
 * 写入 MDC 供 logback pattern %X{traceId} 输出，同时回写响应头。
 * afterCompletion 清 MDC，防止线程池复用导致 traceId 串号。
 */
@Component
public class TraceIdInterceptor implements HandlerInterceptor {

    private static final String TRACE_ID = "traceId";
    private static final String HEADER = "X-Trace-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.clear();
    }
}
