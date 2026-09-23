package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldLong;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldWithTag;
import com.nucc.channel.ark.common.exception.CheckException;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

public class SensitiveRevealProcessorTest {
    private SymmetricalSecurityUtils crypto;
    private RedisRevealTokenStore tokens;
    private RedisCacheService redis;
    private SensitiveRevealProcessor processor;

    @Before
    public void setUp() {
        crypto = mock(SymmetricalSecurityUtils.class);
        redis = mock(RedisCacheService.class);
        tokens = new RedisRevealTokenStore(redis, 1800);
        SecureRandom random = mock(SecureRandom.class);
        final int[] sequence = {0};
        doAnswer(call -> {
            Arrays.fill((byte[]) call.getArguments()[0], (byte) sequence[0]++);
            return null;
        }).when(random).nextBytes(any(byte[].class));
        processor = new SensitiveRevealProcessor(crypto, tokens, random);
    }

    @Test
    public void assertEncryptAndDecryptEveryAnnotationIncludingInheritedFields() throws Exception {
        Record record = new Record();
        record.plain = "plain";
        record.tagged = "tagged";
        record.longText = "long";
        when(crypto.encryptWithCheck("plain")).thenReturn("encrypted");
        when(crypto.encryptWithTag("tagged")).thenReturn("tag-encrypted");
        when(crypto.encryptLongString("long", 3)).thenReturn("long-encrypted");
        processor.encrypt(record);
        assertEquals("encrypted", record.plain);
        assertEquals("tag-encrypted", record.tagged);
        assertEquals("long-encrypted", record.longText);
        assertNull(record.empty);
        when(crypto.decryptWithCheckNoLog("encrypted")).thenReturn("plain");
        when(crypto.decryptWithTag("tag-encrypted")).thenReturn("tagged");
        when(crypto.decryptLongString("long-encrypted")).thenReturn("long");
        processor.decrypt(record);
        assertEquals("plain", record.plain);
        assertEquals("tagged", record.tagged);
        assertEquals("long", record.longText);
        assertEquals("untouched", record.ordinary);
    }

    @Test
    public void assertTransformsFlatMapRowsWithoutChangingOrdinaryAliases() throws Exception {
        Map<Object, Object> map = new LinkedHashMap<>();
        String shared = new String("phone");
        map.put("mobileNumber", shared);
        map.put("alias", shared);
        map.put("count", 7);
        map.put(42, "ordinary");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("telNumber", "telephone");
        List<Map<Object, Object>> rows = Arrays.asList(map, map);
        when(crypto.encryptWithCheck("phone")).thenReturn("encrypted");
        when(crypto.encryptWithCheck("telephone")).thenReturn("encrypted-tel");
        processor.encrypt(rows);
        assertEquals("encrypted", map.get("mobileNumber"));
        assertEquals("phone", map.get("alias"));
        processor.encrypt(new Object[]{nested, null, true, 'x', Thread.State.NEW, new Object()});
        assertEquals("encrypted-tel", nested.get("telNumber"));
        verify(crypto).encryptWithCheck("phone");
        when(crypto.decryptWithCheckNoLog("encrypted")).thenReturn("phone");
        when(crypto.decryptWithCheckNoLog("encrypted-tel")).thenReturn("telephone");
        processor.decrypt(rows);
        assertEquals("phone", map.get("mobileNumber"));
        assertEquals(7, map.get("count"));
        assertEquals("ordinary", map.get(42));
    }

    @Test
    public void assertEncryptsNestedMapValues() throws Exception {
        Map<String, Object> nested = new HashMap<>();
        nested.put("email", "cipher");
        Map<String, Object> row = new HashMap<>();
        row.put("details", nested);
        when(crypto.encryptWithCheck("cipher")).thenReturn("encrypted");
        processor.encrypt(Collections.singletonList(row));
        assertEquals("encrypted", nested.get("email"));
        verifyZeroInteractions(redis);
    }

    @Test
    public void assertAddressBookListEncryptDecryptAndReveal() throws Exception {
        com.union.control.mapper.SensitiveAddressBookDemo row =
                new com.union.control.mapper.SensitiveAddressBookDemo();
        row.setEmail("alice@example.com");
        List<com.union.control.mapper.SensitiveAddressBookDemo> rows = Arrays.asList(row, row);
        when(crypto.encryptWithCheck("alice@example.com")).thenReturn("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("alice@example.com");
        processor.encrypt(rows);
        assertEquals("cipher", row.getEmail());
        verify(crypto).encryptWithCheck("alice@example.com");
        processor.decrypt(rows);
        assertEquals("alice@example.com", row.getEmail());
        row.setEmail("cipher");
        processor.process(rows);
        assertEquals("[#a***@example.com]", row.getEmail());
    }

    @Test
    public void assertPublicConstructorHandlesEmptyAndSimpleValues() throws Exception {
        SensitiveRevealProcessor actual = new SensitiveRevealProcessor(crypto, tokens);
        actual.encrypt(null);
        actual.decrypt("unchanged");
        actual.process(Collections.emptyList());
        assertNotNull(actual);
        verifyZeroInteractions(crypto, redis);
    }

    @Test
    public void assertRevealBatchesFragmentsAndPreservesSurroundingText() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mobileNumber", "ciphertext");
        when(crypto.decryptWithCheckNoLog("ciphertext"))
                .thenReturn("call 13812345678 or alice@example.com today");
        when(crypto.encryptWithCheck(anyString())).thenAnswer(call -> "enc:" + call.getArguments()[0]);
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        processor.process(map);
        String token = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        byte[] secondBytes = new byte[32];
        Arrays.fill(secondBytes, (byte) 1);
        String secondToken = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secondBytes);
        assertEquals("call [#138****5678#VIEW:" + token + "] or [#a***@example.com#VIEW:"
                + secondToken + "] today", map.get("mobileNumber"));
        ArgumentCaptor<Map> entries = ArgumentCaptor.forClass(Map.class);
        verify(redis).setexBatch(entries.capture(), eq(1800));
        assertEquals(2, entries.getValue().size());
        assertEquals("enc:13812345678", entries.getValue().get(RedisRevealTokenStore.key(token)));
        assertEquals("enc:alice@example.com", entries.getValue().get(RedisRevealTokenStore.key(secondToken)));
    }

    @Test
    public void assertRevealMasksWhenStoreRejectsOrThrows() throws Exception {
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("13812345678");
        when(crypto.encryptWithCheck("13812345678")).thenReturn("encrypted");
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.FAIL_RESULT)
                .thenThrow(new IllegalStateException("offline"));
        for (int i = 0; i < 2; i++) {
            Map<String, Object> map = new HashMap<>();
            map.put("phoneNumber", "cipher");
            processor.process(map);
            assertEquals("[#138****5678]", map.get("phoneNumber"));
        }
    }

    @Test
    public void assertRevealMasksFragmentWhenEncryptionFails() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("email", "cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("contact alice@example.com");
        when(crypto.encryptWithCheck(anyString())).thenThrow(mock(CheckException.class));
        processor.process(map);
        assertEquals("contact [#a***@example.com]", map.get("email"));
        verifyZeroInteractions(redis);
    }

    @Test
    public void assertRevealDecryptionFailuresAreMaskedForMapAndPojo() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("email", "bad");
        when(crypto.decryptWithCheckNoLog("bad")).thenThrow(mock(CheckException.class));
        Plain record = new Plain();
        record.plain = "bad";
        processor.process(Arrays.asList(map, record));
        assertEquals("[#****]", map.get("email"));
        assertEquals("[#****]", record.plain);
        when(crypto.decryptWithCheckNoLog("runtime")).thenThrow(new IllegalArgumentException("bad"));
        record.plain = "runtime";
        processor.process(record);
        assertEquals("[#****]", record.plain);
    }

    @Test
    public void assertRevealPreservesNullEmptyAndNonSensitivePlaintext() throws Exception {
        Record record = new Record();
        record.plain = "cipher";
        record.tagged = "tag";
        record.longText = "long";
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("");
        when(crypto.decryptWithTag("tag")).thenReturn("ordinary text");
        when(crypto.decryptLongString("long")).thenReturn(null);
        processor.process(record);
        assertEquals("", record.plain);
        assertEquals("ordinary text", record.tagged);
        assertNull(record.longText);
        assertNull(record.empty);
        verifyZeroInteractions(redis);
    }

    @Test
    public void assertInvalidAnnotatedFieldsAreRejected() throws Exception {
        for (Object invalid : Arrays.asList(new StaticField(), new FinalField(), new WrongType())) {
            try {
                processor.encrypt(invalid);
                fail("Invalid annotated fields must fail");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("writable String"));
            }
        }
        try {
            processor.process(new WrongType());
            fail("Reveal must reject invalid field declarations");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("writable String"));
        }
    }

    @Test
    public void assertCheckedFailuresPropagateOutsideReveal() throws Exception {
        Plain record = new Plain();
        record.plain = "bad";
        CheckException failure = mock(CheckException.class);
        when(crypto.encryptWithCheck("bad")).thenThrow(failure);
        try {
            processor.encrypt(record);
            fail("Encryption failure must propagate");
        } catch (CheckException expected) {
            assertSame(failure, expected);
        }
    }

    @Test
    public void assertMaskBoundaryCases() throws Exception {
        Method mask = SensitiveRevealProcessor.class.getDeclaredMethod("mask", String.class);
        mask.setAccessible(true);
        assertEquals("a***@example.com", mask.invoke(null, "alice@example.com"));
        assertEquals("138****5678", mask.invoke(null, "13812345678"));
        assertEquals("021****5678", mask.invoke(null, "021-12345678"));
        assertEquals("123****5678", mask.invoke(null, "12345678"));
        assertEquals("****", mask.invoke(null, "1234567"));
        assertEquals("****", mask.invoke(null, "@"));
        assertEquals("021****5678", mask.invoke(null, "02112345678"));
    }

    @Test
    public void assertPrimitiveTypeAndPrivateFieldAccess() throws Exception {
        Method simple = SensitiveRevealProcessor.class.getDeclaredMethod("isSimple", Class.class);
        simple.setAccessible(true);
        assertEquals(true, simple.invoke(null, int.class));
        Method read = SensitiveRevealProcessor.class.getDeclaredMethod("readField", Object.class, Field.class);
        Method write = SensitiveRevealProcessor.class.getDeclaredMethod("writeField", Object.class, Field.class, String.class);
        read.setAccessible(true);
        write.setAccessible(true);
        Plain owner = new Plain();
        Field field = Plain.class.getDeclaredField("plain");
        write.invoke(null, owner, field, "value");
        assertEquals("value", read.invoke(null, owner, field));
        field.setAccessible(false);
        assertEquals("value", read.invoke(null, owner, field));
        write.invoke(null, owner, field, "updated");
        assertEquals("updated", read.invoke(null, owner, field));
    }

    @Test
    public void assertReadEditSubmitRoundTripPreservesMultipleFragments() throws Exception {
        Map<String, String> stored = new HashMap<>();
        when(redis.setexBatch(anyMap(), eq(1800))).thenAnswer(call -> {
            stored.putAll((Map<String, String>) call.getArguments()[0]);
            return ResultUtil.SUCCESS_RESULT;
        });
        when(redis.get(anyString())).thenAnswer(call -> stored.get(call.getArguments()[0]));
        when(crypto.encryptWithCheck(anyString())).thenAnswer(call -> "enc:" + call.getArguments()[0]);
        when(crypto.decryptWithCheckNoLog(anyString())).thenAnswer(call ->
                ((String) call.getArguments()[0]).substring(4));
        String plaintext = "备注 [#普通文本] #VIEW:说明 **** call 13812345678 or alice@example.com today";
        Map<String, Object> row = new HashMap<>();
        row.put("phoneNumber", plaintext);
        processor.encrypt(row);
        processor.process(row);
        assertTrue(((String) row.get("phoneNumber")).contains("#VIEW:"));
        processor.encrypt(row);
        assertEquals("enc:" + plaintext, row.get("phoneNumber"));
    }

    @Test
    public void assertRestoresMarkersBeforeEveryAnnotationEncryption() throws Exception {
        String token = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String marker = "[#138****5678#VIEW:" + token + "]";
        when(redis.get(RedisRevealTokenStore.key(token))).thenReturn("fragment");
        when(crypto.decryptWithCheckNoLog("fragment")).thenReturn("13812345678");
        when(crypto.encryptWithCheck("13812345678")).thenReturn("ordinary-cipher");
        when(crypto.encryptWithTag("call 13812345678 / 13812345678")).thenReturn("tag-cipher");
        when(crypto.encryptLongString("13812345678", 3)).thenReturn("long-cipher");
        Record record = new Record();
        record.plain = marker;
        record.tagged = "call " + marker + " / " + marker;
        record.longText = marker;
        processor.encrypt(record);
        assertEquals("ordinary-cipher", record.plain);
        assertEquals("tag-cipher", record.tagged);
        assertEquals("long-cipher", record.longText);
    }

    @Test
    public void assertMyBatisContainersEncryptOnceWithoutChangingOrdinaryMapKeys() throws Exception {
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        Plain row = new Plain();
        row.plain = "plain";
        List<Plain> rows = Arrays.asList(row, row);
        org.apache.ibatis.binding.MapperMethod.ParamMap<Object> parameters =
                new org.apache.ibatis.binding.MapperMethod.ParamMap<>();
        parameters.put("list", rows);
        parameters.put("collection", rows);
        parameters.put("param1", rows);
        processor.encrypt(parameters);
        assertEquals("cipher", row.plain);
        verify(crypto, times(1)).encryptWithCheck("plain");

        Map<String, Object> nested = new LinkedHashMap<>();
        String shared = new String("plain");
        nested.put("email", shared);
        nested.put("name", shared);
        parameters.clear();
        parameters.put("paramMap", nested);
        parameters.put("param1", nested);
        processor.encrypt(parameters);
        assertEquals("cipher", nested.get("email"));
        assertEquals("plain", nested.get("name"));
    }

    @Test
    public void assertEncryptionFailureLeavesEntireInputUnchanged() throws Exception {
        Plain first = new Plain();
        first.plain = "plain";
        Plain second = new Plain();
        second.plain = "[#138****5678]";
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        try {
            processor.encrypt(Arrays.asList(first, second));
            fail("Unresolved marker must reject whole write");
        } catch (IllegalArgumentException expected) { }
        assertEquals("plain", first.plain);
        assertEquals("[#138****5678]", second.plain);
    }

    @Test
    public void assertNumericSensitiveMapValuesAreRejected() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("phoneNumber", 13812345678L);
        try { processor.encrypt(row); fail("Numeric sensitive values cannot bypass encryption"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(13812345678L, row.get("phoneNumber"));
    }

    @Test
    public void assertFailedApplyRollsBackPreviouslyChangedRows() throws Exception {
        Plain first = new Plain();
        first.plain = "plain";
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        Map<String, String> immutable = Collections.singletonMap("email", "plain");
        try {
            processor.encrypt(Arrays.asList(first, immutable));
            fail("Immutable parameter must fail before SQL");
        } catch (UnsupportedOperationException expected) { }
        assertEquals("plain", first.plain);
    }

    @Test
    public void assertMyBatisScalarAliasesAndTokenMaskMismatch() throws Exception {
        org.apache.ibatis.binding.MapperMethod.ParamMap<Object> row =
                new org.apache.ibatis.binding.MapperMethod.ParamMap<>();
        String email = new String("plain");
        row.put("email", email);
        row.put("param1", email);
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        Runnable restore = processor.encryptForExecution(row);
        assertEquals("cipher", row.get("email"));
        assertEquals("cipher", row.get("param1"));
        restore.run();
        assertEquals("plain", row.get("param1"));

        row.put("email", "[#138****5678#VIEW:rt_" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]) + "]");
        when(redis.get(anyString())).thenReturn("fragment");
        when(crypto.decryptWithCheckNoLog("fragment")).thenReturn("13912345678");
        try { processor.encrypt(row); fail("Mismatched token must not replace another displayed value"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void readDedupIsQueryLocalCodecSpecificAndTokensContainOnlyOneFragment() throws Exception {
        when(crypto.decryptWithCheckNoLog("same-cipher")).thenReturn("alice@example.com");
        when(crypto.decryptWithTag("same-cipher")).thenReturn("call 13812345678 / 13812345678");
        when(crypto.decryptLongString("same-cipher")).thenReturn("call 13812345678");
        when(crypto.encryptWithCheck("13812345678")).thenReturn("phone-fragment-cipher");
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        String previousMarker = null;
        for (int query = 0; query < 2; query++) {
            Record first = new Record();
            Record second = new Record();
            first.plain = first.tagged = first.longText = "same-cipher";
            second.plain = second.tagged = second.longText = "same-cipher";
            List<Record> rows = Arrays.asList(first, second, first);
            processor.process(rows);
            assertEquals(3, rows.size());
            assertSame(first, rows.get(2));
            assertEquals(first.plain, second.plain);
            assertEquals(first.tagged, second.tagged);
            String phoneMarker = first.longText.substring("call ".length());
            assertEquals("call " + phoneMarker + " / " + phoneMarker, first.tagged);
            assertTrue(first.plain.contains("#VIEW:"));
            if (previousMarker != null) assertNotEquals(previousMarker, first.plain);
            previousMarker = first.plain;
        }
        verify(crypto, times(2)).decryptWithCheckNoLog("same-cipher");
        verify(crypto, times(2)).decryptWithTag("same-cipher");
        verify(crypto, times(2)).decryptLongString("same-cipher");
        verify(crypto, times(2)).encryptWithCheck("13812345678");
        verify(crypto, never()).encryptWithCheck("alice@example.com");
        ArgumentCaptor<Map> batches = ArgumentCaptor.forClass(Map.class);
        verify(redis, times(2)).setexBatch(batches.capture(), eq(1800));
        for (Map batch : batches.getAllValues()) {
            assertEquals(2, batch.size());
            assertTrue(batch.containsValue("same-cipher"));
            assertTrue(batch.containsValue("phone-fragment-cipher"));
        }
    }

    @Test public void repeatedDecryptionFailureIsAttemptedOnceAndRetriedOnNextQuery() throws Exception {
        when(crypto.decryptWithCheckNoLog("bad")).thenThrow(mock(CheckException.class));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", i);
            row.put("email", "bad");
            rows.add(row);
        }
        processor.process(rows);
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, rows.get(i).get("id"));
            assertEquals("[#****]", rows.get(i).get("email"));
        }
        verify(crypto).decryptWithCheckNoLog("bad");
        verifyZeroInteractions(redis);
        doReturn("alice@example.com").when(crypto).decryptWithCheckNoLog("bad");
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        rows.get(0).put("email", "bad");
        processor.process(rows.get(0));
        assertTrue(((String) rows.get(0).get("email")).contains("#VIEW:"));
        verify(crypto, times(2)).decryptWithCheckNoLog("bad");
    }

    @Test public void distinctAddressBookValuesNeedNoReadSideReEncryption() throws Exception {
        processor = new SensitiveRevealProcessor(crypto, tokens);
        when(crypto.decryptWithCheckNoLog(anyString())).thenAnswer(call ->
                ((String) call.getArguments()[0]).substring("cipher:".length()));
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", i);
            row.put("email", "cipher:user" + i + "@example.com");
            row.put("mobileNumber", "cipher:" + (13812345000L + i));
            row.put("telNumber", "cipher:021-" + (12345000L + i));
            rows.add(row);
        }
        processor.process(rows);
        assertEquals(100, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, rows.get(i).get("id"));
            for (String key : Arrays.asList("email", "mobileNumber", "telNumber")) {
                assertTrue(((String) rows.get(i).get(key)).contains("#VIEW:"));
            }
        }
        verify(crypto, times(300)).decryptWithCheckNoLog(anyString());
        verify(crypto, never()).encryptWithCheck(anyString());
        ArgumentCaptor<Map> batches = ArgumentCaptor.forClass(Map.class);
        verify(redis, times(15)).setexBatch(batches.capture(), eq(1800));
        Set<Object> keys = new HashSet<>();
        for (Map batch : batches.getAllValues()) {
            assertEquals(20, batch.size());
            keys.addAll(batch.keySet());
            for (Object cipher : batch.values()) assertTrue(((String) cipher).startsWith("cipher:"));
        }
        assertEquals(300, keys.size());
    }

    @Test public void phoneShapedEmailLocalPartRemainsOneEmailFragment() throws Exception {
        when(crypto.decryptWithCheckNoLog("email-cipher")).thenReturn("13812345678@example.com");
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        Map<String, Object> row = new HashMap<>();
        row.put("email", "email-cipher");
        processor.process(row);
        assertTrue(((String) row.get("email")).startsWith("[#1***@example.com#VIEW:"));
        verify(crypto, never()).encryptWithCheck(anyString());
        ArgumentCaptor<Map> batch = ArgumentCaptor.forClass(Map.class);
        verify(redis).setexBatch(batch.capture(), eq(1800));
        assertEquals(Collections.singletonList("email-cipher"), new ArrayList<>(batch.getValue().values()));
    }

    private static class Plain {
        @EnDecryptField String plain;
    }
    private static class Record extends Plain {
        @EnDecryptFieldWithTag String tagged;
        @EnDecryptFieldLong(chunkSize = 3) String longText;
        @EnDecryptField String empty;
        String ordinary = "untouched";
    }
    private static class StaticField {
        @EnDecryptField static String value;
    }
    private static class FinalField {
        @EnDecryptField final String value = "final";
    }
    private static class WrongType {
        @EnDecryptField Integer value = 1;
    }
}
