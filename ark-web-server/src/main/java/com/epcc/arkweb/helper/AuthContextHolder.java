package com.epcc.arkweb.helper;

import com.epcc.arkweb.model.ShiroUser;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/** Uses the same Shiro Subject access pattern as production ark-web. */
public final class AuthContextHolder {
    private AuthContextHolder() {}

    public static ShiroUser getAuthUserDetails() {
        Subject subject = SecurityUtils.getSubject();
        Object principal = subject == null ? null : subject.getPrincipal();
        return principal instanceof ShiroUser ? (ShiroUser) principal : null;
    }
}
