package com.epcc.arkweb.mock;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class LocalArkAuthServiceMockTest {
    @Test
    public void grantsOnlyTheConfiguredRole() {
        ArkAuthServiceMock mock = new ArkAuthServiceMock(
                "user-1", "local-only", "104100000004", "role-1");

        assertThat(mock.queryResource("role-1", "user-1", "trace").getResult())
                .extracting("resourceUrl").containsExactly("/assistantManager/page");
        assertThat(mock.queryResource("role-2", "user-1", "trace").getResult()).isEmpty();
    }
}
