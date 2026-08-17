package com.epcc.arkweb.web.sensitive.reveal;

import com.epcc.arkweb.helper.AuthContextHolder;
import com.epcc.arkweb.model.ShiroUser;
import com.union.control.service.ServiceExceptions;
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

    public SensitiveRevealController(SensitiveRevealService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> reveal(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        ShiroUser user = AuthContextHolder.getAuthUserDetails();
        if (user == null || user.getLoginName() == null || user.getLoginName().trim().isEmpty())
            throw new ServiceExceptions.UnauthorizedException();
        Object token = payload == null ? null : payload.get("token");
        String plaintext = service.reveal(
                user.getLoginName(), token instanceof String ? (String) token : null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Collections.singletonMap("value", plaintext));
    }
}
