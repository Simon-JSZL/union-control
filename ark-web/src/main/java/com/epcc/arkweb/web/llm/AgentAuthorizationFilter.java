package com.epcc.arkweb.web.llm;

import com.union.control.service.ScheduledTaskService;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.servlet.AdviceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shiro-chain Agent guard. Scheduled credentials never log in a Subject. */
public final class AgentAuthorizationFilter extends AdviceFilter {
    private static final String EXECUTE_PERMISSION = "/assistantManager/page";
    private static final String AUTHORIZATION_PREFIX = "Scheduled ";
    public static final String CONTEXT_ATTRIBUTE =
            AgentAuthorizationFilter.class.getName() + ".scheduledContext";
    private static final Logger LOG = LoggerFactory.getLogger(AgentAuthorizationFilter.class);

    private final ScheduledTaskService scheduledTasks;
    private final Object arkAuthService;

    public AgentAuthorizationFilter(ScheduledTaskService scheduledTasks, Object arkAuthService) {
        this.scheduledTasks = scheduledTasks;
        this.arkAuthService = arkAuthService;
    }

    @Override
    protected boolean preHandle(ServletRequest servletRequest, ServletResponse servletResponse) {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        List<String> authorization = headers(request, HttpHeaders.AUTHORIZATION);
        if (authorization.size() > 1
                || (!authorization.isEmpty() && request.getHeader(HttpHeaders.COOKIE) != null))
            return deny(response, 400, "mixed_authentication");

        if (!authorization.isEmpty()) {
            String rawToken = token(authorization.get(0));
            if (rawToken == null) return deny(response, 401, "scheduled_auth_error");
            try {
                Map<String, Object> context = context(scheduledTasks.resolveScheduledToken(rawToken));
                if (!hasPermission((String) context.get("roleId"),
                        (String) context.get("userId"), EXECUTE_PERMISSION))
                    return deny(response, 403, "agent_permission_denied");
                request.setAttribute(CONTEXT_ATTRIBUTE, context);
                return true;
            } catch (RuntimeException error) {
                return deny(response, 401, "scheduled_auth_error");
            }
        }

        try {
            Subject subject = SecurityUtils.getSubject();
            if (!subject.isAuthenticated() || !subject.isPermitted(EXECUTE_PERMISSION))
                return deny(response, 403, "agent_permission_denied");
            return true;
        } catch (RuntimeException error) {
            return deny(response, 401, "cas_auth_error");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> currentContext() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) return null;
        Object context = request.getAttribute(CONTEXT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return context instanceof Map ? (Map<String, Object>) context : null;
    }

    private boolean hasPermission(String roleId, String userName, String permission) {
        if (!identity(roleId) || !identity(userName) || !identity(permission)) return false;
        Object service = arkAuthService;
        if (service == null) return false;
        try {
            Object result = service.getClass()
                    .getMethod("queryResource", String.class, String.class, String.class)
                    .invoke(service, roleId, userName, UUID.randomUUID().toString());
            if (result == null || !Boolean.TRUE.equals(noArg(result, "isSuccess").invoke(result)))
                return false;
            Object values = noArg(result, "getResult").invoke(result);
            if (!(values instanceof Iterable)) return false;
            for (Object value : (Iterable<?>) values) {
                if (value == null) continue;
                Object url = noArg(value, "getResourceUrl").invoke(value);
                if (permission.equals(url)) return true;
            }
            return false;
        } catch (InvocationTargetException error) {
            LOG.warn("scheduled role resource lookup failed error_type={}",
                    error.getCause() == null ? error.getClass().getSimpleName()
                            : error.getCause().getClass().getSimpleName());
            return false;
        } catch (Exception error) {
            LOG.warn("scheduled role resource lookup failed error_type={}",
                    error.getClass().getSimpleName());
            return false;
        }
    }

    private static List<String> headers(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        return values == null ? Collections.<String>emptyList() : Collections.list(values);
    }

    private static String token(String value) {
        if (value == null || !value.startsWith(AUTHORIZATION_PREFIX)) return null;
        String token = value.substring(AUTHORIZATION_PREFIX.length());
        return token.length() == 43 && token.matches("[A-Za-z0-9_-]{43}") ? token : null;
    }

    private static Method noArg(Object target, String name) throws NoSuchMethodException {
        return target.getClass().getMethod(name);
    }

    private static boolean identity(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 64;
    }

    private static Map<String, Object> context(Map<String, Object> row) {
        if (row == null) throw new IllegalArgumentException("scheduled context missing");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("authenticationType", "SCHEDULED");
        context.put("runId", positiveNumber(row, "runId"));
        context.put("taskId", positiveNumber(row, "taskId"));
        context.put("userId", text(row, "userId", 64));
        context.put("orgCode", text(row, "orgCode", 64));
        context.put("roleId", text(row, "roleId", 64));
        context.put("prompt", text(row, "prompt", 4096));
        context.put("scheduledAt", text(row, "scheduledAt", 64));
        context.put("timezone", text(row, "timezone", 64));
        String expiresAt = text(row, "expiresAt", 64);
        if (!Instant.parse(expiresAt).isAfter(Instant.now()))
            throw new IllegalArgumentException("scheduled context expired");
        context.put("expiresAt", expiresAt);
        return Collections.unmodifiableMap(context);
    }

    private static long positiveNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0)
            throw new IllegalArgumentException(key + " invalid");
        return ((Number) value).longValue();
    }

    private static String text(Map<String, Object> row, String key, int max) {
        Object raw = row.get(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException(key + " invalid");
        String value = (String) raw;
        if (value.trim().isEmpty() || value.length() > max)
            throw new IllegalArgumentException(key + " invalid");
        return value;
    }

    private static boolean deny(HttpServletResponse response, int status, String code) {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            response.getWriter().write("{\"error\":\"" + code + "\"}");
        } catch (IOException ignored) {}
        return false;
    }

}
