package com.epcc.arkweb.config;

import com.epcc.arkweb.model.ShiroUser;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

/** Local build of the production-named Realm; only its auth-service call is mocked. */
public class Realm extends AuthorizingRealm {
    private Object arkAuthService;

    @Autowired
    public void setArkAuthService(@Qualifier("arkAuthService") Object arkAuthService) {
        this.arkAuthService = arkAuthService;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        UsernamePasswordToken login = (UsernamePasswordToken) token;
        ShiroUser user = invoke("authenticate",
                new Class<?>[]{String.class, char[].class},
                new Object[]{login.getUsername(), login.getPassword()}, ShiroUser.class);
        if (user == null) throw new AuthenticationException("用户名或密码错误");
        SecurityUtils.getSubject().getSession().setAttribute("wl_session_user_key", user);
        return new SimpleAuthenticationInfo(user, login.getCredentials(), getName());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        ShiroUser user = principals.oneByType(ShiroUser.class);
        if (user == null) return null;
        Set<String> permissions = invoke("queryPermissions",
                new Class<?>[]{String.class, String.class},
                new Object[]{user.getRoleId(), user.getLoginName()}, Set.class);
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        info.setStringPermissions(permissions);
        return info;
    }

    private <T> T invoke(String name, Class<?>[] types, Object[] args, Class<T> resultType) {
        try {
            Method method = arkAuthService.getClass().getMethod(name, types);
            return resultType.cast(method.invoke(arkAuthService, args));
        } catch (InvocationTargetException error) {
            throw new AuthenticationException("鉴权服务调用失败", error.getCause());
        } catch (Exception error) {
            throw new AuthenticationException("鉴权服务调用失败", error);
        }
    }
}
