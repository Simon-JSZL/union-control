package com.epcc.dubbo.result;

/** Local compile-time compatibility stub. Production supplies this class. */
public final class Result<T> {
    private final boolean success;
    private final T result;
    private final String errorCode;
    private final String errorMsg;

    private Result(boolean success, T result, String errorCode, String errorMsg) {
        this.success = success;
        this.result = result;
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
    }

    public static <T> Result<T> success(T result) {
        return new Result<>(true, result, null, null);
    }

    public static <T> Result<T> failure(String errorCode, String errorMsg) {
        return new Result<>(false, null, errorCode, errorMsg);
    }

    public boolean isSuccess() { return success; }
    public T getResult() { return result; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMsg() { return errorMsg; }
}
