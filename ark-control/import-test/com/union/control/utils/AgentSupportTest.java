package com.union.control.utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.union.control.utils.AgentSupport.*;
public class AgentSupportTest {
    @Test public void parsesRequestsAndRejectsInvalidObjects() {
        ObjectMapper json = new ObjectMapper();
        assertThat(request(json, "{\"userId\":\"owner\"}")).containsEntry("userId", "owner");
        for (String value : new String[]{null, "null", "[]", "bad"})
            assertThatThrownBy(() -> request(json, value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test public void validatesIdentitiesIdsTextAndNumbers() {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("userId", " owner ");
        assertThat(userId(data)).isEqualTo("owner");
        requireId("thread-1"); requireExecutionToken("run-1");
        for (String value : new String[]{null, "", "../bad", String.join("", Collections.nCopies(65, "x"))}) {
            assertThatThrownBy(() -> requireId(value)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> requireExecutionToken(value)).isInstanceOf(IllegalArgumentException.class);
        }
        for (Object value : new Object[]{null, "", "   ", 1, "too long"}) {
            data.put("field", value);
            assertThatThrownBy(() -> text(data, "field", 4, true)).isInstanceOf(IllegalArgumentException.class);
        }
        data.put("field", " ");
        assertThat(optionalText(data,"field",4)).isNull();
        assertThatThrownBy(() -> identity(data,"field")).isInstanceOf(IllegalArgumentException.class);
        data.put("field", 2);
        assertThat(integer(data,"field",1,3)).isEqualTo(2);
        for (Object value : new Object[]{null,"2",0,4}) {
            data.put("field",value);
            assertThatThrownBy(() -> integer(data,"field",1,3)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(number(9L)).isEqualTo(9L); assertThat(number("10")).isEqualTo(10L);
        assertThatThrownBy(() -> number("bad")).isInstanceOf(NumberFormatException.class);
    }
    @Test public void preservesEscapingEqualityAndResponseShapes() {
        assertThat(likePrefix("a\\%_")).isEqualTo("a\\\\\\%\\_%");
        assertThat(same(null,null)).isTrue(); assertThat(same(null,"a")).isFalse();
        assertThat(same("a",null)).isFalse(); assertThat(same("a","a")).isTrue();
        assertThat(same("a","b")).isFalse();
        assertThat(success()).containsEntry("success",true).hasSize(1);
        assertThat(ok("value")).containsEntry("success",true).containsEntry("data","value");
    }
}
