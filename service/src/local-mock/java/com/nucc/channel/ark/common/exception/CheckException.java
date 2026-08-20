package com.nucc.channel.ark.common.exception;

/** Local compatibility copy of the production checked exception contract. */
public class CheckException extends Exception implements ErrorCode {
    private final ErrorCode errorCode;
    private final String extraMsg;

    public CheckException(ErrorCode errorCode, String message) {
        super(errorCode.getCode() + "|" + message);
        this.errorCode = errorCode;
        this.extraMsg = message;
    }

    @Override
    public String getCode() {
        return errorCode.getCode();
    }

    @Override
    public String getDesc() {
        return extraMsg;
    }

    @Override
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public Throwable getThrowable() {
        return getCause();
    }
}
