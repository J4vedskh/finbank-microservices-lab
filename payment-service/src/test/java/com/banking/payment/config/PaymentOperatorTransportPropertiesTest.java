package com.banking.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOperatorTransportPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TransportPropertiesConfiguration.class);

    @Test
    void defaultsToDirectTlsWithNoTrustedProxyAddresses() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PaymentOperatorTransportProperties properties = context
                    .getBean(PaymentOperatorTransportProperties.class);

            assertThat(properties.mode())
                    .isEqualTo(PaymentOperatorTransportProperties.Mode.DIRECT);
            assertThat(properties.trustedProxyAddresses()).isEmpty();
        });
    }

    @Test
    void trustedProxyModeAndAddressesBind() {
        contextRunner.withPropertyValues(
                "payment.recovery.transport.mode=trusted-proxy",
                "payment.recovery.transport.trusted-proxy-addresses="
                        + "10.20.30.40,2001:db8::40"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PaymentOperatorTransportProperties properties = context
                    .getBean(PaymentOperatorTransportProperties.class);

            assertThat(properties.mode())
                    .isEqualTo(PaymentOperatorTransportProperties.Mode.TRUSTED_PROXY);
            assertThat(properties.trustedProxyAddresses())
                    .containsExactly("10.20.30.40", "2001:db8::40");
        });
    }

    @Test
    void unsupportedModeFailsBinding() {
        contextRunner.withPropertyValues(
                "payment.recovery.transport.mode=trust-all-forwarded-headers"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentOperatorTransportProperties.class)
    static class TransportPropertiesConfiguration {
    }
}
