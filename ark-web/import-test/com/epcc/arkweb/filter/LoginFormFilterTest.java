package com.epcc.arkweb.filter;
import org.junit.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
public class LoginFormFilterTest {
    @Test public void ajaxDenialAndBrowserRedirectKeepProductionPaths() throws Exception {
        for (String flag:new String[]{"DMZ","INTERNAL"}) {
            for (String path:new String[]{"/home","/union-op/home"}) {
                LoginFormFilter filter=new LoginFormFilter(flag);
                MockHttpServletRequest request=new MockHttpServletRequest(); request.setServletPath(path);
                MockHttpServletResponse response=new MockHttpServletResponse();
                assertThat(filter.onAccessDenied(request,response)).isFalse();
                assertThat(response.getRedirectedUrl()).endsWith(flag.equals("DMZ") || path.contains("union-op")
                        ? "/union-op/login.html" : "/login.html");
                request.addHeader("X-Requested-With","XMLHttpRequest"); response=new MockHttpServletResponse();
                assertThat(filter.onAccessDenied(request,response)).isFalse();
                assertThat(response.getStatus()).isEqualTo(401); assertThat(response.getRedirectedUrl()).isNull();
            }
        }
    }
    @Test public void loginPageAndSubmissionsUseTheNativeLoginLifecycle() throws Exception {
        TestFilter filter=spy(new TestFilter()); filter.setLoginUrl("/login");
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/login");
        request.setServletPath("/login");
        assertThat(filter.onAccessDenied(request,new MockHttpServletResponse())).isTrue();
        request.setMethod("POST");
        doReturn(true).when(filter).executeLogin(org.mockito.Matchers.any(),org.mockito.Matchers.any());
        assertThat(filter.onAccessDenied(request,new MockHttpServletResponse())).isTrue();
        doReturn(false).when(filter).executeLogin(org.mockito.Matchers.any(),org.mockito.Matchers.any());
        assertThat(filter.onAccessDenied(request,new MockHttpServletResponse())).isFalse();
    }
    private static class TestFilter extends LoginFormFilter {
        TestFilter() { super("INTERNAL"); }
        @Override public boolean executeLogin(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response) { return false; }
    }
}
