package com.union.control;

import com.union.control.local.security.LocalCasRealm;
import com.union.control.mapper.MemoryStoreMapper;
import com.union.control.service.MemoryStoreService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MemoryStoreServiceTest {
    private MemoryStoreService service;
    private MemoryStoreMapper mapper;

    @Before
    public void setUp() {
        ShiroTestSupport.bindLocalUser();
        mapper = mock(MemoryStoreMapper.class);
        service = new MemoryStoreService(mapper);
    }

    @After
    public void tearDown() { ShiroTestSupport.clear(); }

    @Test
    public void memoryPathCannotEscapeAuthenticatedUserNamespace() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", "another-user/personal/profile.md");
        payload.put("maxChars", 100);

        assertThatThrownBy(() -> service.memoryRead(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memory path");
    }

    @Test
    public void operationIdCannotBeReplayedWithAnotherFingerprint() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("fingerprint", "first");
        when(mapper.findMemoryOperation(LocalCasRealm.USER_ID, "operation-1", false))
                .thenReturn(Collections.singletonList(stored));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", "operation-1");
        payload.put("fingerprint", "second");

        Map<String, Object> response = service.memoryOperation(payload);

        assertThat(response.get("success")).isEqualTo(false);
        assertThat(response.get("errorCode")).isEqualTo("operation_conflict");
    }
}
