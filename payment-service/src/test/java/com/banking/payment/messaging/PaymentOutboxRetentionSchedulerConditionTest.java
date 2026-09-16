package com.banking.payment.messaging;

import com.banking.payment.service.PaymentOutboxRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentOutboxRetentionSchedulerConditionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetentionSchedulerConfiguration.class);

    @Test
    void schedulerIsAbsentByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(PaymentOutboxRetentionScheduler.class));
    }

    @Test
    void schedulerIsAbsentWhenExplicitlyDisabled() {
        contextRunner.withPropertyValues("payment.outbox.retention.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PaymentOutboxRetentionScheduler.class));
    }

    @Test
    void schedulerIsPresentOnlyWhenExplicitlyEnabled() {
        contextRunner.withPropertyValues("payment.outbox.retention.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(PaymentOutboxRetentionScheduler.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PaymentOutboxRetentionScheduler.class)
    static class RetentionSchedulerConfiguration {
        @Bean
        PaymentOutboxRetentionService retentionService() {
            return mock(PaymentOutboxRetentionService.class);
        }
    }
}
