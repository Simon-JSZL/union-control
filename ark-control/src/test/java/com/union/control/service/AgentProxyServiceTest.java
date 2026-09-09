package com.union.control.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import com.union.control.service.impl.AgentProxyServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

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
                .andRespond(withSuccess("{ \"content\" : \"model-secret\" }", MediaType.APPLICATION_JSON));
        ListAppender<ILoggingEvent> logs = logs();
        String payload = "{\"prompt\":\"plaintext-secret\"}";

        AgentResponse response = new AgentProxyServiceImpl("http://py-app", http, new ObjectMapper())
                .sync("CASSESSIONID=cookie-secret", payload);

        String messages = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(response.getBody()).isEqualTo("{\"content\":\"model-secret\"}");
        assertThat(messages).contains("Agent call started mode=sync payload_chars=" + payload.length())
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

        AgentResponse response = new AgentProxyServiceImpl("http://py-app", http, new ObjectMapper())
                .cancel("CASSESSIONID=session", "thread-1", "run-1");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getBody()).isEqualTo("{\"detail\":\"already finished\"}");
        server.verify();
    }

    @Test(timeout = 5000)
    public void allProxyModesStopWaitingForASilentUpstream() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { Thread.sleep(150); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            AgentProxyServiceImpl service = new AgentProxyServiceImpl(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper(), 0.05);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sync("session", "{}"))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                    .hasCauseInstanceOf(java.net.SocketTimeoutException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.scheduled("Scheduled token"))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                    .hasCauseInstanceOf(java.net.SocketTimeoutException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancel("session", "thread", "run"))
                    .isInstanceOf(org.springframework.web.client.ResourceAccessException.class)
                    .hasCauseInstanceOf(java.net.SocketTimeoutException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsUnboundedOrOverflowingTimeouts() {
        for (double seconds : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Integer.MAX_VALUE}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new AgentProxyServiceImpl("http://py-app", new ObjectMapper(), seconds))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static ListAppender<ILoggingEvent> logs() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentProxyServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
