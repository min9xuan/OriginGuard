package com.originguard.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.originguard.agent.application.C2paVerificationClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class C2paVerificationClientTests {
    @Test
    void disabledClientReturnsExplicitNotConfiguredResultWithoutSpringJsonBean() {
        C2paVerificationClient client = new C2paVerificationClient(
                false, "http://127.0.0.1:8091", Duration.ofSeconds(1));

        assertThat(client.verify(new byte[] {1, 2, 3}, "sample.png", "image/png"))
                .containsEntry("status", "NOT_CONFIGURED")
                .containsEntry("credentialPresent", false);
    }
}
