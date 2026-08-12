package com.union.control.sensitive.reveal;

import com.union.control.controller.ApiExceptionHandler;
import com.union.control.sensitive.interceptor.SensitiveCrypto;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class SensitiveRevealControllerTest {
    private static final String TOKEN = "rt_abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
    private MockMvc mvc;

    @Before
    public void setUp() {
        RevealTokenStore store = new RevealTokenStore() {
            @Override
            public boolean putAll(String userId, List<Entry> entries) {
                return true;
            }

            @Override
            public String getCiphertext(String userId, String token) {
                return "user-1".equals(userId) && TOKEN.equals(token) ? "ciphertext" : null;
            }
        };
        SensitiveCrypto crypto = new SensitiveCrypto() {
            public String encrypt(String plaintext) { return "ciphertext"; }
            public String decrypt(String ciphertext) { return "123456789012"; }
        };
        SensitiveRevealService service = new SensitiveRevealService(store, crypto);
        mvc = standaloneSetup(new SensitiveRevealController(service))
                .setControllerAdvice(new SensitiveRevealExceptionHandler(), new ApiExceptionHandler()).build();
    }

    @Test
    public void revealIsAnIndependentAuthenticatedNoStoreApi() throws Exception {
        mvc.perform(post("/api/sensitive/reveal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/sensitive/reveal")
                .header(HttpHeaders.COOKIE, "CASSESSIONID=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.value").value("123456789012"));
    }

    @Test
    public void invalidTokenUsesTheRevealErrorContract() throws Exception {
        mvc.perform(post("/api/sensitive/reveal")
                .header(HttpHeaders.COOKIE, "CASSESSIONID=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REVEAL_TOKEN"));
    }

    @Test
    public void missingOrWrongOwnerTokenIsReportedAsExpired() throws Exception {
        String missing = "rt_abcdefghijklmnopqrstuvwxyzABCDEFGH123456780";
        mvc.perform(post("/api/sensitive/reveal")
                .header(HttpHeaders.COOKIE, "CASSESSIONID=session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + missing + "\"}"))
                .andExpect(status().isGone())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("REVEAL_TOKEN_EXPIRED"));
    }
}
