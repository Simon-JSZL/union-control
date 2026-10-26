package com.epcc.arkweb.vo.llm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.assertj.core.api.Assertions.*;
public class ScheduledTaskCommandVOTest {
    @Test public void browserEditableFieldsRoundTripWithoutIdentity() throws Exception {
        ObjectMapper json=new ObjectMapper();
        String input="{\"taskId\":7,\"title\":\"title\",\"prompt\":\"prompt\",\"scheduleType\":\"INTERVAL\",\"runAt\":\"2099-01-01T00:00:00Z\",\"cronExpression\":null,\"intervalSeconds\":60,\"timezone\":\"UTC\"}";
        ScheduledTaskCommandVO value=json.readValue(input,ScheduledTaskCommandVO.class);
        assertThat(json.readTree(json.writeValueAsString(value))).isEqualTo(json.readTree(input));
        assertThat(json.valueToTree(value).has("userId")).isFalse();
    }
}
