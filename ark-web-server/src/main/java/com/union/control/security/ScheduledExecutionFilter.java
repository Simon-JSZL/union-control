package com.union.control.security;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.servlet.AdviceFilter;
import org.springframework.http.HttpHeaders;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Adds Scheduled credentials to the existing Shiro chain without replacing CAS. */
public final class ScheduledExecutionFilter extends AdviceFilter {
    @Override
    public boolean preHandle(ServletRequest rawRequest, ServletResponse rawResponse) {
        HttpServletRequest request = (HttpServletRequest) rawRequest;
        HttpServletResponse response = (HttpServletResponse) rawResponse;
        List<String> headers = Collections.list(safe(request.getHeaders(HttpHeaders.AUTHORIZATION)));
        if (headers.isEmpty()) return true;
        if (headers.size() != 1 || request.getHeader(HttpHeaders.COOKIE) != null)
            return error(response, 400, "mixed_authentication");
        String header = headers.get(0);
        try {
            if (header == null || !header.startsWith("Scheduled ")
                    || header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0)
                throw new IllegalArgumentException();
            String token = header.substring("Scheduled ".length());
            if (token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0)
                throw new IllegalArgumentException();
            Subject subject = SecurityUtils.getSubject();
            subject.login(new ScheduledExecutionRealm.Token(token));
            return true;
        } catch (RuntimeException error) {
            SecurityUtils.getSubject().logout();
            return error(response, 401, "scheduled_auth_error");
        }
    }

    private static Enumeration<String> safe(Enumeration<String> values) {
        return values == null ? Collections.enumeration(Collections.<String>emptyList()) : values;
    }

    private static boolean error(HttpServletResponse response, int status, String code) {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            response.getWriter().write("{\"error\":\"" + code + "\"}");
        } catch (IOException ignored) {}
        return false;
    }
}
