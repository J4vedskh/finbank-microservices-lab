package com.banking.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOperatorAuthenticationPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AuthenticationPropertiesConfiguration.class);

    @Test
    void defaultsToBasicWithNoExternalJwtSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PaymentOperatorAuthenticationProperties properties = context
                    .getBean(PaymentOperatorAuthenticationProperties.class);
            assertThat(properties.mode())
                    .isEqualTo(PaymentOperatorAuthenticationProperties.Mode.BASIC);
            assertThat(properties.externalJwt().issuerUri()).isEmpty();
            assertThat(properties.externalJwt().jwkSetUri()).isEmpty();
            assertThat(properties.externalJwt().audience()).isEmpty();
        });
    }

    @Test
    void jwtModeAndNestedSettingsBind() {
        contextRunner.withPropertyValues(
                "payment.recovery.authentication.mode=jwt",
                "payment.recovery.authentication.external-jwt.issuer-uri=https://identity.example/issuer",
                "payment.recovery.authentication.external-jwt.jwk-set-uri=https://identity.example/jwks",
                "payment.recovery.authentication.external-jwt.audience=finbank-operations"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PaymentOperatorAuthenticationProperties properties = context
                    .getBean(PaymentOperatorAuthenticationProperties.class);
            assertThat(properties.mode())
                    .isEqualTo(PaymentOperatorAuthenticationProperties.Mode.JWT);
            assertThat(properties.externalJwt().issuerUri())
                    .isEqualTo("https://identity.example/issuer");
            assertThat(properties.externalJwt().jwkSetUri())
                    .isEqualTo("https://identity.example/jwks");
            assertThat(properties.externalJwt().audience())
                    .isEqualTo("finbank-operations");
        });
    }

    @Test
    void unsupportedModeFailsBinding() {
        contextRunner.withPropertyValues(
                "payment.recovery.authentication.mode=both"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentOperatorAuthenticationProperties.class)
    static class AuthenticationPropertiesConfiguration {
    }
}
