package com.example.adminsystem.util;

import com.example.adminsystem.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    @Resource
    private SecretKey jwtSecretKey;

    @Resource
    private JwtProperties jwtProperties;

    /**
     * 生成token
     */
    public String generateToken(Long userId, String username, Integer role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);

        return Jwts.builder()
                .claims(claims)
                .expiration(new Date(System.currentTimeMillis() + jwtProperties.getExpiration()))
                .signWith(jwtSecretKey)
                .compact();
    }

    /**
     * 解析token，获取Claims
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(jwtSecretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            throw new BusinessException(401, "token无效或已过期");
        }
    }
}
