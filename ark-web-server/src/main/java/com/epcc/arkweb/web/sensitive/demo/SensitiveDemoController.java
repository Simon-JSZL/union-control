package com.epcc.arkweb.web.sensitive.demo;

import com.union.control.sensitive.demo.SensitiveDemoService;
import com.epcc.arkweb.helper.AuthenticatedRequest;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sensitive/demo")
@RequiresPermissions("agent:execute")
public class SensitiveDemoController {
    private final SensitiveDemoService service;
    private final AuthenticatedRequest request;

    public SensitiveDemoController(SensitiveDemoService service, AuthenticatedRequest request) {
        this.service = service;
        this.request = request;
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.create(request.json(payload));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(request.json()));
    }
}
