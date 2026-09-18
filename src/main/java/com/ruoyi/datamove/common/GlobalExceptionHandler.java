package com.ruoyi.datamove.common;

import com.ruoyi.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public R<?> handle(Exception e) {
        log.error("server error", e);
        return R.fail(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public R<?> handleRuntime(RuntimeException e) {
        log.warn("runtime error: {}", e.getMessage());
        return R.fail(e.getMessage());
    }
}
