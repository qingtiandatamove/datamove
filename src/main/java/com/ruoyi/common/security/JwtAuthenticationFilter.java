package com.ruoyi.common.security;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 认证过滤器
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${token.header}")
    private String header;

    @Value("${token.secret}")
    private String secret;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = request.getHeader(header);
        if (StrUtil.isNotBlank(token) && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (StrUtil.isNotBlank(token)) {
            Claims claims = JwtUtils.parse(token, secret);
            if (claims != null) {
                LoginUser lu = new LoginUser();
                lu.setUserId(claims.get(JwtUtils.CLAIM_KEY_USER_ID, Long.class));
                lu.setUserName(claims.getSubject());
                // 角色权限先简化默认:admin
                lu.setRoles(Collections.singleton("admin"));
                lu.setPermissions(Collections.emptySet());
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(lu, null,
                                lu.getRoles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                                        .collect(Collectors.toList()));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                response.setStatus(401);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getWriter(), R.fail("登录已过期,请重新登录"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
