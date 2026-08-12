package com.union.control.service;

/** Local authentication-service mock. Production replaces this with the real auth-service call. */
public final class LocalAuth {
    public static final String CAS_SESSION_ID = "session-1";
    public static final String USER_ID = "user-1";

    private LocalAuth() {}

    public static String cookieHeader() {
        return "CASSESSIONID=" + CAS_SESSION_ID;
    }

    public static String cookieHeaderForUser(String userId) {
        if (!USER_ID.equals(userId)) throw new ControlService.UnauthorizedException();
        return cookieHeader();
    }

    public static String authenticatedCookieHeader(String cookieHeader) {
        return cookieHeaderForUser(authenticate(cookieHeader));
    }

    public static String authenticate(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.length() > 8192 ||
                cookieHeader.indexOf('\r') >= 0 || cookieHeader.indexOf('\n') >= 0)
            throw new ControlService.UnauthorizedException();
        String cas = null;
        for (String raw : cookieHeader.split(";", -1)) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            int separator = part.indexOf('=');
            if (separator < 1) throw new ControlService.UnauthorizedException();
            if ("CASSESSIONID".equals(part.substring(0, separator).trim())) {
                if (cas != null) throw new ControlService.UnauthorizedException();
                cas = part.substring(separator + 1).trim();
            }
        }
        if (!CAS_SESSION_ID.equals(cas)) throw new ControlService.UnauthorizedException();
        return USER_ID;
    }
}
