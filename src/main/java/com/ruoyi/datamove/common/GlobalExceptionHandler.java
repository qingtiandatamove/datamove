package com.ruoyi.datamove.common;

import com.ruoyi.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 匹配: Duplicate entry 'xxx' for key 'sync_task.uk_task_name' */
    private static final Pattern UNIQUE_MSG = Pattern.compile("Duplicate entry '([^']*)' for key '([^']*)'");

    @ExceptionHandler(Exception.class)
    public R<?> handle(Exception e) {
        log.error("server error", e);
        return R.fail(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public R<?> handleRuntime(RuntimeException e) {
        // 唯一索引冲突: 翻译为可读消息, 避免给用户看 SQL 堆栈
        String friendly = tryTranslateUniqueViolation(e);
        if (friendly != null) {
            log.warn("[unique-violation] {}", friendly);
            return R.fail(friendly);
        }
        log.warn("runtime error: {}", e.getMessage(), e);
        return R.fail(e.getMessage());
    }

    /**
     * 从异常 / cause 链里抓 SQLIntegrityConstraintViolationException, 提取冲突的字段值, 拼成中文提示。
     * 典型来源:
     *   - update() 撞 task_name (本类别的 update() 已加预校验, 这里兜底防并发 / 防 SQL bypass)
     *   - 任意 INSERT 撞 uk_task_name (clone 并发兜底)
     *   - 其他表的唯一索引 (后续扩展也自动受益)
     *
     * @return 友好消息; 不是唯一冲突返回 null
     */
    private String tryTranslateUniqueViolation(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof SQLIntegrityConstraintViolationException) {
                String msg = cur.getMessage();
                if (msg == null) return null;
                Matcher m = UNIQUE_MSG.matcher(msg);
                if (m.find()) {
                    String value = m.group(1);
                    String key = m.group(2);
                    // uk_task_name → 任务名; 其它索引(后续扩展)统一给「该字段值已存在」
                    if (key.contains("task_name")) {
                        return "任务名称「" + value + "」已存在, 请使用其他名称";
                    }
                    return "该记录已存在: " + value;
                }
                return "数据违反唯一约束, 请检查后重试";
            }
            cur = cur.getCause();
        }
        return null;
    }
}