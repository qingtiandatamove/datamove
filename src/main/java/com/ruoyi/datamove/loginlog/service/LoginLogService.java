package com.ruoyi.datamove.loginlog.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.datamove.loginlog.domain.LoginLog;
import com.ruoyi.datamove.loginlog.mapper.LoginLogMapper;
import com.ruoyi.datamove.util.IpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;

/**
 * 登录日志: 记录 + 查询
 *
 * <p>记录入口只有一处 {@link #record(String, String, String, boolean, String)}, 由 AuthController 在
 * 三种登录方式(账号密码/短信/邮箱)的成功与失败分支里调用。
 *
 * <p>这里刻意不做异步: 登录频率远低于业务请求, 一次 insert + 一次带缓存的归属地解析开销可接受;
 * 异步反而会在容器关闭时丢日志。所有异常都被吞掉 —— 登录日志写不进去不能导致用户登不上系统。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final LoginLogMapper loginLogMapper;

    /* ============ 记录 ============ */

    /**
     * 写一条登录日志
     *
     * @param userName  登录账号 (失败时为输入值)
     * @param nickName  昵称快照, 可以为 null
     * @param loginType PASSWORD / SMS / EMAIL
     * @param success   是否成功
     * @param msg       成功文案或失败原因
     */
    public void record(String userName, String nickName, String loginType, boolean success, String msg) {
        try {
            LoginLog row = new LoginLog();
            row.setUserName(StrUtil.maxLength(StrUtil.trimToEmpty(userName), 50));
            row.setNickName(StrUtil.maxLength(StrUtil.trimToNull(nickName), 50));
            row.setLoginType(loginType);
            row.setStatus(success ? "0" : "1");
            row.setMsg(StrUtil.maxLength(StrUtil.trimToEmpty(msg), 255));

            HttpServletRequest req = currentRequest();
            String ip = IpUtils.clientIp(req);
            String ua = req == null ? "" : StrUtil.trimToEmpty(req.getHeader("User-Agent"));
            row.setIp(StrUtil.maxLength(ip, 64));
            row.setUserAgent(StrUtil.maxLength(ua, 500));
            row.setBrowser(StrUtil.maxLength(IpUtils.browserOf(ua), 100));
            row.setOs(StrUtil.maxLength(IpUtils.osOf(ua), 100));
            // 归属地走在线库并有缓存/超时兜底; 失败只留空, 不拖慢登录
            row.setLoginLocation(StrUtil.maxLength(IpUtils.resolveLocation(ip), 255));

            row.setLoginTime(new Date());
            loginLogMapper.insert(row);
        } catch (Exception e) {
            // 登录日志写入失败绝不能影响登录本身
            log.warn("[LOGIN-LOG] 写入登录日志失败 user={} err={}", userName, e.getMessage());
        }
    }

    /* ============ 查询 ============ */

    public PageResult<LoginLog> page(String keyword, String status, String loginType,
                                     String beginTime, String endTime,
                                     String orderByColumn, String isAsc, int pageNum, int pageSize) {
        Page<LoginLog> page = new Page<>(pageNum, pageSize);
        QueryWrapper<LoginLog> wrapper = new QueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("user_name", kw)
                    .or().like("nick_name", kw)
                    .or().like("ip", kw)
                    .or().like("login_location", kw)
                    .or().like("msg", kw));
        }
        if (StrUtil.isNotBlank(status)) wrapper.eq("status", status);
        if (StrUtil.isNotBlank(loginType)) wrapper.eq("login_type", loginType);
        if (StrUtil.isNotBlank(beginTime)) wrapper.ge("login_time", beginTime);
        if (StrUtil.isNotBlank(endTime)) wrapper.le("login_time", endTime);

        // 排序白名单: 防止前端传任意列名拼进 SQL
        String col = (orderByColumn == null || orderByColumn.isEmpty()) ? "id" : orderByColumn;
        if (!"id".equals(col) && !"login_time".equals(col)) col = "id";
        boolean asc = "asc".equalsIgnoreCase(isAsc);
        wrapper.orderBy(true, asc, col);

        IPage<LoginLog> result = loginLogMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal());
    }

    /** 删除单条 */
    public int remove(Long id) {
        return loginLogMapper.deleteById(id);
    }

    /** 清空全部 (页面上有二次确认) */
    public int clear() {
        return loginLogMapper.delete(new QueryWrapper<>());
    }

    /* ============ 内部 ============ */

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }
}
