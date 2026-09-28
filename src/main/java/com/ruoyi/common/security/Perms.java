package com.ruoyi.common.security;

import com.ruoyi.common.core.domain.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Set;

/**
 * 当前登录用户的权限读取与校验
 *
 * <p>本项目没有 RuoYi 的 {@code @ss.hasPermi} 表达式, 也没有开 {@code @PreAuthorize}:
 * 权限由 {@code JwtAuthenticationFilter} 在每次请求时算好放进 {@link LoginUser},
 * 需要鉴权的接口显式调用 {@link #require(String)} —— 前端只负责按钮显隐和路由守卫,
 * 后端是最终拦截 (否则普通操作员直接调接口就能绕过)。
 */
public final class Perms {

    /** 超管权限通配符 */
    public static final String ALL = "*:*:*";

    private Perms() {
    }

    /** 当前请求的权限集合 (超管是 *:*:*), 未登录/无权限时为空集 */
    public static Set<String> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        Set<String> perms = principal instanceof LoginUser ? ((LoginUser) principal).getPermissions() : null;
        return perms == null ? Collections.emptySet() : perms;
    }

    /** 是否有某个权限 (超管直通) */
    public static boolean has(String perm) {
        Set<String> perms = current();
        return perms.contains(ALL) || perms.contains(perm);
    }

    /**
     * 校验权限: 传入多个时满足任意一个即可, 都没有则抛异常
     *
     * @param perms 权限标识, 如 {@code system:user:edit}
     */
    public static void require(String... perms) {
        for (String p : perms) {
            if (has(p)) return;
        }
        throw new RuntimeException("没有该操作权限 (" + String.join(" 或 ", perms) + ")");
    }
}
