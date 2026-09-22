package com.banking.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOperatorTransportPolicyTest {
    @Test
    void directModeDoesNotTrustForwardedScheme() {
        PaymentOperatorTransportPolicy policy = policy(
                PaymentOperatorTransportProperties.Mode.DIRECT,
                List.of()
        );
        MockHttpServletRequest request = request("10.20.30.40", "https");

        assertThat(policy.isTrustedProxyHttps(request)).isFalse();
    }

    @Test
    void trustedProxyRequiresExactPeerAndSingleHttpsHeader() {
        PaymentOperatorTransportPolicy policy = policy(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of("10.20.30.40", "2001:db8::40")
        );

        assertThat(policy.isTrustedProxyHttps(request("10.20.30.40", "https")))
                .isTrue();
        assertThat(policy.isTrustedProxyHttps(request("2001:0db8:0:0:0:0:0:40", "HTTPS")))
                .isTrue();
        assertThat(policy.isTrustedProxyHttps(request("10.20.30.41", "https")))
                .isFalse();
        assertThat(policy.isTrustedProxyHttps(request("::ffff:10.20.30.40", "https")))
                .isFalse();
    }

    @Test
    void ambiguousOrConflictingForwardedHeadersFailClosed() {
        PaymentOperatorTransportPolicy policy = policy(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of("10.20.30.40")
        );

        assertThat(policy.isTrustedProxyHttps(request("10.20.30.40", null)))
                .isFalse();
        assertThat(policy.isTrustedProxyHttps(request("10.20.30.40", "http")))
                .isFalse();
        assertThat(policy.isTrustedProxyHttps(request("10.20.30.40", "https, http")))
                .isFalse();

        MockHttpServletRequest repeated = request("10.20.30.40", "https");
        repeated.addHeader("X-Forwarded-Proto", "https");
        assertThat(policy.isTrustedProxyHttps(repeated)).isFalse();

        MockHttpServletRequest conflicting = request("10.20.30.40", "https");
        conflicting.addHeader("Forwarded", "proto=http");
        assertThat(policy.isTrustedProxyHttps(conflicting)).isFalse();
    }

    @Test
    void policyRejectsMissingInvalidOrDuplicateProxyAddresses() {
        assertInvalid(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of(),
                "requires at least one"
        );
        assertInvalid(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of("proxy.internal.example"),
                "numeric IPv4 or IPv6"
        );
        assertInvalid(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of("10.20.30.0/24"),
                "numeric IPv4 or IPv6"
        );
        assertInvalid(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of("10.20.30.\u0664\u0660"),
                "numeric IPv4 or IPv6"
        );
        assertInvalid(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of("2001:db8::40", "2001:0db8:0:0:0:0:0:40"),
                "must not contain duplicates"
        );
        assertInvalid(
                PaymentOperatorTransportProperties.Mode.DIRECT,
                List.of("10.20.30.40"),
                "must be empty in direct mode"
        );
    }

    @Test
    void activeOperatorAcceptsOnlyAnExplicitSecureTransport() {
        PaymentOperatorTransportPolicy direct = policy(
                PaymentOperatorTransportProperties.Mode.DIRECT,
                List.of()
        );
        PaymentOperatorTransportPolicy proxy = policy(
                PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                List.of("10.20.30.40")
        );

        direct.requireActiveOperatorTransport(true, "none");
        proxy.requireActiveOperatorTransport(false, "none");

        assertThatThrownBy(() -> direct.requireActiveOperatorTransport(false, "none"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Direct operator transport requires server SSL to be enabled");
        assertThatThrownBy(() -> proxy.requireActiveOperatorTransport(false, "framework"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Operator authentication requires server.forward-headers-strategy=none"
                );
    }

    private PaymentOperatorTransportPolicy policy(
            PaymentOperatorTransportProperties.Mode mode,
            List<String> addresses
    ) {
        return new PaymentOperatorTransportPolicy(
                new PaymentOperatorTransportProperties(mode, addresses)
        );
    }

    private MockHttpServletRequest request(String remoteAddress, String proto) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/internal/payment-outbox/dead-letter-handoffs"
        );
        request.setRemoteAddr(remoteAddress);
        request.setSecure(false);
        if (proto != null) {
            request.addHeader("X-Forwarded-Proto", proto);
        }
        return request;
    }

    private void assertInvalid(
            PaymentOperatorTransportProperties.Mode mode,
            List<String> addresses,
            String messageFragment
    ) {
        assertThatThrownBy(() -> policy(mode, addresses))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messageFragment);
    }
}
