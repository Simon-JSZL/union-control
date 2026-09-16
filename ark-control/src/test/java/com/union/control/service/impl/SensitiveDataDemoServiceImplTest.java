package com.union.control.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.mapper.SensitiveAddressBookDemo;
import com.union.control.mapper.SensitiveDataDemoMapper;
import com.union.control.service.sensitive.SensitiveRevealService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

public class SensitiveDataDemoServiceImplTest {
    private static final String TOKEN = "rt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MASK = "[#138****8000#VIEW:" + TOKEN + "]";
    private final ObjectMapper json = new ObjectMapper();
    private final SensitiveDataDemoMapper mapper = mock(SensitiveDataDemoMapper.class);
    private final SensitiveRevealService reveal = mock(SensitiveRevealService.class);
    private final SensitiveDataDemoServiceImpl service = new SensitiveDataDemoServiceImpl(mapper, json, reveal);

    @Test
    public void saveResolvesUnopenedFragmentsPreservesNewlinesAndReturnsOnlyQueriedRow() throws Exception {
        Map<String, Object> input = row("phoneNumber", " 新备注\n" + MASK + " ");
        input.put("id", 7);
        Map<String, Object> saved = row("phoneNumber", " 新备注\n" + MASK + " ");
        saved.put("id", 7L);
        when(reveal.reveal(anyString())).thenReturn("13800138000");
        when(mapper.update(anyMap())).thenReturn(1);
        when(mapper.queryById(7L)).thenReturn(saved);

        assertSame(saved, service.save(json.writeValueAsString(input)));

        ArgumentCaptor<Map> persisted = ArgumentCaptor.forClass(Map.class);
        verify(mapper).update(persisted.capture());
        assertEquals(" 新备注\n13800138000 ", persisted.getValue().get("phoneNumber"));
        ArgumentCaptor<String> tokenInput = ArgumentCaptor.forClass(String.class);
        verify(reveal).reveal(tokenInput.capture());
        Map parsed = json.readValue(tokenInput.getValue(), Map.class);
        assertEquals("user-1", parsed.get("userId"));
        assertEquals(TOKEN, parsed.get("token"));
    }

    @Test
    public void insertReturnsTheGeneratedIdRatherThanTheLatestRow() throws Exception {
        doAnswer(call -> { ((Map) call.getArguments()[0]).put("id", 9L); return 1; })
                .when(mapper).insertSaved(anyMap());
        Map<String, Object> saved = row("phoneNumber", MASK);
        when(mapper.queryById(9L)).thenReturn(saved);
        Map<String, Object> input = row("phoneNumber", "13800138000");
        input.put("id", null);
        assertSame(saved, service.save(json.writeValueAsString(input)));
        verify(mapper, never()).query();
    }

    @Test
    public void addressBookEditResolvesOnlySensitiveValuesAndReturnsBypassResult() throws Exception {
        Map<String, Object> input = row("name", "新联系人");
        input.put("id", 3); input.put("role", "003"); input.put("email", "demo@example.com");
        input.put("telephone", "010-12345678"); input.put("mobileNumber", MASK);
        when(reveal.reveal(anyString())).thenReturn("13800138000");
        when(mapper.updateAddressBook(any(SensitiveAddressBookDemo.class))).thenReturn(1);
        SensitiveAddressBookDemo saved = new SensitiveAddressBookDemo();
        saved.setId(3L); saved.setRole("003"); saved.setMobileNumber(MASK);
        when(mapper.queryAddressBookById(3L)).thenReturn(saved);

        assertEquals(MASK, service.saveAddressBook(json.writeValueAsString(input)).get("mobileNumber"));
        ArgumentCaptor<SensitiveAddressBookDemo> persisted = ArgumentCaptor.forClass(SensitiveAddressBookDemo.class);
        verify(mapper).updateAddressBook(persisted.capture());
        assertEquals("13800138000", persisted.getValue().getMobileNumber());
        assertEquals("新联系人", persisted.getValue().getName());
    }

    @Test
    public void expiredOrDamagedFragmentsNeverPersist() throws Exception {
        when(reveal.reveal(anyString())).thenThrow(new SensitiveRevealService.ExpiredTokenException());
        try { service.save(json.writeValueAsString(row("phoneNumber", MASK))); fail(); }
        catch (SensitiveRevealService.ExpiredTokenException expected) { }
        try { service.save(json.writeValueAsString(row("phoneNumber", "[#138****8000]"))); fail(); }
        catch (IllegalArgumentException expected) { }
        verifyZeroInteractions(mapper);
    }

    @Test
    public void rejectsInvalidIdsAndOverlongResolvedContent() throws Exception {
        for (Object id : new Object[]{0, -1, 1.5, "1"}) {
            Map<String, Object> input = row("phoneNumber", "13800138000"); input.put("id", id);
            try { service.save(json.writeValueAsString(input)); fail("accepted invalid id"); }
            catch (IllegalArgumentException expected) { }
        }
        when(reveal.reveal(anyString())).thenReturn(new String(new char[65]).replace('\0', '1'));
        try { service.save(json.writeValueAsString(row("phoneNumber", MASK))); fail(); }
        catch (IllegalArgumentException expected) { }
        verifyZeroInteractions(mapper);
    }

    private Map<String, Object> row(String key, String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", "user-1"); result.put(key, value); return result;
    }
}
