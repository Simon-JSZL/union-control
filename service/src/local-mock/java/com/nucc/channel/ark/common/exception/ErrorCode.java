package com.nucc.channel.ark.common.exception;

/** Local compatibility subset. Production uses its existing interface. */
public interface ErrorCode {
    String getCode();
    String getDesc();
    ErrorCode getErrorCode();
    Throwable getThrowable();
}
