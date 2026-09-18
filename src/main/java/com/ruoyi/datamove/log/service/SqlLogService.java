package com.ruoyi.datamove.log.service;

import cn.hutool.core.util.StrUtil;
import com.ruoyi.common.core.domain.LoginUser;
import com.ruoyi.datamove.log.domain.SyncSqlLog;
import com.ruoyi.datamove.log.mapper.SyncSqlLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;

/**
 * SQL 执行日志记录
 * - 记录数据工作台对数据源的每一次操作: SQL/库表/耗时/行数/状态/操作人/IP
 * - 日志写入失败只告警, 绝不影响业务主流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlLogService {

    /** SQL 文本最大保存长度(text 字段够用, 防止超长语句写库失败) */
    private static final int SQL_MAX_LEN = 20000;
    /** 错误信息最大保存长度 */
    private static final int ERR_MAX_LEN = 2000;

    private final SyncSqlLogMapper sqlLogMapper;

    /**
     * 记录一条操作日志
     *
     * @param dsId         数据源ID
     * @param dsName       数据源名称
     * @param dbName       数据库名
     * @param sourceType   来源 SQL_CONSOLE / DATA_BROWSE
     * @param sql          执行的SQL(可含多语句)
     * @param stmtCount    语句数
     * @param resultRows   结果集返回行数
     * @param affectedRows 增删改影响行数
     * @param costMs       耗时(毫秒)
     * @param status       SUCCESS / FAILED
     * @param errorMsg     错误信息(成功为 null)
     */
    public void record(Long dsId, String dsName, String dbName, String sourceType,
                       String sql, int stmtCount, long resultRows, long affectedRows,
                       long costMs, String status, String errorMsg) {
        try {
            SyncSqlLog entity = new SyncSqlLog();
            entity.setDsId(dsId);
            entity.setDsName(StrUtil.maxLength(dsName, 100));
            entity.setDbName(StrUtil.maxLength(dbName, 100));
            entity.setSourceType(sourceType);
            entity.setSqlText(StrUtil.maxLength(StrUtil.nullToEmpty(sql), SQL_MAX_LEN));
            entity.setStmtCount(stmtCount);
            entity.setResultRows(resultRows);
            entity.setAffectedRows(affectedRows);
            entity.setCostMs(costMs);
            entity.setStatus(status);
            entity.setErrorMsg(StrUtil.maxLength(errorMsg, ERR_MAX_LEN));
            entity.setOperName(currentUserName());

            HttpServletRequest request = currentRequest();
            if (request != null) {
                entity.setOperIp(StrUtil.maxLength(clientIp(request), 64));
                entity.setClientInfo(StrUtil.maxLength(request.getHeader("User-Agent"), 255));
            }
            entity.setCreateTime(new Date());
            sqlLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[SQL-LOG] 写入执行日志失败: {}", e.getMessage());
        }
    }

    /** 当前登录用户 */
    private String currentUserName() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof LoginUser) {
                String name = ((LoginUser) auth.getPrincipal()).getUserName();
                return StrUtil.isBlank(name) ? "anonymous" : name;
            }
        } catch (Exception ignored) {
        }
        return "anonymous";
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    /** 取真实客户端IP(兼容 nginx 等反向代理) */
    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (StrUtil.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
                int idx = ip.indexOf(',');
                return idx > 0 ? ip.substring(0, idx).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
