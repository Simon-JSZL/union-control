package com.union.control.sensitive.demo;

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
public class SensitiveDemoController {
    private final SensitiveDemoService service;

    public SensitiveDemoController(SensitiveDemoService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie,
            @RequestBody Map<String, Object> payload) {
        return service.create(cookie, payload);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(cookie));
    }
}
