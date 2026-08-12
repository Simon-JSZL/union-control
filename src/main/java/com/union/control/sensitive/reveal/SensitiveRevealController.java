package com.union.control.sensitive.reveal;

import com.union.control.service.LocalAuth;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/sensitive/reveal")
public class SensitiveRevealController {
    private final SensitiveRevealService service;

    public SensitiveRevealController(SensitiveRevealService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> reveal(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        String userId = LocalAuth.authenticate(cookie);
        Object token = payload == null ? null : payload.get("token");
        String plaintext = service.reveal(userId, token instanceof String ? (String) token : null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Collections.singletonMap("value", plaintext));
    }
}
