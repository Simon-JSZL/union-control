package com.epcc.arkweb.web;

import com.union.control.service.ConversationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** ponytail: Local-only identity mock; production replaces it with real authentication. */
@RestController
public class CommonController {
    private final ConversationService service;

    public CommonController(ConversationService service) {
        this.service = service;
    }

    @GetMapping("/common/getUserInfo")
    public Map<String, Object> getUserInfo() {
        return service.userInfo();
    }
}
