package com.epcc.arkweb;

import com.epcc.arkweb.config.LocalCasRealm;
import com.epcc.arkweb.model.AuthenticatedUser;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

public final class ShiroTestSupport {
    private ShiroTestSupport() {}

    public static void bindLocalUser() {
        DefaultSecurityManager manager = new DefaultSecurityManager(new LocalCasRealm());
        Subject subject = new Subject.Builder(manager)
                .principals(new SimplePrincipalCollection(
                        new AuthenticatedUser(LocalCasRealm.USER_ID, LocalCasRealm.ORG_CODE,
                                LocalCasRealm.ROLE_ID, LocalCasRealm.cookieHeader()),
                        LocalCasRealm.class.getName()))
                .authenticated(true).buildSubject();
        ThreadContext.bind(manager);
        ThreadContext.bind(subject);
    }

    public static void clear() { ThreadContext.remove(); }
}
