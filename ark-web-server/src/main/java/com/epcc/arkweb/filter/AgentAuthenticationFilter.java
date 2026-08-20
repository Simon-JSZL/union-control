package com.epcc.arkweb.filter;

import com.epcc.arkweb.security.CasSessionToken;

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

public final class AgentAuthenticationFilter extends AdviceFilter {
    private final ScheduledExecutionFilter scheduledFilter = new ScheduledExecutionFilter();

    protected boolean preHandle(ServletRequest rawRequest, ServletResponse rawResponse) {
        HttpServletRequest request = (HttpServletRequest) rawRequest;
        HttpServletResponse response = (HttpServletResponse) rawResponse;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        List<String> authorizations = Collections.list(
                safeHeaders(request.getHeaders(HttpHeaders.AUTHORIZATION)));
        String cookie = request.getHeader(HttpHeaders.COOKIE);
        boolean scheduled = !authorizations.isEmpty();
        boolean cas = cookie != null && !cookie.trim().isEmpty();
        if (authorizations.size() > 1 || (scheduled && cas))
            return error(response, 400, "mixed_authentication");
        if (path.startsWith("/llm/") || path.startsWith("/union-op/llm/") || path.startsWith("/common/")
                || path.startsWith("/api/sensitive/")) {
            if (scheduled) return error(response, 401, "cas_auth_error");
            return loginCas(cookie, response);
        }
        if (path.equals("/agent/scheduledExecutionIdentity")) {
            if (!scheduled) return error(response, 401, "scheduled_auth_error");
            return scheduledFilter.preHandle(rawRequest, rawResponse);
        }
        if (path.startsWith("/agent/")) {
            return scheduled ? scheduledFilter.preHandle(rawRequest, rawResponse)
                    : loginCas(cookie, response);
        }
        return true;
    }

    private boolean loginCas(String cookie, HttpServletResponse response) {
        if (cookie == null) return error(response, 401, "cas_auth_error");
        try {
            SecurityUtils.getSubject().login(new CasSessionToken(cookie));
            return true;
        } catch (RuntimeException error) {
            SecurityUtils.getSubject().logout();
            return error(response, 401, "cas_auth_error");
        }
    }

    private static Enumeration<String> safeHeaders(Enumeration<String> values) {
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
