package com.union.control.utils.security;

import com.epcc.commons.securityproxy.api.SecurityResult;
import com.epcc.commons.securityproxy.api.SymmetricalSecurityService;
import com.epcc.dubbo.result.Result;
import com.nucc.channel.ark.common.exception.CheckException;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

public class SymmetricalSecurityUtilsTest {
    @Test public void malformedSuccessfulEncryptionCannotProduceEmptyStorageValues() throws Exception {
        SymmetricalSecurityService gateway = mock(SymmetricalSecurityService.class);
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(gateway);
        for (Result<SecurityResult> response : Arrays.<Result<SecurityResult>>asList(null,
                Result.success(null), Result.success(new SecurityResult(null)),
                Result.success(new SecurityResult(new byte[0])))) {
            when(gateway.encryptByFixedKey(any(SymmetricalSecurityService.Algorithm.class), any(byte[].class)))
                    .thenReturn(response);
            try { crypto.encryptWithCheck("13812345678"); fail("Invalid gateway result accepted"); }
            catch (CheckException expected) { }
        }
    }

    @Test public void longTextChunkingPreservesUnicode() throws Exception {
        SymmetricalSecurityService gateway = mock(SymmetricalSecurityService.class);
        when(gateway.encryptByFixedKey(any(SymmetricalSecurityService.Algorithm.class), any(byte[].class)))
                .thenAnswer(call -> Result.success(new SecurityResult((byte[]) call.getArguments()[1])));
        when(gateway.decryptByFixedKey(any(SymmetricalSecurityService.Algorithm.class), any(byte[].class)))
                .thenAnswer(call -> Result.success(new SecurityResult((byte[]) call.getArguments()[1])));
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(gateway);
        String original = "前🚀后，联系13812345678";
        for (int chunkSize = 1; chunkSize <= 5; chunkSize++) {
            assertEquals(original, crypto.decryptLongString(crypto.encryptLongString(original, chunkSize)));
        }
    }

    @Test public void taggedEncryptionDoesNotScanItsOwnCiphertext() throws Exception {
        SymmetricalSecurityService gateway = mock(SymmetricalSecurityService.class);
        SymmetricalSecurityUtils crypto = new SymmetricalSecurityUtils(gateway);
        String ciphertext = "13812345678A";
        when(gateway.encryptByFixedKey(any(SymmetricalSecurityService.Algorithm.class), any(byte[].class)))
                .thenReturn(Result.success(new SecurityResult(java.util.Base64.getDecoder().decode(ciphertext))))
                .thenThrow(new IllegalStateException("Ciphertext was incorrectly encrypted again"));
        assertEquals("call #[" + ciphertext + "]", crypto.encryptWithTag("call 13812345678"));
        verify(gateway, times(1)).encryptByFixedKey(any(SymmetricalSecurityService.Algorithm.class), any(byte[].class));
    }
}
