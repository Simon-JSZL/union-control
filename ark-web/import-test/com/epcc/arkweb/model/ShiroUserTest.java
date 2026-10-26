package com.epcc.arkweb.model;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.assertj.core.api.Assertions.*;
public class ShiroUserTest {
    @Test public void identityPropertiesRoundTrip() throws Exception {
        ObjectMapper json=new ObjectMapper();
        ShiroUser user=json.readValue("{\"loginName\":\"u\",\"orgCode\":\"o\",\"roleId\":\"r\"}",ShiroUser.class);
        assertThat(user.getLoginName()).isEqualTo("u"); assertThat(user.getOrgCode()).isEqualTo("o"); assertThat(user.getRoleId()).isEqualTo("r");
        assertThat(json.readTree(json.writeValueAsString(user)).size()).isEqualTo(3);
    }
}
