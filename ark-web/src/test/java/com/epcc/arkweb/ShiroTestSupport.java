package com.epcc.arkweb;

import com.epcc.arkweb.model.ShiroUser;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

public final class ShiroTestSupport {
    public static final String USER_ID = "user-1";
    public static final String ORG_CODE = "104100000004";
    public static final String ROLE_ID = "ark-role-1";

    private ShiroTestSupport() {}

    public static void bindLocalUser() {
        DefaultSecurityManager manager = new DefaultSecurityManager();
        ShiroUser user = new ShiroUser();
        user.setLoginName(USER_ID);
        user.setOrgCode(ORG_CODE);
        user.setRoleId(ROLE_ID);
        Subject subject = new Subject.Builder(manager)
                .principals(new SimplePrincipalCollection(
                        user, ShiroTestSupport.class.getName()))
                .authenticated(true).buildSubject();
        subject.getSession().setAttribute("wl_session_user_key", user);
        ThreadContext.bind(manager);
        ThreadContext.bind(subject);
    }

    public static void clear() { ThreadContext.remove(); }
}
