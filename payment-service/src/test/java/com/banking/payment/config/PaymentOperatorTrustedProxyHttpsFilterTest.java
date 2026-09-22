package com.banking.payment.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOperatorTrustedProxyHttpsFilterTest {
    private final PaymentOperatorTrustedProxyHttpsFilter filter =
            new PaymentOperatorTrustedProxyHttpsFilter(
                    new PaymentOperatorTransportPolicy(
                            new PaymentOperatorTransportProperties(
                                    PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY,
                                    List.of("10.20.30.40")
                            )
                    )
            );

    @Test
    void trustedOperatorRequestIsPresentedAsSecureToDownstreamSecurity() throws Exception {
        MockHttpServletRequest request = request(
                "/internal/payment-outbox/dead-letter-handoffs",
                "10.20.30.40",
                "https"
        );
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest downstream = (HttpServletRequest) chain.getRequest();
        assertThat(downstream.isSecure()).isTrue();
        assertThat(downstream.getScheme()).isEqualTo("https");
        assertThat(downstream.getServerPort()).isEqualTo(443);
    }

    @Test
    void untrustedOrNonOperatorRequestIsNotRewritten() throws Exception {
        MockHttpServletRequest untrusted = request(
                "/internal/payment-outbox/dead-letter-handoffs",
                "10.20.30.41",
                "https"
        );
        MockFilterChain untrustedChain = new MockFilterChain();
        filter.doFilter(untrusted, new MockHttpServletResponse(), untrustedChain);

        assertThat(((HttpServletRequest) untrustedChain.getRequest()).isSecure()).isFalse();

        MockHttpServletRequest publicRequest = request(
                "/payments",
                "10.20.30.40",
                "https"
        );
        MockFilterChain publicChain = new MockFilterChain();
        filter.doFilter(publicRequest, new MockHttpServletResponse(), publicChain);

        assertThat(publicChain.getRequest()).isSameAs(publicRequest);
        assertThat(((HttpServletRequest) publicChain.getRequest()).isSecure()).isFalse();
    }

    private MockHttpServletRequest request(
            String path,
            String remoteAddress,
            String proto
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setRemoteAddr(remoteAddress);
        request.setSecure(false);
        request.addHeader("X-Forwarded-Proto", proto);
        return request;
    }
}
