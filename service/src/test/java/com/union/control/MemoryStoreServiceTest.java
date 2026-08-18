package com.union.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.MemoryStoreMapper;
import com.union.control.service.MemoryStoreService;
import org.junit.Test;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MemoryStoreServiceTest {
    @Test
    public void parsesAuthenticatedUserFromJsonBeforeCallingMapper() {
        MemoryStoreMapper mapper = mock(MemoryStoreMapper.class);
        MemoryStoreService service = new MemoryStoreService(mapper, new ObjectMapper());
        String path = TestJson.USER_ID + "/personal/notes.md";
        when(mapper.readMemory(TestJson.USER_ID, path)).thenReturn(Collections.emptyList());

        service.memoryRead(TestJson.request("path", path, "maxChars", 100));

        verify(mapper).readMemory(TestJson.USER_ID, path);
    }
}
