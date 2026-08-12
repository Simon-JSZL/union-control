package com.union.control.sensitive.reveal;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.union.control.sensitive.interceptor.DecryptedValueHook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MaskedRevealHookTest {
    @Test
    public void replacesPlaintextWithAMaskAndAnOwnerBoundToken() {
        RecordingStore store = new RecordingStore();
        MaskedRevealHook hook = new MaskedRevealHook(store);

        String value = hook.afterDecrypt("user-1", Collections.singletonList(
                new DecryptedValueHook.Value("ciphertext", "联系人手机号：13800138000"))).get(0);

        assertTrue(value.matches("\\[#联系人手机号：138\\*{5}000#VIEW:rt_[A-Za-z0-9_-]{43}\\]"));
        assertEquals("user-1", store.userId);
        assertEquals("ciphertext", store.ciphertext);
        assertFalse(store.toString().contains("13800138000"));
    }

    @Test
    public void tokenStoreFailureReturnsMaskedNonClickableText() {
        RecordingStore store = new RecordingStore();
        store.failure = true;
        MaskedRevealHook hook = new MaskedRevealHook(store);

        String value = hook.afterDecrypt("user-1", Collections.singletonList(
                new DecryptedValueHook.Value("ciphertext", "987654321098"))).get(0);

        assertEquals("[#9876*****1098]", value);
    }

    @Test
    public void redisKeysBindTheTokenToItsOwner() {
        assertFalse(Arrays.equals(
                RedisRevealTokenStore.key("user-1", "rt_token"),
                RedisRevealTokenStore.key("user-2", "rt_token")));
    }

    @Test
    public void aResultSetUsesOneTokenStoreBatch() {
        RecordingStore store = new RecordingStore();
        MaskedRevealHook hook = new MaskedRevealHook(store);
        List<DecryptedValueHook.Value> values = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            values.add(new DecryptedValueHook.Value("ciphertext-" + i, "1234567890" + i));

        List<String> result = hook.afterDecrypt("user-1", values);

        assertEquals(1, store.batchCalls);
        assertEquals(20, store.batchSize);
        assertEquals(20, result.size());
    }

    static class RecordingStore implements RevealTokenStore {
        String userId;
        String token;
        String ciphertext;
        boolean failure;
        int batchCalls;
        int batchSize;

        @Override
        public boolean putAll(String userId, List<Entry> entries) {
            batchCalls++;
            batchSize = entries.size();
            if (failure) throw new StoreUnavailableException();
            this.userId = userId;
            this.token = entries.get(0).token;
            this.ciphertext = entries.get(0).ciphertext;
            return true;
        }

        @Override
        public String getCiphertext(String userId, String token) {
            return this.userId != null && this.userId.equals(userId) && this.token.equals(token)
                    ? ciphertext : null;
        }

        @Override
        public String toString() {
            return "RecordingStore{ciphertextLength=" + (ciphertext == null ? 0 : ciphertext.length()) + "}";
        }
    }
}
