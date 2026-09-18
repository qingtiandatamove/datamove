package com.ruoyi.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Token 工具 (RuoYi-Vue 风格)
 */
public class JwtUtils {

    public static final String CLAIM_KEY_USER_ID   = "user_id";
    public static final String CLAIM_KEY_USER_NAME = "user_name";

    /**
     * 生成 Token
     */
    public static String generate(Long userId, String userName, String secret, long expireSeconds) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_KEY_USER_ID, userId);
        claims.put(CLAIM_KEY_USER_NAME, userName);

        Date now = new Date();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userName)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expireSeconds * 1000))
                .signWith(SignatureAlgorithm.HS512, secret)
                .compact();
    }

    public static Claims parse(String token, String secret) {
        try {
            return Jwts.parser()
                    .setSigningKey(secret)
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }
}
