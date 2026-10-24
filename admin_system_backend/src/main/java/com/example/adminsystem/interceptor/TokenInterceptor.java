package com.example.adminsystem.interceptor;

import com.example.adminsystem.annotation.RequireAdmin;
import com.example.adminsystem.annotation.RequireRole;
import com.example.adminsystem.enums.UserRoleEnum;
import com.example.adminsystem.util.JwtUtil;
import com.example.adminsystem.util.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * ====== 稳定模块：TokenInterceptor 不允许擅自修改核心鉴权逻辑 ======
 * 本轮只做 P2-6 级别的魔法常量清理，不改业务判断分支：
 *   - "Authorization" → HttpHeaders.AUTHORIZATION
 *   - "Bearer "      → 私有常量 BEARER_PREFIX + LENGTH
 *
 * 鉴权核心流程：
 *   1. 放行 OPTIONS（CORS 预检）
 *   2. 读 Header Authorization，缺失/格式错 → 401
 *   3. JwtUtil.parseToken → 捕获 JwtException / 签名不匹配 / 过期 → 401
 *   4. claims.get(userId / role / username) → 写 request.setAttribute
 *   5. checkAdmin：读取 @RequireAdmin / @RequireRole → 校验 role → 失败 403
 */
@Slf4j
@Component
public class TokenInterceptor implements HandlerInterceptor {

    /** P2-6：魔法常量集中声明，避免散落 "Bearer " 子串 */
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int BEARER_PREFIX_LENGTH = BEARER_PREFIX.length();

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String uri = request.getRequestURI();
        log.debug("拦截请求 uri={}, method={}", uri, request.getMethod());

        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        // ====== P2-6：HttpHeaders 常量 + Bearer 常量 ======
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeFail(response, 401, "未登录，请携带token");
            return false;
        }
        String token = authHeader.substring(BEARER_PREFIX_LENGTH);

        if (token.isBlank()) {
            writeFail(response, 401, "未登录，请携带token");
            return false;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            Object userIdObj = claims.get("userId");
            Object roleObj = claims.get("role");
            Object usernameObj = claims.get("username");

            if (userIdObj == null || roleObj == null) {
                log.debug("token内userId/role为空");
                writeFail(response, 401, "token无效");
                return false;
            }
            request.setAttribute("userId", Long.valueOf(userIdObj.toString()));
            request.setAttribute("role", Integer.valueOf(roleObj.toString()));
            if (usernameObj != null) {
                request.setAttribute("username", usernameObj.toString());
            }
        } catch (Exception e) {
            log.error("token解析异常 uri={}", uri, e);
            writeFail(response, 401, "token无效或已过期");
            return false;
        }

        return checkAdmin(request, response, handler);
    }

    private boolean checkAdmin(HttpServletRequest request,
                               HttpServletResponse response,
                               Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        String denyMessage = resolveAdminDenyMessage(handlerMethod);
        if (denyMessage == null) {
            return true;
        }
        Object roleObj = request.getAttribute("role");
        if (UserRoleEnum.ADMIN.getCode().equals(roleObj)) {
            return true;
        }
        writeFail(response, 403, denyMessage);
        return false;
    }

    private String resolveAdminDenyMessage(HandlerMethod handlerMethod) {
        RequireAdmin requireAdmin = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        if (requireAdmin == null) {
            requireAdmin = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
        }
        if (requireAdmin != null) {
            return requireAdmin.message();
        }
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        return requireRole == null ? null : requireRole.message();
    }

    private void writeFail(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(code, msg));
    }
}
