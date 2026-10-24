package com.example.adminsystem.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP 解析工具类 —— 处理多级反向代理场景下的真实客户端 IP。
 * 集中了工程内各处的重复 getClientIp 逻辑，改一处即全项目生效。
 */
public final class IpUtil {

    private IpUtil() {}

    /**
     * 按反代链顺序依次尝试取真实 IP：
     * X-Forwarded-For → Proxy-Client-IP → WL-Proxy-Client-IP → remoteAddr
     * X-Forwarded-For 可能是逗号分隔的多 IP，取第一段。
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) return null;
        String ip = request.getHeader("X-Forwarded-For");
        if (isBlankOrUnknown(ip)) ip = request.getHeader("Proxy-Client-IP");
        if (isBlankOrUnknown(ip)) ip = request.getHeader("WL-Proxy-Client-IP");
        if (isBlankOrUnknown(ip)) ip = request.getRemoteAddr();
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private static boolean isBlankOrUnknown(String ip) {
        return ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip);
    }
}
