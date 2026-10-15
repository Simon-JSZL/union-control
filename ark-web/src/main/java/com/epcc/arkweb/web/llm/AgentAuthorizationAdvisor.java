package com.epcc.arkweb.web.llm;

import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.authz.aop.AuthorizingAnnotationMethodInterceptor;
import org.apache.shiro.authz.aop.PermissionAnnotationHandler;
import org.apache.shiro.authz.aop.PermissionAnnotationMethodInterceptor;
import org.apache.shiro.spring.security.interceptor.AopAllianceAnnotationsAuthorizingMethodInterceptor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Set;

/** Extends the existing Shiro advisor only for Scheduled permission checks. */
public final class AgentAuthorizationAdvisor extends AuthorizationAttributeSourceAdvisor {
    public AgentAuthorizationAdvisor() {
        AopAllianceAnnotationsAuthorizingMethodInterceptor advice =
                (AopAllianceAnnotationsAuthorizingMethodInterceptor) getAdvice();
        for (AuthorizingAnnotationMethodInterceptor interceptor : advice.getMethodInterceptors()) {
            if (interceptor instanceof PermissionAnnotationMethodInterceptor) {
                interceptor.setHandler(new PermissionAnnotationHandler() {
                    @Override
                    public void assertAuthorized(Annotation annotation) {
                        if (AgentAuthorizationFilter.currentContext() == null) {
                            super.assertAuthorized(annotation);
                            return;
                        }
                        RequiresPermissions required = (RequiresPermissions) annotation;
                        Set<String> permissions = AgentAuthorizationFilter.currentPermissions();
                        boolean allowed = required.logical() == Logical.OR
                                ? Arrays.stream(required.value()).anyMatch(permissions::contains)
                                : Arrays.stream(required.value()).allMatch(permissions::contains);
                        if (!allowed) throw new UnauthorizedException("agent_permission_denied");
                    }
                });
            }
        }
    }
}
