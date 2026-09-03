package com.epcc.arkweb.web.sensitive.reveal;

import com.epcc.arkweb.helper.AuthenticatedRequest;
import com.union.control.service.SensitiveDataDemoService;
import com.union.control.service.sensitive.SensitiveRevealService;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sensitive/reveal")
public class SensitiveRevealController {
    private final SensitiveRevealService service;
    private final SensitiveDataDemoService demoService;
    private final AuthenticatedRequest request;

    public SensitiveRevealController(SensitiveRevealService service,
            SensitiveDataDemoService demoService, AuthenticatedRequest request) {
        this.service = service;
        this.demoService = demoService;
        this.request = request;
    }

    @PostMapping
    @RequiresPermissions(value = "/assistantManager/page")
    public ResponseEntity<Map<String, String>> reveal(@RequestBody Map<String, Object> payload) {
        String plaintext = service.reveal(request.json(payload));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Collections.singletonMap("value", plaintext));
    }

    @PostMapping("/demo/insert")
    @RequiresPermissions(value = "/assistantManager/page")
    public Map<String, Integer> insertDemo(@RequestBody Map<String, Object> payload) {
        return Collections.singletonMap("affectedRows", demoService.insert(request.json(payload)));
    }

    @GetMapping("/demo/query")
    @RequiresPermissions(value = "/assistantManager/page")
    public List<Map<String, Object>> queryDemo() {
        return demoService.query(request.json());
    }

    @PostMapping("/demo/address-book/insert")
    @RequiresPermissions(value = "/assistantManager/page")
    public Map<String, Integer> insertAddressBookDemo(
            @RequestBody Map<String, Object> payload) {
        return Collections.singletonMap("affectedRows",
                demoService.insertAddressBook(request.json(payload)));
    }

    @GetMapping("/demo/address-book/query")
    @RequiresPermissions(value = "/assistantManager/page")
    public List<Map<String, Object>> queryAddressBookDemo() {
        return demoService.queryAddressBook(request.json());
    }
}
