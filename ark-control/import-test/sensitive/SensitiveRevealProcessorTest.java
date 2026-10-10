package com.union.control.service.sensitive;

import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldLong;
import com.nucc.channel.ark.common.annotation.EnDecryptFieldWithTag;
import com.nucc.channel.ark.common.exception.CheckException;
import com.nucc.channel.ark.common.redis.RedisCacheService;
import com.nucc.channel.ark.common.util.ResultUtil;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SensitiveRevealProcessorTest {
    private static final String PHONE = "13812345678";
    private static final String EMAIL = "alice@example.com";
    private static final String TOKEN = "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String MARKER = "[#138****5678#VIEW:" + TOKEN + "]";
    private final SymmetricalSecurityUtils crypto = mock(SymmetricalSecurityUtils.class);
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final RedisRevealTokenStore store = new RedisRevealTokenStore(redis, 1800);
    private final SecureRandom random = mock(SecureRandom.class);
    private SensitiveRevealProcessor processor;

    @Before
    public void setUp() {
        final int[] sequence = {0};
        doAnswer(call -> {
            Arrays.fill((byte[]) call.getArguments()[0], (byte) sequence[0]++);
            return null;
        }).when(random).nextBytes(any(byte[].class));
        processor = new SensitiveRevealProcessor(crypto, store, random);
    }

    @Test
    public void annotationCodecsIncludeInheritedNullAndOrdinaryFields() throws Exception {
        AnnotatedRow row = new AnnotatedRow();
        row.plain = "normal";
        row.tagged = "tagged";
        row.longText = "long";
        when(crypto.encryptWithCheck("normal")).thenReturn("normal-cipher");
        when(crypto.encryptWithTag("tagged")).thenReturn("tag-cipher");
        when(crypto.encryptLongString("long", 3)).thenReturn("long-cipher");
        when(crypto.decryptWithCheckNoLog("normal-cipher")).thenReturn("normal");
        when(crypto.decryptWithTag("tag-cipher")).thenReturn("tagged");
        when(crypto.decryptLongString("long-cipher")).thenReturn("long");
        Runnable restore = processor.encryptForExecution(row);
        assertEquals("normal-cipher", row.plain);
        assertEquals("tag-cipher", row.tagged);
        assertEquals("long-cipher", row.longText);
        assertNull(row.empty);
        restore.run();
        assertEquals("normal", row.plain);
        assertEquals("tagged", row.tagged);
        assertEquals("long", row.longText);
        processor.encrypt(row);
        processor.decrypt(row);
        assertEquals("normal", row.plain);
        assertEquals("tagged", row.tagged);
        assertEquals("long", row.longText);
        assertEquals("untouched", row.ordinary);
        verify(crypto, times(2)).encryptLongString("long", 3);
    }

    @Test
    public void rowCollectionsArraysNullsAndRepeatedReferencesAreHandledOnce() throws Exception {
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("plain");
        for (boolean array : new boolean[]{false, true}) {
            Map<String, Object> row = row("plain");
            Object[] values = {row, row, null, true, 'a', Thread.State.NEW, 4, "text", new Object()};
            Object input = array ? values : Arrays.asList(values);
            Runnable restore = processor.encryptForExecution(input);
            assertEquals("cipher", row.get("email"));
            restore.run();
            assertEquals("plain", row.get("email"));
            processor.encrypt(input);
            processor.decrypt(input);
            assertEquals("plain", row.get("email"));
        }
        processor.encryptForExecution(null).run();
        processor.decrypt(null);
        processor.process(null);
        processor.process(new int[]{1, 2});
        assertNotNull(processor.encryptForExecution(new int[]{1, 2}));
        verify(crypto, times(4)).encryptWithCheck("plain");
        verify(crypto, times(2)).decryptWithCheckNoLog("cipher");
    }

    @Test
    public void scalarWritesAreRejectedButScalarQueryParametersAreUnchanged() throws Exception {
        for (Object scalar : Arrays.asList("text", 1, true, 'x', Thread.State.NEW)) {
            try {
                processor.encryptForExecution(scalar);
                fail("Unnamed scalar writes must be rejected");
            } catch (IllegalArgumentException error) {
                assertTrue(error.getMessage().contains("named parameters"));
            }
            processor.encryptQueryParameters(scalar).run();
            processor.decrypt(scalar);
            processor.process(scalar);
        }
        verifyZeroInteractions(crypto, redis);
    }

    @Test
    public void ordinaryMapsDoNotTraverseNestedRowsOrRewriteNonSensitiveAliases() throws Exception {
        Map<String, Object> row = row("plain");
        Map<String, Object> nested = row("nested");
        row.put("ordinary", row.get("email"));
        row.put("nested", nested);
        row.put("phoneNumber", null);
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        processor.encrypt(row);
        assertEquals("cipher", row.get("email"));
        assertEquals("plain", row.get("ordinary"));
        assertEquals("nested", nested.get("email"));
        assertNull(row.get("phoneNumber"));
        verify(crypto).encryptWithCheck("plain");
        verifyNoMoreInteractions(crypto);
    }

    @Test
    public void myBatisParamMapAndStrictMapUnwrapCollectionsAndArraysOnce() throws Exception {
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        for (Map<String, Object> wrapper : Arrays.asList(new MapperMethod.ParamMap<Object>(),
                new DefaultSqlSession.StrictMap<Object>())) {
            PlainRow bean = new PlainRow();
            bean.plain = "plain";
            Map<String, Object> row = row("plain");
            List<Object> rows = Arrays.asList(bean, row, bean);
            wrapper.put("list", rows);
            wrapper.put("collection", rows);
            wrapper.put("array", new Object[]{row, bean});
            wrapper.put("param1", rows);
            Runnable restore = processor.encryptForExecution(wrapper);
            assertEquals("cipher", bean.plain);
            assertEquals("cipher", row.get("email"));
            restore.run();
            assertEquals("plain", bean.plain);
            assertEquals("plain", row.get("email"));
        }
        verify(crypto, times(4)).encryptWithCheck("plain");
    }

    @Test
    public void namedScalarAliasesAreEncryptedTogetherAndAmbiguousAliasesAreRejected() throws Exception {
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        MapperMethod.ParamMap<Object> parameters = new MapperMethod.ParamMap<>();
        parameters.put("email", "plain");
        parameters.put("param1", parameters.get("email"));
        parameters.put("param12", parameters.get("email"));
        parameters.put("unrelated", "other");
        Runnable restore = processor.encryptForExecution(parameters);
        assertEquals("cipher", parameters.get("email"));
        assertEquals("cipher", parameters.get("param1"));
        assertEquals("cipher", parameters.get("param12"));
        assertEquals("other", parameters.get("unrelated"));
        restore.run();
        assertEquals("plain", parameters.get("email"));
        assertEquals("plain", parameters.get("param1"));
        for (Object alias : Arrays.asList("name", "param0", 7, null)) {
            Map raw = new MapperMethod.ParamMap<>();
            raw.put("email", "plain");
            raw.put(alias, raw.get("email"));
            try {
                processor.encrypt(raw);
                fail("Ambiguous alias accepted: " + alias);
            } catch (IllegalArgumentException error) {
                assertEquals("Ambiguous sensitive MyBatis parameter alias", error.getMessage());
                assertEquals("plain", raw.get("email"));
            }
        }
    }

    @Test
    public void invalidAnnotatedFieldModifiersAndTypesFailOnReadAndWrite() throws Exception {
        for (Object row : Arrays.asList(new StaticRow(), new FinalRow(), new NumericRow())) {
            try {
                processor.encrypt(row);
                fail("Invalid field accepted for write");
            } catch (IllegalStateException error) {
                assertTrue(error.getMessage().contains("writable String"));
            }
            try {
                processor.process(row);
                fail("Invalid field accepted for read");
            } catch (IllegalStateException error) {
                assertTrue(error.getMessage().contains("writable String"));
            }
        }
    }

    @Test
    public void numericSensitiveMapValuesCannotBypassEncryption() throws Exception {
        Map<String, Object> row = row("plain");
        row.put("email", 123L);
        try {
            processor.encrypt(row);
            fail("Numeric sensitive field must be rejected");
        } catch (IllegalArgumentException error) {
            assertEquals(123L, row.get("email"));
            assertNull(error.getCause());
        }
        processor.process(row);
        assertEquals(123L, row.get("email"));
        verifyZeroInteractions(crypto, redis);
    }

    @Test
    public void encryptionRejectsNullEmptyAndUnchangedResultsBeforeMutatingAnyRow() throws Exception {
        for (String cipher : Arrays.asList(null, "", "plain")) {
            when(crypto.encryptWithCheck("plain")).thenReturn(cipher);
            Map<String, Object> row = row("plain");
            try {
                processor.encrypt(row);
                fail("Invalid cipher accepted");
            } catch (IllegalStateException error) {
                assertEquals("plain", row.get("email"));
                assertTrue(error.getMessage().contains("invalid result"));
            }
        }
        for (String blank : Arrays.asList("", "  ")) {
            when(crypto.encryptWithCheck(blank)).thenReturn(blank);
            Map<String, Object> row = row(blank);
            processor.encrypt(row);
            assertEquals(blank, row.get("email"));
        }
    }

    @Test
    public void taggedNonSensitiveTextMayRemainUnchangedButSensitiveTextMustEncrypt() throws Exception {
        AnnotatedRow row = new AnnotatedRow();
        row.tagged = "ordinary text";
        when(crypto.encryptWithTag(row.tagged)).thenReturn(row.tagged);
        processor.encrypt(row);
        assertEquals("ordinary text", row.tagged);
        row.tagged = "call " + PHONE;
        when(crypto.encryptWithTag(row.tagged)).thenReturn(row.tagged);
        try {
            processor.encrypt(row);
            fail("Sensitive tagged plaintext must not pass through");
        } catch (IllegalStateException error) {
            assertEquals("call " + PHONE, row.tagged);
        }
    }

    @Test
    public void checkedEncryptionFailureLeavesAllStagedValuesUntouched() throws Exception {
        Map<String, Object> first = row("first");
        Map<String, Object> second = row("second");
        when(crypto.encryptWithCheck("first")).thenReturn("cipher");
        CheckException failure = mock(CheckException.class);
        when(crypto.encryptWithCheck("second")).thenThrow(failure);
        try {
            processor.encrypt(Arrays.asList(first, second));
            fail("Crypto failure must propagate");
        } catch (CheckException error) {
            assertSame(failure, error);
        }
        assertEquals("first", first.get("email"));
        assertEquals("second", second.get("email"));
    }

    @Test
    public void setterFailureRollsBackEarlierWrites() throws Exception {
        Map<String, Object> first = row("plain");
        Map<String, String> immutable = Collections.singletonMap("email", "plain");
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        try {
            processor.encrypt(Arrays.asList(first, immutable));
            fail("Immutable row must fail");
        } catch (UnsupportedOperationException error) {
            assertEquals("plain", first.get("email"));
            assertEquals("plain", immutable.get("email"));
        }
    }

    @Test
    public void restorationContinuesAfterMultipleFailuresAndKeepsSuppressedCauses() throws Exception {
        Map<String, Object> first = spy(row("one"));
        Map<String, Object> second = spy(row("two"));
        Map<String, Object> third = row("three");
        when(crypto.encryptWithCheck(anyString())).thenAnswer(call -> "cipher:" + call.getArguments()[0]);
        Runnable restore = processor.encryptForExecution(Arrays.asList(first, second, third));
        RuntimeException firstFailure = new IllegalStateException("first setter");
        RuntimeException secondFailure = new IllegalStateException("second setter");
        doThrow(firstFailure).when(first).put("email", "one");
        doThrow(secondFailure).when(second).put("email", "two");
        try {
            restore.run();
            fail("Restoration failure must propagate");
        } catch (RuntimeException error) {
            assertSame(secondFailure, error);
            assertArrayEquals(new Throwable[]{firstFailure}, error.getSuppressed());
            assertEquals("three", third.get("email"));
        }
    }

    @Test
    public void applyFailurePreservesRollbackFailureAsSuppressed() throws Exception {
        Map<String, Object> first = spy(row("plain"));
        RuntimeException rollbackFailure = new IllegalStateException("rollback failed");
        doThrow(rollbackFailure).when(first).put("email", "plain");
        when(crypto.encryptWithCheck("plain")).thenReturn("cipher");
        try {
            processor.encrypt(Arrays.asList(first, Collections.singletonMap("email", "plain")));
            fail("Apply failure must propagate");
        } catch (UnsupportedOperationException error) {
            assertArrayEquals(new Throwable[]{rollbackFailure}, error.getSuppressed());
            assertEquals("cipher", first.get("email"));
        }
    }

    @Test
    public void validMarkersRestoreBeforeAllThreeCodecsAndRestoreOriginalDisplayOnCleanup() throws Exception {
        when(redis.get(RedisRevealTokenStore.key(TOKEN))).thenReturn("fragment");
        when(crypto.decryptWithCheckNoLog("fragment")).thenReturn(PHONE);
        when(crypto.encryptWithCheck(PHONE)).thenReturn("normal-cipher");
        when(crypto.encryptWithTag("call " + PHONE + " / " + PHONE)).thenReturn("tag-cipher");
        when(crypto.encryptLongString(PHONE, 3)).thenReturn("long-cipher");
        AnnotatedRow row = new AnnotatedRow();
        row.plain = MARKER;
        row.tagged = "call " + MARKER + " / " + MARKER;
        row.longText = MARKER;
        Runnable restore = processor.encryptForExecution(row);
        assertEquals("normal-cipher", row.plain);
        assertEquals("tag-cipher", row.tagged);
        assertEquals("long-cipher", row.longText);
        restore.run();
        assertEquals(MARKER, row.plain);
        assertEquals("call " + MARKER + " / " + MARKER, row.tagged);
        assertEquals(MARKER, row.longText);
    }

    @Test
    public void malformedAndUnresolvedMaskFormatsAreRejectedWithoutChangingRows() throws Exception {
        for (String mask : Arrays.asList("138****5678", "a***@example.com", "****")) {
            for (String suffix : Arrays.asList("]", "", "\n", "\r", "#VIEW:bad]", "#VIEW:" + TOKEN)) {
                String original = "[#" + mask + suffix;
                Map<String, Object> row = row(original);
                try {
                    processor.encrypt(row);
                    fail("Unresolved marker accepted: " + original);
                } catch (IllegalArgumentException error) {
                    assertEquals(original, row.get("email"));
                    assertNull(error.getCause());
                }
            }
        }
        verifyZeroInteractions(crypto, redis);
    }

    @Test
    public void ordinaryMarkerLikeTextRemainsLiteral() throws Exception {
        when(crypto.encryptWithCheck(anyString())).thenAnswer(call -> "cipher:" + call.getArguments()[0]);
        for (String text : Arrays.asList("****", "[#", "#VIEW:bad]", "[#ordinary]", "138****5678",
                "[#ordinary#VIEW:" + TOKEN + "]", "notes [#123**456]")) {
            Map<String, Object> row = row(text);
            processor.encrypt(row);
            assertEquals("cipher:" + text, row.get("email"));
        }
        verifyZeroInteractions(redis);
    }

    @Test
    public void tokenExpiryEmptyCipherInvalidPlaintextAndMismatchedMaskAllRejectWrite() throws Exception {
        for (String ciphertext : Arrays.asList(null, "")) {
            when(redis.get(anyString())).thenReturn(ciphertext);
            try {
                processor.encrypt(row(MARKER));
                fail("Missing token ciphertext accepted");
            } catch (IllegalArgumentException error) {
                assertNull(error.getCause());
            }
        }
        when(redis.get(anyString())).thenReturn("fragment");
        for (String plaintext : Arrays.asList(null, "", "ordinary", "call " + PHONE, "13912345678", PHONE)) {
            when(crypto.decryptWithCheckNoLog("fragment")).thenReturn(plaintext);
            Map<String, Object> row = row(MARKER);
            when(crypto.encryptWithCheck(PHONE)).thenReturn("stored");
            if (PHONE.equals(plaintext)) {
                processor.encrypt(row);
                assertEquals("stored", row.get("email"));
            } else {
                try {
                    processor.encrypt(row);
                    fail("Invalid token plaintext accepted");
                } catch (IllegalArgumentException error) {
                    assertEquals(MARKER, row.get("email"));
                    assertNull(error.getCause());
                }
            }
        }
    }

    @Test
    public void tokenStoreAndCheckedDecryptionFailuresAreSanitized() throws Exception {
        when(redis.get(anyString())).thenThrow(new IllegalStateException("private backend detail"));
        try {
            processor.encrypt(row(MARKER));
            fail("Unavailable token accepted");
        } catch (IllegalArgumentException error) {
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("private backend detail"));
        }
        doReturn("fragment").when(redis).get(anyString());
        when(crypto.decryptWithCheckNoLog("fragment")).thenThrow(mock(CheckException.class));
        try {
            processor.encrypt(row(MARKER));
            fail("Decryption failure accepted");
        } catch (IllegalArgumentException error) {
            assertNull(error.getCause());
        }
    }

    @Test
    public void readMarkersPreserveSurroundingTextAndStoreOnlyIndividualFragments() throws Exception {
        Map<String, Object> row = row("cipher");
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("call " + PHONE + " or " + EMAIL + " today");
        when(crypto.encryptWithCheck(anyString())).thenAnswer(call -> "fragment:" + call.getArguments()[0]);
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        processor.process(row);
        String secondToken = token((byte) 1);
        assertEquals("call " + MARKER + " or [#a***@example.com#VIEW:" + secondToken + "] today", row.get("email"));
        ArgumentCaptor<Map> batch = ArgumentCaptor.forClass(Map.class);
        verify(redis).setexBatch(batch.capture(), eq(1800));
        assertEquals(2, batch.getValue().size());
        assertEquals("fragment:" + PHONE, batch.getValue().get(RedisRevealTokenStore.key(TOKEN)));
        assertEquals("fragment:" + EMAIL, batch.getValue().get(RedisRevealTokenStore.key(secondToken)));
    }

    @Test
    public void failedRedisBatchesProduceMasksWithoutTokens() throws Exception {
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn(PHONE);
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.FAIL_RESULT)
                .thenThrow(new IllegalStateException("Redis offline"));
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> row = row("cipher");
            processor.process(row);
            assertEquals("[#138****5678]", row.get("email"));
        }
        verify(crypto, never()).encryptWithCheck(anyString());
    }

    @Test
    public void fragmentEncryptionFailureMasksOnlyAffectedFragmentAndIsDeduplicated() throws Exception {
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn("call " + PHONE + " / " + PHONE);
        when(crypto.encryptWithCheck(PHONE)).thenThrow(mock(CheckException.class))
                .thenThrow(new IllegalStateException("crypto unavailable"));
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> row = row("cipher");
            processor.process(row);
            assertEquals("call [#138****5678] / [#138****5678]", row.get("email"));
        }
        verify(crypto, times(2)).encryptWithCheck(PHONE);
        verifyZeroInteractions(redis);
    }

    @Test
    public void revealPreservesNullEmptyAndOrdinaryValuesWithoutRedis() throws Exception {
        AnnotatedRow bean = new AnnotatedRow();
        bean.plain = "empty";
        bean.tagged = "ordinary";
        bean.longText = "null-plaintext";
        when(crypto.decryptWithCheckNoLog("empty")).thenReturn("");
        when(crypto.decryptWithTag("ordinary")).thenReturn("ordinary text");
        when(crypto.decryptLongString("null-plaintext")).thenReturn(null);
        processor.process(bean);
        assertEquals("", bean.plain);
        assertEquals("ordinary text", bean.tagged);
        assertNull(bean.longText);
        assertNull(bean.empty);
        assertEquals("untouched", bean.ordinary);
        verifyZeroInteractions(redis);
    }

    @Test
    public void nullNormalPlaintextIsCachedAndDoesNotCreateTokens() throws Exception {
        when(crypto.decryptWithCheckNoLog("null-result")).thenReturn(null);
        Map<String, Object> first = row("null-result");
        Map<String, Object> second = row("null-result");
        processor.process(Arrays.asList(first, second));
        assertNull(first.get("email"));
        assertNull(second.get("email"));
        verify(crypto).decryptWithCheckNoLog("null-result");
        verifyZeroInteractions(redis);
    }

    @Test
    public void checkedAndRuntimeReadFailuresAreCachedAcrossMapsAndBeansPerQuery() throws Exception {
        CheckException checked = mock(CheckException.class);
        RuntimeException runtime = new IllegalStateException("crypto unavailable");
        when(crypto.decryptWithCheckNoLog("checked")).thenThrow(checked);
        when(crypto.decryptWithCheckNoLog("runtime")).thenThrow(runtime);
        for (String cipher : Arrays.asList("checked", "runtime")) {
            PlainRow bean = new PlainRow();
            bean.plain = cipher;
            Map<String, Object> first = row(cipher);
            Map<String, Object> second = row(cipher);
            processor.process(Arrays.asList(first, second, bean));
            assertEquals("[#****]", first.get("email"));
            assertEquals("[#****]", second.get("email"));
            assertEquals("[#****]", bean.plain);
            verify(crypto).decryptWithCheckNoLog(cipher);
            bean.plain = cipher;
            processor.process(bean);
            assertEquals("[#****]", bean.plain);
            verify(crypto, times(2)).decryptWithCheckNoLog(cipher);
        }
        verifyZeroInteractions(redis);
    }

    @Test
    public void plaintextReadCheckedFailureIsWrappedAndDirectDecryptPreservesCause() throws Exception {
        CheckException failure = mock(CheckException.class);
        when(crypto.decryptWithCheckNoLog("bad")).thenThrow(failure);
        try {
            processor.process(row("bad"), value -> true);
            fail("Plaintext row failure must propagate");
        } catch (IllegalStateException error) {
            assertSame(failure, error.getCause());
        }
        try {
            processor.decrypt(row("bad"));
            fail("Direct decryption failure must propagate");
        } catch (CheckException error) {
            assertSame(failure, error);
        }
    }

    @Test
    public void successfulReadCacheIsCodecSpecificAndSharedWithPlaintextRows() throws Exception {
        when(crypto.decryptWithCheckNoLog("same")).thenReturn(EMAIL);
        when(crypto.decryptWithTag("same")).thenReturn("call " + PHONE + " / " + PHONE);
        when(crypto.decryptLongString("same")).thenReturn("call " + PHONE);
        when(crypto.encryptWithCheck(PHONE)).thenReturn("phone-fragment");
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        for (int query = 0; query < 2; query++) {
            AnnotatedRow first = new AnnotatedRow();
            AnnotatedRow second = new AnnotatedRow();
            first.plain = first.tagged = first.longText = "same";
            second.plain = second.tagged = second.longText = "same";
            Map<String, Object> plaintextRow = row("same");
            List<Object> rows = Arrays.asList(plaintextRow, first, second, first);
            processor.process(rows, value -> value == plaintextRow);
            assertEquals(EMAIL, plaintextRow.get("email"));
            assertEquals(first.plain, second.plain);
            assertEquals(first.tagged, second.tagged);
            assertTrue(first.plain.contains("#VIEW:"));
            String phoneMarker = first.longText.substring("call ".length());
            assertEquals("call " + phoneMarker + " / " + phoneMarker, first.tagged);
            assertSame(first, rows.get(3));
        }
        verify(crypto, times(2)).decryptWithCheckNoLog("same");
        verify(crypto, times(2)).decryptWithTag("same");
        verify(crypto, times(2)).decryptLongString("same");
        verify(crypto, times(2)).encryptWithCheck(PHONE);
        verify(crypto, never()).encryptWithCheck(EMAIL);
        ArgumentCaptor<Map> batches = ArgumentCaptor.forClass(Map.class);
        verify(redis, times(2)).setexBatch(batches.capture(), eq(1800));
        for (Map batch : batches.getAllValues()) {
            assertEquals(2, batch.size());
            assertTrue(batch.containsValue("same"));
            assertTrue(batch.containsValue("phone-fragment"));
        }
        assertTrue(Collections.disjoint(batches.getAllValues().get(0).keySet(),
                batches.getAllValues().get(1).keySet()));
    }

    @Test
    public void fullReadEditWriteRoundTripRestoresFragmentsWithoutReencryptingCiphertext() throws Exception {
        Map<String, String> tokenValues = new HashMap<>();
        when(redis.setexBatch(anyMap(), eq(1800))).thenAnswer(call -> {
            tokenValues.putAll((Map<String, String>) call.getArguments()[0]);
            return ResultUtil.SUCCESS_RESULT;
        });
        when(redis.get(anyString())).thenAnswer(call -> tokenValues.get(call.getArguments()[0]));
        when(crypto.encryptWithCheck(anyString())).thenAnswer(call -> "enc:" + call.getArguments()[0]);
        when(crypto.decryptWithCheckNoLog(anyString())).thenAnswer(call ->
                ((String) call.getArguments()[0]).substring(4));
        String original = "notes [#ordinary] **** call " + PHONE + " or " + EMAIL;
        Map<String, Object> row = row(original);
        processor.encrypt(row);
        assertEquals("enc:" + original, row.get("email"));
        processor.process(row);
        assertTrue(((String) row.get("email")).contains("#VIEW:"));
        assertEquals(2, tokenValues.size());
        String display = (String) row.get("email");
        Runnable restore = processor.encryptForExecution(row);
        assertEquals("enc:" + original, row.get("email"));
        restore.run();
        assertEquals(display, row.get("email"));
    }

    @Test
    public void hundredRowsKeepIdsOrderAndUseOneTokenPerDistinctWholeField() throws Exception {
        processor = new SensitiveRevealProcessor(crypto, store);
        when(crypto.decryptWithCheckNoLog(anyString())).thenAnswer(call ->
                ((String) call.getArguments()[0]).substring(7));
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> row = row("cipher:user" + i + "@example.com");
            row.put("id", i);
            row.put("mobileNumber", "cipher:" + (13812345000L + i));
            row.put("telNumber", "cipher:021-" + (12345000L + i));
            rows.add(row);
        }
        List<Map<String, Object>> original = new ArrayList<>(rows);
        processor.process(rows);
        assertEquals(100, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertSame(original.get(i), rows.get(i));
            assertEquals(i, rows.get(i).get("id"));
            for (String key : Arrays.asList("email", "mobileNumber", "telNumber")) {
                assertTrue(((String) rows.get(i).get(key)).contains("#VIEW:rt_"));
            }
        }
        ArgumentCaptor<Map> batches = ArgumentCaptor.forClass(Map.class);
        verify(redis, times(15)).setexBatch(batches.capture(), eq(1800));
        Set<String> keys = new HashSet<>();
        for (Map<String, String> batch : batches.getAllValues()) {
            assertEquals(20, batch.size());
            keys.addAll(batch.keySet());
            for (String value : batch.values()) assertTrue(value.startsWith("cipher:"));
        }
        assertEquals(300, keys.size());
        verify(crypto, times(300)).decryptWithCheckNoLog(anyString());
        verify(crypto, never()).encryptWithCheck(anyString());
    }

    @Test
    public void phoneShapedEmailIsOneEmailFragment() throws Exception {
        when(crypto.decryptWithCheckNoLog("cipher")).thenReturn(PHONE + "@example.com");
        when(redis.setexBatch(anyMap(), eq(1800))).thenReturn(ResultUtil.SUCCESS_RESULT);
        Map<String, Object> row = row("cipher");
        processor.process(row);
        assertEquals("[#1***@example.com#VIEW:" + TOKEN + "]", row.get("email"));
        verify(crypto, never()).encryptWithCheck(anyString());
    }

    @Test
    public void maskAndSimpleTypeDefensiveBoundariesAreExplicit() throws Exception {
        Method mask = SensitiveRevealProcessor.class.getDeclaredMethod("mask", String.class);
        mask.setAccessible(true);
        assertEquals("a***@example.com", mask.invoke(null, EMAIL));
        assertEquals("138****5678", mask.invoke(null, PHONE));
        assertEquals("021****5678", mask.invoke(null, "021-12345678"));
        assertEquals("123****5678", mask.invoke(null, "12345678"));
        assertEquals("****", mask.invoke(null, "1234567"));
        assertEquals("****", mask.invoke(null, "@"));
        Method checked = SensitiveRevealProcessor.class.getDeclaredMethod("checkedCipher",
                String.class, String.class, boolean.class);
        checked.setAccessible(true);
        assertNull(checked.invoke(null, null, null, false));
        Method simple = SensitiveRevealProcessor.class.getDeclaredMethod("isSimple", Class.class);
        simple.setAccessible(true);
        assertEquals(true, simple.invoke(null, int.class));
        assertEquals(false, simple.invoke(null, PlainRow.class));
    }

    private static Map<String, Object> row(String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("email", email);
        return row;
    }

    private static String token(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static class PlainRow {
        @EnDecryptField String plain;
    }

    private static class AnnotatedRow extends PlainRow {
        @EnDecryptFieldWithTag String tagged;
        @EnDecryptFieldLong(chunkSize = 3) String longText;
        @EnDecryptField String empty;
        String ordinary = "untouched";
    }

    private static class StaticRow {
        @EnDecryptField static String value;
    }

    private static class FinalRow {
        @EnDecryptField final String value = "final";
    }

    private static class NumericRow {
        @EnDecryptField Integer value = 1;
    }
}
