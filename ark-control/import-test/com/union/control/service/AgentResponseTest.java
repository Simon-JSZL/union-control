package com.union.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentResponseTest {
    @Test
    public void preservesTheSerializableHttpContractAndBeanProperties() throws Exception {
        AgentResponse original = new AgentResponse(502, "{\"error\":\"unavailable\"}");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream output = new java.io.ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (java.io.ObjectInputStream input = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            AgentResponse restored = (AgentResponse) input.readObject();
            assertThat(restored.getStatus()).isEqualTo(502);
            assertThat(restored.getBody()).isEqualTo(original.getBody());
        }

        ObjectMapper json = new ObjectMapper();
        AgentResponse bean = json.readValue("{\"status\":200,\"body\":\"{}\"}", AgentResponse.class);
        assertThat(bean.getStatus()).isEqualTo(200);
        assertThat(bean.getBody()).isEqualTo("{}");
    }
}
