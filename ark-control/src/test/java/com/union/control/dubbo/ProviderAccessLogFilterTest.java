package com.union.control.dubbo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.union.control.service.ConversationService;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProviderAccessLogFilterTest {

    @SuppressWarnings("unchecked")
    @Test
    public void logsOnlyTheRpcBoundaryAndDelegatesTheCall() {
        Invoker<ConversationService> invoker = mock(Invoker.class);
        Invocation invocation = mock(Invocation.class);
        Result result = mock(Result.class);
        when(invoker.getInterface()).thenReturn(ConversationService.class);
        when(invocation.getMethodName()).thenReturn("conversations");
        when(invoker.invoke(invocation)).thenReturn(result);

        Logger logger = (Logger) LoggerFactory.getLogger(ProviderAccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(new ProviderAccessLogFilter().invoke(invoker, invocation)).isSameAs(result);
        } finally {
            logger.detachAppender(appender);
        }

        List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        assertThat(messages.toString())
                .contains("request started: service=ConversationService, method=conversations")
                .contains("request completed: service=ConversationService, method=conversations");
        verify(invoker).invoke(invocation);
        verify(invocation, never()).getArguments();
    }
}
