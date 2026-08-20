package com.nucc.channel.ark.common.exception;

/** Local compatibility subset. Production uses its existing enum unchanged. */
public enum BaseDataErrorCode implements ErrorCode {
    SYSTEM_INNER_ERROR("SYSTEM_INNER_ERROR", "系统内部错误");

    private final String code;
    private final String desc;

    BaseDataErrorCode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public String getCode() { return code; }

    @Override
    public String getDesc() { return desc; }

    @Override
    public ErrorCode getErrorCode() { return this; }

    @Override
    public Throwable getThrowable() { return null; }
}
