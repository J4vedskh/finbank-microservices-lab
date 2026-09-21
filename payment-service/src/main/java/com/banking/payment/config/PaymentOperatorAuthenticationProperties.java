package com.banking.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.recovery.authentication")
public record PaymentOperatorAuthenticationProperties(
        @DefaultValue("BASIC") Mode mode,
        @DefaultValue ExternalJwt externalJwt
) {
    public enum Mode {
        BASIC,
        JWT
    }

    public record ExternalJwt(
            @DefaultValue("") String issuerUri,
            @DefaultValue("") String jwkSetUri,
            @DefaultValue("") String audience
    ) {
    }
}
