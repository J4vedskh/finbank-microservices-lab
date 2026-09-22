package com.banking.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties("payment.recovery.transport")
public record PaymentOperatorTransportProperties(
        @DefaultValue("DIRECT") Mode mode,
        List<String> trustedProxyAddresses
) {
    public PaymentOperatorTransportProperties {
        if (mode == null) {
            mode = Mode.DIRECT;
        }
        trustedProxyAddresses = trustedProxyAddresses == null
                ? List.of()
                : List.copyOf(trustedProxyAddresses);
    }

    public enum Mode {
        DIRECT,
        TRUSTED_PROXY
    }
}
