package com.ruoyi.datamove.auth;

import com.ruoyi.common.core.domain.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

/**
 * 归属用户上下文 —— 多用户数据隔离的基础设施
 *
 * <p>数据隔离靠两件事配合:
 * <ol>
 *   <li>写入: {@code MetaHandlerConfig} 调 {@link #currentUserId()} 自动填 owner_id</li>
 *   <li>查询: {@code DataOwnerHandler} 多租户拦截器自动追加 {@code owner_id = 当前用户}</li>
 * </ol>
 *
 * <p>难点在于「后台线程没有登录上下文」: 同步引擎 / Canal 监听 / 数据校验 / 异步日志
 * 都跑在自己 new 出来的线程里, SecurityContext 是空的, 直接取当前用户会拿到 null,
 * 落库的日志就会变成「无人认领」—— 用户看不到自己任务的运行日志。
 * 所以任务进入后台线程前必须用 {@link #wrap(Long, Runnable)} 把归属用户带进去。
 */
public final class OwnerContext {

    /**
     * 归属未知时的兜底值: 0 表示「不属于任何注册用户」。
     * 只有超管看全量时才会看到这些数据, 普通用户永远查不到 —— 宁可看不见, 也不能串号。
     */
    public static final Long UNKNOWN_OWNER = 0L;

    private OwnerContext() {
    }

    /** 当前登录用户, 未登录 / 后台线程未传递时为 null */
    public static LoginUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof LoginUser ? (LoginUser) principal : null;
    }

    public static Long currentUserId() {
        LoginUser u = currentUser();
        return u == null ? null : u.getUserId();
    }

    public static String currentUserName() {
        LoginUser u = currentUser();
        return u == null ? null : u.getUserName();
    }

    /**
     * 是否超管: 超管不做数据隔离, 看全量 (否则管理员没法排查别人的任务)。
     *
     * <p>只认角色 (role_key = admin), 不能拿 user_id = 1 判断: 线上已经把 admin
     * 降级成普通操作员、另建 superadmin 持有超管角色, 按 ID 判断会把操作员误放行成超管,
     * 于是操作员能看到所有人的数据源和任务。
     */
    public static boolean isAdmin() {
        LoginUser u = currentUser();
        return u != null && u.isAdmin();
    }

    /**
     * 把归属用户带进后台线程: 线程内所有写库操作都能自动填上正确的 owner_id。
     *
     * @param ownerId 归属用户ID, 传 null 表示不设置上下文(原样执行)
     */
    public static Runnable wrap(Long ownerId, Runnable runnable) {
        if (ownerId == null || runnable == null) return runnable;
        return () -> {
            SecurityContextHolder.setContext(contextOf(ownerId));
            try {
                runnable.run();
            } finally {
                // 线程池里的线程会被复用, 不清理会把归属"借"给下一个任务
                SecurityContextHolder.clearContext();
            }
        };
    }

    /** 用给定的用户ID构造一个最小登录上下文 (仅供后台线程内部写库用) */
    public static SecurityContext contextOf(Long userId) {
        LoginUser u = new LoginUser();
        u.setUserId(userId);
        u.setUserName("system");
        // 故意不放 admin: 引擎内部若发起查询, 仍然按这个用户的归属过滤, 不会越权
        u.setRoles(Collections.singleton("owner"));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(u, null, Collections.emptyList()));
        return ctx;
    }
}
