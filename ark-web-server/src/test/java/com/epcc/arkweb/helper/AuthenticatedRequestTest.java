package com.epcc.arkweb.helper;

import com.epcc.arkweb.ShiroTestSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.epcc.arkweb.config.LocalCasRealm;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthenticatedRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Before
    public void setUp() { ShiroTestSupport.bindLocalUser(); }

    @After
    public void tearDown() { ShiroTestSupport.clear(); }

    @Test
    public void authenticatedIdentityOverridesClientSuppliedIdentity() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", "attacker");
        payload.put("orgCode", "attacker-org");
        payload.put("roleId", "attacker-role");
        payload.put("conversationId", "thread-1");

        String input = new AuthenticatedRequest(json).json(payload);
        Map<String, Object> parsed = json.readValue(
                input, new TypeReference<Map<String, Object>>() {});

        assertThat(parsed)
                .containsEntry("userId", LocalCasRealm.USER_ID)
                .containsEntry("orgCode", LocalCasRealm.ORG_CODE)
                .containsEntry("roleId", LocalCasRealm.ROLE_ID)
                .containsEntry("conversationId", "thread-1");
    }

    @Test
    public void removesClientIdentityBeforeForwardingPayload() throws Exception {
        byte[] input = "{\"userId\":\"attacker\",\"orgCode\":\"x\",\"prompt\":\"hello\"}"
                .getBytes("UTF-8");

        Map<String, Object> parsed = json.readValue(
                new AuthenticatedRequest(json).clientPayload(input),
                new TypeReference<Map<String, Object>>() {});

        assertThat(parsed).containsEntry("prompt", "hello")
                .doesNotContainKeys("userId", "orgCode", "roleId");
    }
}
