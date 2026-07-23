package com.union.control.controller;

import com.union.control.service.ControlService;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** ponytail: Local-only identity mock; production replaces it with real authentication. */
@RestController
public class CommonController {
    private final ControlService service;

    public CommonController(ControlService service) {
        this.service = service;
    }

    @GetMapping("/common/getUserInfo")
    public Map<String, Object> getUserInfo(
            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookie) {
        return service.userInfo(cookie);
    }
}
