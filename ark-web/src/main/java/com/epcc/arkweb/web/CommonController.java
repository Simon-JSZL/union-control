package com.epcc.arkweb.web;

import com.union.control.service.ConversationService;
import com.epcc.arkweb.helper.AuthContextHolder;
import com.epcc.arkweb.model.ShiroUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Production-style authenticated user-info endpoint. */
@RestController
public class CommonController {
    private final ConversationService service;
    private final ObjectMapper json;

    public CommonController(ConversationService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping("/common/getUserInfo")
    public Map<String, Object> getUserInfo() {
        ShiroUser user = AuthContextHolder.getAuthUserDetails();
        return service.userInfo(json.createObjectNode()
                .put("userId", user.getLoginName()).put("orgCode", user.getOrgCode()).toString());
    }
}
