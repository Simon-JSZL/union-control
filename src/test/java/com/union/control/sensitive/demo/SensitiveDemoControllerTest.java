package com.union.control.sensitive.demo;

import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class SensitiveDemoControllerTest {
    @Test
    public void maskedTokenListDisablesCaching() throws Exception {
        SensitiveDemoMapper mapper = new SensitiveDemoMapper() {
            public int insert(SensitiveDemoRecord record) { return 1; }
            public List<SensitiveDemoRecord> list(String userId) {
                SensitiveDemoRecord record = new SensitiveDemoRecord();
                record.setContent("[#masked#VIEW:rt_test]");
                return Collections.singletonList(record);
            }
        };
        MockMvc mvc = standaloneSetup(new SensitiveDemoController(new SensitiveDemoService(mapper))).build();

        mvc.perform(get("/api/sensitive/demo")
                .header(HttpHeaders.COOKIE, "CASSESSIONID=session-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }
}
