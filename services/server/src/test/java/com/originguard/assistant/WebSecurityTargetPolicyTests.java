package com.originguard.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.originguard.assistant.application.WebSecurityTargetPolicy;
import com.originguard.shared.application.BusinessConflictException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSecurityTargetPolicyTests {
    private final WebSecurityTargetPolicy policy = new WebSecurityTargetPolicy();

    @Test
    void acceptsAndNormalizesPublicHttpsTarget() {
        URI target = policy.requireTarget("帮我检查 HTTPS://Example.COM/login?next=home。谢谢");

        assertThat(target.toASCIIString()).isEqualTo("https://example.com/login?next=home");
    }

    @Test
    void rejectsEmbeddedCredentialsAndNonStandardPorts() {
        assertThatThrownBy(() -> policy.requireTarget("检查 https://user:password@example.com/login"))
                .isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> policy.requireTarget("检查 https://example.com:8443/login"))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void blocksPrivateAndReservedDnsResults() throws Exception {
        URI target = policy.requireTarget("检查 https://example.com");

        assertThatThrownBy(() -> policy.requirePublicAddresses(
                target, List.of(InetAddress.getByName("127.0.0.1"))))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("private, local, reserved");
        assertThatThrownBy(() -> policy.requirePublicAddresses(
                target, List.of(InetAddress.getByName("100.64.0.1"))))
                .isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> policy.requirePublicAddresses(
                target, List.of(InetAddress.getByName("169.254.169.254"))))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void allowsKnownPublicAddress() throws Exception {
        URI target = policy.requireTarget("检查 https://example.com");

        policy.requirePublicAddresses(target, List.of(InetAddress.getByName("93.184.216.34")));
    }
}
