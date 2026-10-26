package com.epcc.arkweb.vo.llm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.assertj.core.api.Assertions.*;
public class ScheduledTaskQueryVOTest {
    @Test public void defaultsAndQueryFieldsRoundTrip() throws Exception {
        ObjectMapper json=new ObjectMapper(); ScheduledTaskQueryVO initial=new ScheduledTaskQueryVO();
        assertThat(initial.getPage()).isEqualTo(1); assertThat(initial.getPageSize()).isEqualTo(20);
        String input="{\"taskId\":7,\"keyword\":\"title\",\"status\":\"ACTIVE\",\"page\":2,\"pageSize\":50}";
        assertThat(json.readTree(json.writeValueAsString(json.readValue(input,ScheduledTaskQueryVO.class)))).isEqualTo(json.readTree(input));
    }
}
