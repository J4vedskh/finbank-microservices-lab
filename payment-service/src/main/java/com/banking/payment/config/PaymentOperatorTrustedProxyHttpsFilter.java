package com.banking.payment.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class PaymentOperatorTrustedProxyHttpsFilter extends OncePerRequestFilter {
    private static final RequestMatcher OPERATOR_ROUTES =
            new AntPathRequestMatcher("/internal/payment-outbox/**");

    private final PaymentOperatorTransportPolicy transportPolicy;

    PaymentOperatorTrustedProxyHttpsFilter(
            PaymentOperatorTransportPolicy transportPolicy
    ) {
        this.transportPolicy = transportPolicy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.isSecure() || !OPERATOR_ROUTES.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpServletRequest downstreamRequest = request;
        if (transportPolicy.isTrustedProxyHttps(request)) {
            downstreamRequest = new TrustedProxyHttpsRequest(request);
        }
        filterChain.doFilter(downstreamRequest, response);
    }

    private static final class TrustedProxyHttpsRequest
            extends HttpServletRequestWrapper {
        private TrustedProxyHttpsRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public boolean isSecure() {
            return true;
        }

        @Override
        public String getScheme() {
            return "https";
        }

        @Override
        public int getServerPort() {
            return 443;
        }
    }
}
