package com.epcc.arkweb.utils;

public final class ResultMsg {
    private boolean success = true;
    private String msg = "操作成功";
    private Integer code = 0;
    private Object data;

    public ResultMsg() {}

    public ResultMsg(Object data) { this.data = data; }

    public ResultMsg(String msg, boolean status) {
        this.success = status;
        this.msg = msg;
        this.code = status ? 0 : 1;
    }

    public static ResultMsg ok(Object data) { return new ResultMsg(data); }
    public static ResultMsg fail(String msg) { return new ResultMsg(msg, false); }
    public boolean isSuccess() { return success; }
    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }
    public String getMsg() { return msg; }
    public Integer getCode() { return code; }
    public ResultMsg setCode(Integer code) {
        this.code = code;
        return this;
    }
}
