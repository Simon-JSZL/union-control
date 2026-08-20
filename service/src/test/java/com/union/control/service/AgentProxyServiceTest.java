package com.union.control.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

public class AgentProxyServiceTest {
    @Test
    public void logsModelCallMetadataWithoutCredentialsOrPayload() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://py-app/agent/v1/runs/sync"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"content\":\"model-secret\"}", MediaType.APPLICATION_JSON));
        ListAppender<ILoggingEvent> logs = logs();
        byte[] payload = "{\"prompt\":\"plaintext-secret\"}".getBytes(StandardCharsets.UTF_8);

        new AgentProxyService("http://py-app", http).sync("CASSESSIONID=cookie-secret", payload);

        String messages = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(messages).contains("Agent call started mode=sync payload_bytes=" + payload.length)
                .contains("Agent call completed mode=sync status=200 duration_ms=")
                .doesNotContain("cookie-secret")
                .doesNotContain("plaintext-secret")
                .doesNotContain("model-secret");
        server.verify();
    }

    @Test
    public void cancellationPassesThroughPyAppResponse() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://py-app/agent/v1/runs/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"already finished\"}"));

        ResponseEntity<byte[]> response = new AgentProxyService("http://py-app", http)
                .cancel("CASSESSIONID=session", "thread-1", "run-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"detail\":\"already finished\"}");
        server.verify();
    }

    private static ListAppender<ILoggingEvent> logs() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentProxyService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
