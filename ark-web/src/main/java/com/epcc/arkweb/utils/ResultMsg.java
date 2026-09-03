package com.epcc.arkweb.utils;

public final class ResultMsg {
    private final boolean success;
    private final Object data;
    private final String message;

    private ResultMsg(boolean success, Object data, String message) {
        this.success = success;
        this.data = data;
        this.message = message;
    }

    public static ResultMsg ok(Object data) { return new ResultMsg(true, data, null); }
    public static ResultMsg fail(String message) { return new ResultMsg(false, null, message); }
    public boolean isSuccess() { return success; }
    public Object getData() { return data; }
    public String getMessage() { return message; }
}
