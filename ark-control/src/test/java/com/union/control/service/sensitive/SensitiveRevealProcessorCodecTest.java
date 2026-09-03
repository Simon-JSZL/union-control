package com.union.control.service.sensitive;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.annotation.EnDecryptField;
import com.nucc.channel.ark.common.exception.BaseDataErrorCode;
import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class SensitiveRevealProcessorCodecTest {
    @Test
    public void encryptsEveryElementOfADirectListParameter() throws Exception {
        SensitiveRevealProcessor codec = processor(new PrefixGateway());
        List<Contact> contacts = new ArrayList<>();
        contacts.add(new Contact("13800138000"));
        contacts.add(new Contact("13900139000"));

        codec.encrypt(contacts);

        assertEquals(ciphertext("13800138000"), contacts.get(0).mobile);
        assertEquals(ciphertext("13900139000"), contacts.get(1).mobile);
    }

    @Test
    public void encryptsSensitiveKeysInAPlainMapParameter() throws Exception {
        SensitiveRevealProcessor codec = processor(new PrefixGateway());
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("email", "demo@example.com");
        parameter.put("role", "001");

        codec.encrypt(parameter);

        assertEquals(ciphertext("demo@example.com"), parameter.get("email"));
        assertEquals("001", parameter.get("role"));
    }

    @Test
    public void keepsMyBatisScalarAliasesOnTheSameCiphertext() throws Exception {
        SensitiveRevealProcessor codec = processor(new PrefixGateway());
        Map<String, Object> parameter = new HashMap<>();
        String email = new String("demo@example.com");
        parameter.put("email", email);
        parameter.put("param1", email);

        codec.encrypt(parameter);

        assertEquals(ciphertext("demo@example.com"), parameter.get("email"));
        assertEquals(parameter.get("email"), parameter.get("param1"));
    }

    @Test(expected = CheckException.class)
    public void encryptionFailureIsNotSwallowed() throws Exception {
        SensitiveRevealProcessor codec = processor(new FailingGateway());
        codec.encrypt(new Contact("13800138000"));
    }

    private static SensitiveRevealProcessor processor(SymmetricalSecurityService gateway) {
        return new SensitiveRevealProcessor(new SymmetricalSecurityUtils(gateway), null);
    }

    private static String ciphertext(String plaintext) {
        return Base64.getEncoder().encodeToString(
                ("enc:" + plaintext).getBytes(StandardCharsets.UTF_8));
    }

    static class Contact {
        @EnDecryptField
        String mobile;

        Contact(String mobile) {
            this.mobile = mobile;
        }
    }

    static class PrefixGateway implements SymmetricalSecurityService {
        @Override
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            return Result.success(new SecurityResult(
                    ("enc:" + new String(plaintext, StandardCharsets.UTF_8))
                            .getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
            return Result.success(new SecurityResult(ciphertext));
        }
    }

    static class FailingGateway implements SymmetricalSecurityService {
        @Override
        public Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext) {
            return Result.failure(BaseDataErrorCode.SYSTEM_INNER_ERROR.getCode(), "unavailable");
        }

        @Override
        public Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext) {
            return Result.failure(BaseDataErrorCode.SYSTEM_INNER_ERROR.getCode(), "unavailable");
        }
    }
}
