package com.epcc.arkweb.filter;

import com.epcc.arkweb.security.CasSessionToken;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.servlet.AdviceFilter;
import org.springframework.http.HttpHeaders;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

final class CasSessionFilter extends AdviceFilter {
    protected boolean preHandle(ServletRequest request, ServletResponse response) {
        Subject subject = SecurityUtils.getSubject();
        String cookie = ((HttpServletRequest) request).getHeader(HttpHeaders.COOKIE);
        if (!subject.isAuthenticated() && cookie != null) {
            try {
                subject.login(new CasSessionToken(cookie));
            } catch (AuthenticationException ignored) {
                subject.logout();
            }
        }
        return true;
    }
}
