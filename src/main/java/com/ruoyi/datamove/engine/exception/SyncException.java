package com.ruoyi.datamove.engine.exception;

/**
 * 同步异常
 */
public class SyncException extends RuntimeException {

    public SyncException(String message) { super(message); }

    public SyncException(String message, Throwable cause) { super(message, cause); }
}
