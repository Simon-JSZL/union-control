package com.union.control.sensitive.reveal;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.union.control.sensitive.interceptor.SensitiveCrypto;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class SensitiveRevealServiceTest {
    @Test
    public void logsRevealCompletionWithoutOwnerTokenCiphertextOrPlaintext() {
        String token = "rt_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        RevealTokenStore tokens = new RevealTokenStore() {
            @Override
            public boolean putAll(String userId, List<Entry> entries) {
                return true;
            }

            @Override
            public String getCiphertext(String userId, String suppliedToken) {
                return "ciphertext-secret";
            }
        };
        SensitiveCrypto crypto = new SensitiveCrypto() {
            @Override
            public String encrypt(String plaintext) {
                return plaintext;
            }

            @Override
            public String decrypt(String ciphertext) {
                return "plaintext-secret";
            }
        };
        ListAppender<ILoggingEvent> logs = logs();

        String result = new SensitiveRevealService(tokens, crypto, new ObjectMapper())
                .reveal("{\"userId\":\"user-secret\",\"token\":\"" + token + "\"}");

        String messages = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(result).isEqualTo("plaintext-secret");
        assertThat(messages).contains("Sensitive field reveal decryption completed")
                .doesNotContain("user-secret")
                .doesNotContain(token)
                .doesNotContain("ciphertext-secret")
                .doesNotContain("plaintext-secret");
    }

    private static ListAppender<ILoggingEvent> logs() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(SensitiveRevealService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
