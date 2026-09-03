package com.epcc.arkweb.web.llm;

import com.union.control.service.ScheduledTaskService;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

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

/** Agent-only guard. Scheduled requests never create a Shiro Subject. */
@Component
public final class AgentAuthorizationInterceptor extends HandlerInterceptorAdapter {
    public static final String TRUSTED_CONTEXT_HEADER = "X-Agent-Trusted-Context";
    private static final String AUTHORIZATION_PREFIX = "Scheduled ";
    public static final String CONTEXT_ATTRIBUTE =
            AgentAuthorizationInterceptor.class.getName() + ".scheduledContext";
    private static final Logger LOG = LoggerFactory.getLogger(AgentAuthorizationInterceptor.class);

    private final ScheduledTaskService scheduledTasks;
    private volatile Object arkAuthService;

    @Autowired
    public AgentAuthorizationInterceptor(ScheduledTaskService scheduledTasks) {
        this.scheduledTasks = scheduledTasks;
    }

    /** Production supplies its existing bean; absence deliberately fails closed. */
    @Autowired(required = false)
    public void setArkAuthService(@Qualifier("arkAuthService") Object arkAuthService) {
        this.arkAuthService = arkAuthService;
    }

    public Map<String, Object> authorize(String authorization, String permission) {
        String token = token(authorization);
        if (token == null) throw new IllegalArgumentException("scheduled token invalid");
        Map<String, Object> context = context(scheduledTasks.resolveScheduledToken(token));
        if (!hasPermission((String) context.get("roleId"),
                (String) context.get("userId"), permission))
            throw new SecurityException("scheduled permission denied");
        return context;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (!(handler instanceof HandlerMethod)) return true;
        AgentPermission permission = ((HandlerMethod) handler)
                .getMethodAnnotation(AgentPermission.class);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (permission == null) {
            if ("/agent/scheduledTaskAuthorize".equals(path))
                return validAuthorizationRequest(request, response);
            return deny(response, 403, "agent_permission_denied");
        }

        List<String> authorization = headers(request, HttpHeaders.AUTHORIZATION);
        List<String> trusted = headers(request, TRUSTED_CONTEXT_HEADER);
        boolean cookie = present(request.getHeader(HttpHeaders.COOKIE));
        if (authorization.size() > 1 || trusted.size() > 1
                || (!authorization.isEmpty() && !trusted.isEmpty())
                || (!trusted.isEmpty() && cookie))
            return deny(response, 400, "mixed_authentication");

        if (!trusted.isEmpty()) {
            String rawToken = token(trusted.get(0));
            if (rawToken == null) return deny(response, 401, "scheduled_auth_error");
            try {
                Map<String, Object> context = context(
                        scheduledTasks.resolveScheduledToken(rawToken));
                if (!hasPermission((String) context.get("roleId"),
                        (String) context.get("userId"), permission.value()))
                    return deny(response, 403, "agent_permission_denied");
                request.setAttribute(CONTEXT_ATTRIBUTE, context);
                return true;
            } catch (RuntimeException error) {
                return deny(response, 401, "scheduled_auth_error");
            }
        }

        // Raw scheduled credentials are accepted only by the exchange endpoint.
        if (!authorization.isEmpty()) return deny(response, 401, "scheduled_auth_error");
        try {
            Subject subject = SecurityUtils.getSubject();
            if (!subject.isAuthenticated() || !subject.isPermitted(permission.value()))
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

    private static boolean validAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        List<String> authorization = headers(request, HttpHeaders.AUTHORIZATION);
        if (authorization.size() != 1
                || token(authorization.get(0)) == null
                || present(request.getHeader(TRUSTED_CONTEXT_HEADER))
                || present(request.getHeader(HttpHeaders.COOKIE)))
            return deny(response, 401, "scheduled_auth_error");
        return true;
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

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
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
