package com.epcc.commons.securityproxy.api;

import com.epcc.dubbo.result.Result;

/** Local compile-time compatibility stub. Production supplies this interface. */
public interface SymmetricalSecurityService {
    enum Algorithm { AES256 }

    Result<SecurityResult> encryptByFixedKey(Algorithm algorithm, byte[] plaintext);
    Result<SecurityResult> decryptByFixedKey(Algorithm algorithm, byte[] ciphertext);
}
