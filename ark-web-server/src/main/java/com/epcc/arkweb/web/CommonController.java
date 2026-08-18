package com.epcc.arkweb.web;

import com.union.control.service.ConversationService;
import com.epcc.arkweb.helper.AuthenticatedRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** ponytail: Local-only identity mock; production replaces it with real authentication. */
@RestController
public class CommonController {
    private final ConversationService service;
    private final AuthenticatedRequest request;

    public CommonController(ConversationService service, AuthenticatedRequest request) {
        this.service = service;
        this.request = request;
    }

    @GetMapping("/common/getUserInfo")
    public Map<String, Object> getUserInfo() {
        return service.userInfo(request.json());
    }
}
