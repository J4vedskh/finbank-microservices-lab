package com.banking.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxRetentionPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetentionConfiguration.class);

    @Test
    void defaultsAreSafeAndRetentionIsOptIn() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PaymentOutboxRetentionProperties properties =
                    context.getBean(PaymentOutboxRetentionProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.publishedRetentionDays()).isEqualTo(30);
            assertThat(properties.rejectionRetentionDays()).isEqualTo(30);
            assertThat(properties.batchSize()).isEqualTo(100);
            assertThat(properties.cleanupDelayMs()).isEqualTo(86_400_000L);
        });
    }

    @Test
    void explicitValidPolicyBinds() {
        contextRunner.withPropertyValues(
                "payment.outbox.retention.enabled=true",
                "payment.outbox.retention.published-retention-days=90",
                "payment.outbox.retention.rejection-retention-days=14",
                "payment.outbox.retention.batch-size=250",
                "payment.outbox.retention.cleanup-delay-ms=60000"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            PaymentOutboxRetentionProperties properties =
                    context.getBean(PaymentOutboxRetentionProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.publishedRetentionDays()).isEqualTo(90);
            assertThat(properties.rejectionRetentionDays()).isEqualTo(14);
            assertThat(properties.batchSize()).isEqualTo(250);
            assertThat(properties.cleanupDelayMs()).isEqualTo(60_000L);
        });
    }

    @Test
    void zeroPublishedRetentionFailsStartup() {
        assertInvalid("payment.outbox.retention.published-retention-days=0", "publishedRetentionDays");
    }

    @Test
    void zeroRejectionRetentionFailsStartup() {
        assertInvalid("payment.outbox.retention.rejection-retention-days=0", "rejectionRetentionDays");
    }

    @Test
    void oversizedBatchFailsStartup() {
        assertInvalid("payment.outbox.retention.batch-size=1001", "batchSize");
    }

    @Test
    void zeroBatchFailsStartup() {
        assertInvalid("payment.outbox.retention.batch-size=0", "batchSize");
    }

    @Test
    void subSecondCleanupDelayFailsStartup() {
        assertInvalid("payment.outbox.retention.cleanup-delay-ms=999", "cleanupDelayMs");
    }

    @ParameterizedTest
    @CsvSource({
            "published-retention-days=-1, publishedRetentionDays",
            "rejection-retention-days=-1, rejectionRetentionDays",
            "batch-size=-1, batchSize",
            "cleanup-delay-ms=-1, cleanupDelayMs"
    })
    void negativePolicyValuesFailStartup(String property, String fieldName) {
        assertInvalid("payment.outbox.retention." + property, fieldName);
    }

    @ParameterizedTest
    @CsvSource({
            "published-retention-days=36501, publishedRetentionDays",
            "rejection-retention-days=36501, rejectionRetentionDays"
    })
    void oversizedRetentionPeriodsFailStartup(String property, String fieldName) {
        assertInvalid("payment.outbox.retention." + property, fieldName);
    }

    private void assertInvalid(String property, String fieldName) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()).getMessage())
                    .contains(fieldName);
        });
    }

    private Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentOutboxRetentionProperties.class)
    static class RetentionConfiguration {
    }
}
