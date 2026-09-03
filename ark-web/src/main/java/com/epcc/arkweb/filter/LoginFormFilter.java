package com.epcc.arkweb.filter;

import org.apache.shiro.web.filter.authc.FormAuthenticationFilter;
import org.apache.shiro.web.util.WebUtils;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/** Production-named form filter; proprietary session telemetry is outside the local build. */
public class LoginFormFilter extends FormAuthenticationFilter {
    private final String propFlag;

    public LoginFormFilter(String propFlag) {
        this.propFlag = propFlag;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response)
            throws Exception {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        if (isLoginRequest(request, response)) {
            return !isLoginSubmission(request, response) || executeLogin(request, response);
        }
        httpResponse.setStatus(401);
        if (!isAjax((HttpServletRequest) request))
            WebUtils.issueRedirect(request, response,
                    wrapperUrl((HttpServletRequest) request), (Map) null, true, false);
        return false;
    }

    private boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }

    private String wrapperUrl(HttpServletRequest request) {
        if (propFlag.contains("DMZ")) return "/union-op/login.html";
        return request.getServletPath().contains("/union-op")
                ? "/union-op/login.html" : "/login.html";
    }
}
