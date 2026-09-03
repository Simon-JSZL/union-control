package com.union.control.schedule;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ScheduledExecutionTokenTest {
    @Test
    public void issuesAHashableRedactedCapability() {
        ScheduledExecutionToken token = ScheduledExecutionToken.issue();

        assertThat(token.authorizationHeader()).startsWith("Scheduled ");
        assertThat(token.hash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ScheduledExecutionToken.hashSubmitted(
                token.authorizationHeader().substring("Scheduled ".length())))
                .isEqualTo(token.hash());
        assertThat(token.toString()).doesNotContain(
                token.authorizationHeader().substring("Scheduled ".length()));
    }

    @Test
    public void rejectsMalformedSubmittedTokens() {
        assertThatThrownBy(() -> ScheduledExecutionToken.hashSubmitted("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
