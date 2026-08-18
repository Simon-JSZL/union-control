package com.epcc.arkweb.web.sensitive.reveal;

import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.union.control.sensitive.reveal.SensitiveRevealService;
import org.apache.shiro.authz.annotation.RequiresPermissions;
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
@RequiresPermissions("agent:execute")
public class SensitiveRevealController {
    private final SensitiveRevealService service;
    private final AuthenticatedRequest request;

    public SensitiveRevealController(SensitiveRevealService service, AuthenticatedRequest request) {
        this.service = service;
        this.request = request;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> reveal(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        String plaintext = service.reveal(request.json(payload));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Collections.singletonMap("value", plaintext));
    }
}
