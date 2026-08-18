package com.epcc.arkweb.web.sensitive.reveal;

import com.epcc.arkweb.web.sensitive.demo.SensitiveDemoController;
import com.union.control.sensitive.interceptor.SensitiveAesInterceptor;
import com.union.control.sensitive.reveal.SensitiveRevealService;
import com.union.control.sensitive.reveal.StoreUnavailableException;
import org.apache.shiro.authz.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ControllerAdvice(assignableTypes = {SensitiveDemoController.class, SensitiveRevealController.class})
public class SensitiveRevealExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SensitiveRevealExceptionHandler.class);

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, String>> forbidden(AuthorizationException error) {
        HttpStatus status = error instanceof org.apache.shiro.authz.UnauthenticatedException
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return error(status, status == HttpStatus.UNAUTHORIZED ? "UNAUTHORIZED" : "FORBIDDEN",
                status == HttpStatus.UNAUTHORIZED ? "缺少或无效登录会话" : "缺少接口访问权限");
    }

    @ExceptionHandler(SensitiveRevealService.InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> invalidToken() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REVEAL_TOKEN", "查看凭证格式错误");
    }

    @ExceptionHandler(SensitiveRevealService.ExpiredTokenException.class)
    public ResponseEntity<Map<String, String>> expiredToken() {
        return error(HttpStatus.GONE, "REVEAL_TOKEN_EXPIRED", "查看凭证已过期，请刷新后重试");
    }

    @ExceptionHandler(StoreUnavailableException.class)
    public ResponseEntity<Map<String, String>> redisUnavailable() {
        LOG.error("Sensitive reveal token store unavailable, requestId={}", UUID.randomUUID());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "REVEAL_SERVICE_UNAVAILABLE", "服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler({SensitiveRevealService.DecryptionException.class,
            SensitiveAesInterceptor.DecryptionException.class})
    public ResponseEntity<Map<String, String>> decryptionFailed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "SENSITIVE_DECRYPT_FAILED", "敏感数据解密失败");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数错误");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
