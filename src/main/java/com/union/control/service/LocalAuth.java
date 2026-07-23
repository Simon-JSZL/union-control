package com.union.control.service;

/** Local identity mock. Production replaces this class with the real auth service. */
public final class LocalAuth {
    public static final String CAS_SESSION_ID = "session-1";
    public static final String USER_ID = "user-1";

    private LocalAuth() {}

    public static String cookieHeader() {
        return "CASSESSIONID=" + CAS_SESSION_ID + "; USERID=" + USER_ID;
    }
}
