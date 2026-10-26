package com.epcc.arkweb.web;
import com.epcc.arkweb.ShiroTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.service.ConversationService;
import org.junit.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
public class CommonControllerTest {
    @After public void clear() { ShiroTestSupport.clear(); }
    @Test public void forwardsAuthenticatedIdentityAndReturnsTheServiceResult() {
        ShiroTestSupport.bindLocalUser();
        ConversationService service=mock(ConversationService.class);
        String expected="{\"userId\":\"user-1\",\"orgCode\":\"104100000004\"}";
        Map<String,Object> result=Collections.singletonMap("success",true);
        when(service.userInfo(expected)).thenReturn(result);
        assertThat(new CommonController(service,new ObjectMapper()).getUserInfo()).isSameAs(result);
        verify(service).userInfo(expected);
    }
}
