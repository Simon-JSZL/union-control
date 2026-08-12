package com.union.control.sensitive.interceptor;

import java.util.List;

/** Extension point entered once after the existing database interceptor decrypts a result batch. */
public interface DecryptedValueHook {
    List<String> afterDecrypt(String userId, List<Value> values);

    class Value {
        private final String ciphertext;
        private final String plaintext;

        public Value(String ciphertext, String plaintext) {
            this.ciphertext = ciphertext;
            this.plaintext = plaintext;
        }

        public String getCiphertext() { return ciphertext; }
        public String getPlaintext() { return plaintext; }
    }
}
