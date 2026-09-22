package com.banking.payment.config;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class PaymentOperatorTransportPolicy {
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private final PaymentOperatorTransportProperties.Mode mode;
    private final Set<String> trustedProxyAddresses;

    PaymentOperatorTransportPolicy(PaymentOperatorTransportProperties properties) {
        mode = properties.mode();
        List<String> configuredAddresses = properties.trustedProxyAddresses();
        if (mode == PaymentOperatorTransportProperties.Mode.DIRECT) {
            if (configuredAddresses.stream().anyMatch(this::hasText)) {
                throw new IllegalStateException(
                        "Trusted proxy addresses must be empty in direct mode"
                );
            }
            trustedProxyAddresses = Set.of();
            return;
        }

        if (configuredAddresses.isEmpty()
                || configuredAddresses.stream().noneMatch(this::hasText)) {
            throw new IllegalStateException(
                    "Trusted proxy mode requires at least one proxy address"
            );
        }

        LinkedHashSet<String> normalizedAddresses = new LinkedHashSet<>();
        for (String configuredAddress : configuredAddresses) {
            if (!hasText(configuredAddress)) {
                throw new IllegalStateException(
                        "Trusted proxy addresses must not contain blank entries"
                );
            }
            String normalized = normalizeAddress(configuredAddress);
            if (!normalizedAddresses.add(normalized)) {
                throw new IllegalStateException(
                        "Trusted proxy addresses must not contain duplicates"
                );
            }
        }
        trustedProxyAddresses = Set.copyOf(normalizedAddresses);
    }

    boolean isTrustedProxyHttps(HttpServletRequest request) {
        if (mode != PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY
                || request.isSecure()) {
            return false;
        }

        String remoteAddress;
        try {
            remoteAddress = normalizeAddress(request.getRemoteAddr());
        } catch (IllegalStateException invalidRemoteAddress) {
            return false;
        }
        if (!trustedProxyAddresses.contains(remoteAddress)
                || hasHeader(request, "Forwarded")) {
            return false;
        }

        Enumeration<String> protoHeaders = request.getHeaders("X-Forwarded-Proto");
        if (protoHeaders == null || !protoHeaders.hasMoreElements()) {
            return false;
        }
        String proto = protoHeaders.nextElement();
        return !protoHeaders.hasMoreElements()
                && proto != null
                && "https".equalsIgnoreCase(proto.strip());
    }

    private boolean hasHeader(HttpServletRequest request, String name) {
        Enumeration<String> headers = request.getHeaders(name);
        return headers != null && headers.hasMoreElements();
    }

    void requireActiveOperatorTransport(
            boolean serverSslEnabled,
            String forwardHeadersStrategy
    ) {
        if (forwardHeadersStrategy == null
                || !"none".equalsIgnoreCase(forwardHeadersStrategy.strip())) {
            throw new IllegalStateException(
                    "Operator authentication requires server.forward-headers-strategy=none"
            );
        }
        if (mode == PaymentOperatorTransportProperties.Mode.DIRECT
                && !serverSslEnabled) {
            throw new IllegalStateException(
                    "Direct operator transport requires server SSL to be enabled"
            );
        }
    }

    private String normalizeAddress(String value) {
        String address = value == null ? "" : value.strip();
        byte[] bytes;
        if (address.indexOf(':') >= 0) {
            if (!IPV6_LITERAL.matcher(address).matches()
                    || address.indexOf('%') >= 0
                    || address.indexOf('/') >= 0) {
                throw invalidAddress();
            }
            try {
                InetAddress parsed = InetAddress.getByName(address);
                if (!(parsed instanceof Inet6Address)) {
                    throw invalidAddress();
                }
                bytes = parsed.getAddress();
            } catch (UnknownHostException failure) {
                throw invalidAddress(failure);
            }
            return "6:" + HexFormat.of().formatHex(bytes);
        }

        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) {
            throw invalidAddress();
        }
        bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()
                    || part.length() > 3
                    || (part.length() > 1 && part.charAt(0) == '0')
                    || !part.chars().allMatch(character ->
                            character >= '0' && character <= '9')) {
                throw invalidAddress();
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                throw invalidAddress();
            }
            bytes[index] = (byte) octet;
        }
        return "4:" + HexFormat.of().formatHex(bytes);
    }

    private IllegalStateException invalidAddress() {
        return new IllegalStateException(
                "Trusted proxy addresses must be exact numeric IPv4 or IPv6 literals"
        );
    }

    private IllegalStateException invalidAddress(Exception cause) {
        return new IllegalStateException(
                "Trusted proxy addresses must be exact numeric IPv4 or IPv6 literals",
                cause
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
