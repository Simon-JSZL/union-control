package com.epcc.arkweb.web.sensitive.reveal;

import com.union.control.service.sensitive.RedisRevealTokenStore.StoreUnavailableException;
import com.union.control.service.sensitive.SensitiveRevealService;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.UnauthenticatedException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice(assignableTypes = SensitiveRevealController.class)
public class SensitiveRevealExceptionHandler {
    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, String>> authorization(AuthorizationException error) {
        HttpStatus status = error instanceof UnauthenticatedException
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return error(status, status == HttpStatus.UNAUTHORIZED ? "UNAUTHORIZED" : "FORBIDDEN");
    }

    @ExceptionHandler(SensitiveRevealService.InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> invalidToken() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REVEAL_TOKEN");
    }

    @ExceptionHandler(SensitiveRevealService.ExpiredTokenException.class)
    public ResponseEntity<Map<String, String>> expiredToken() {
        return error(HttpStatus.GONE, "REVEAL_TOKEN_EXPIRED");
    }

    @ExceptionHandler(StoreUnavailableException.class)
    public ResponseEntity<Map<String, String>> storeUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "REVEAL_STORE_UNAVAILABLE");
    }

    @ExceptionHandler(SensitiveRevealService.RevealDecryptionException.class)
    public ResponseEntity<Map<String, String>> decryptionFailed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "REVEAL_DECRYPT_FAILED");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
