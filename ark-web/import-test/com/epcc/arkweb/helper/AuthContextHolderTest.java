package com.epcc.arkweb.helper;
import com.epcc.arkweb.model.ShiroUser;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.InvalidSessionException;
import org.apache.shiro.util.ThreadContext;
import org.junit.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
public class AuthContextHolderTest {
    @After public void clear() { ThreadContext.remove(); }
    @Test public void resolvesSessionThenPrincipalAndCopiesProductionBean() {
        Subject subject=mock(Subject.class); Session session=mock(Session.class);
        ThreadContext.bind(subject);
        ShiroUser principal=new ShiroUser(); principal.setLoginName("principal");
        when(subject.getPrincipal()).thenReturn(principal);
        assertThat(AuthContextHolder.getAuthUserDetails()).isSameAs(principal);
        when(subject.getSession(false)).thenReturn(session);
        assertThat(AuthContextHolder.getAuthUserDetails()).isSameAs(principal);
        ShiroUser user=new ShiroUser(); user.setLoginName("session");
        when(session.getAttribute("wl_session_user_key")).thenReturn(user);
        assertThat(AuthContextHolder.getAuthUserDetails()).isSameAs(user);
        when(session.getAttribute("wl_session_user_key")).thenReturn(new ProductionUser());
        assertThat(AuthContextHolder.getAuthUserDetails().getLoginName()).isEqualTo("production");
    }
    @Test public void absentAndInvalidSessionsReturnNoIdentity() {
        ThreadContext.remove();
        assertThat(AuthContextHolder.getAuthUserDetails()).isNull();
        Subject subject=mock(Subject.class); ThreadContext.bind(subject);
        assertThat(AuthContextHolder.getAuthUserDetails()).isNull();
        when(subject.getSession(false)).thenThrow(new InvalidSessionException());
        assertThat(AuthContextHolder.getAuthUserDetails()).isNull();
    }
    public static class ProductionUser { public String getLoginName() { return "production"; } }
}
