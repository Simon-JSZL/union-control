package com.epcc.arkweb.utils;
import org.junit.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
public class ResultMsgTest {
    @Test public void successAndFailureHaveDistinctPayloads() {
        ResultMsg ok=ResultMsg.ok("result"), fail=ResultMsg.fail("error");
        assertThat(ok.isSuccess()).isTrue(); assertThat(ok.getData()).isEqualTo("result"); assertThat(ok.getMsg()).isEqualTo("操作成功");
        assertThat(fail.isSuccess()).isFalse(); assertThat(fail.getData()).isNull(); assertThat(fail.getMsg()).isEqualTo("error");
    }

    @Test public void serializesProductionFieldNamesAndDefaultCodes() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(new ResultMsg().getCode()).isZero();
        ResultMsg failure = ResultMsg.fail("failed");
        assertThat(failure.setCode(409)).isSameAs(failure);
        assertThat(failure.getCode()).isEqualTo(409);
        assertThat(failure.isSuccess()).isFalse();
        assertThat(json.readTree(json.writeValueAsString(ResultMsg.ok("result"))))
                .isEqualTo(json.readTree("{\"success\":true,\"msg\":\"操作成功\",\"code\":0,\"data\":\"result\"}"));
        assertThat(json.readTree(json.writeValueAsString(ResultMsg.fail("failed"))))
                .isEqualTo(json.readTree("{\"success\":false,\"msg\":\"failed\",\"code\":1,\"data\":null}"));
    }
}
