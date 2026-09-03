package com.epcc.arkweb.helper;

import com.epcc.arkweb.model.ShiroUser;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.session.InvalidSessionException;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.BeanUtils;

/** Uses the same Shiro Subject access pattern as production ark-web. */
public final class AuthContextHolder {
    private static final String SESSION_USER = "wl_session_user_key";

    private AuthContextHolder() {}

    public static ShiroUser getAuthUserDetails() {
        Subject subject;
        try {
            subject = SecurityUtils.getSubject();
        } catch (RuntimeException error) {
            return null;
        }
        if (subject == null) return null;
        try {
            ShiroUser user = copy(subject.getSession(false) == null ? null
                    : subject.getSession(false).getAttribute(SESSION_USER));
            return user == null ? copy(subject.getPrincipal()) : user;
        } catch (InvalidSessionException error) {
            return null;
        }
    }

    private static ShiroUser copy(Object source) {
        if (source == null) return null;
        if (source instanceof ShiroUser) return (ShiroUser) source;
        ShiroUser target = new ShiroUser();
        BeanUtils.copyProperties(source, target);
        return target;
    }
}
