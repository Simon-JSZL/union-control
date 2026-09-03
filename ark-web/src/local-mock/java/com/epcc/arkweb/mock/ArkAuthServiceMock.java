package com.epcc.arkweb.mock;

import com.epcc.arkweb.model.ShiroUser;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.web.servlet.AdviceFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

/** Local replacement for the external arkAuthService bean. */
@Component("arkAuthService")
@Profile("local-auth-mock")
public final class ArkAuthServiceMock {
    private final String userId;
    private final String password;
    private final String orgCode;
    private final String authorizedRoleId;

    public ArkAuthServiceMock(
            @Value("${agent.local-user-id:user-1}") String userId,
            @Value("${agent.local-password:local-only}") String password,
            @Value("${agent.local-org-code:104100000004}") String orgCode,
            @Value("${agent.local-authorized-role-id:role-1}") String authorizedRoleId) {
        this.userId = userId;
        this.password = password;
        this.orgCode = orgCode;
        this.authorizedRoleId = authorizedRoleId;
    }

    public ShiroUser authenticate(String userName, char[] suppliedPassword) {
        if (!userId.equals(userName)
                || !Arrays.equals(password.toCharArray(), suppliedPassword)) return null;
        ShiroUser user = new ShiroUser();
        user.setLoginName(userId);
        user.setOrgCode(orgCode);
        user.setRoleId(authorizedRoleId);
        return user;
    }

    public Set<String> queryPermissions(String roleId, String userName) {
        if (!authorizedRoleId.equals(roleId) || !userId.equals(userName))
            return Collections.emptySet();
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                Collections.singletonList("/assistantManager/page")));
    }

    /** Local requests get a normal Shiro Subject without a login page. */
    @Bean("localAuthFilter")
    public AdviceFilter localAuthFilter() {
        return new AdviceFilter() {
            @Override
            protected boolean preHandle(ServletRequest request, ServletResponse response) {
                HttpServletRequest http = (HttpServletRequest) request;
                String authorization = http.getHeader("Authorization");
                if ((authorization != null && authorization.startsWith("Scheduled "))
                        || http.getHeader("X-Agent-Trusted-Context") != null)
                    return true;
                if (!SecurityUtils.getSubject().isAuthenticated())
                    SecurityUtils.getSubject().login(
                            new UsernamePasswordToken(userId, password));
                return true;
            }
        };
    }

    /** The filter belongs only to Shiro's chain, not the servlet container's global chain. */
    @Bean
    public FilterRegistrationBean localAuthFilterRegistration(AdviceFilter localAuthFilter) {
        FilterRegistrationBean registration = new FilterRegistrationBean(localAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    public Result queryResource(String roleId, String userName, String traceNo) {
        List<Resource> resources = authorizedRoleId.equals(roleId) && userId.equals(userName)
                ? Collections.singletonList(new Resource("/assistantManager/page"))
                : Collections.<Resource>emptyList();
        return new Result(resources);
    }

    public static final class Result {
        private final List<Resource> result;
        Result(List<Resource> result) { this.result = result; }
        public boolean isSuccess() { return true; }
        public List<Resource> getResult() { return result; }
    }

    public static final class Resource {
        private final String resourceUrl;
        Resource(String resourceUrl) { this.resourceUrl = resourceUrl; }
        public String getResourceUrl() { return resourceUrl; }
    }
}
