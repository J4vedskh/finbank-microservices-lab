package com.banking.transaction.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionKafkaConfigurationTest {

    @Test
    void paymentConsumerUsesSharedBrokerAndReplaysFromEarliestOffset() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "transaction-service",
                new ClassPathResource("application.yml")
        );

        assertThat(property(sources, "spring.kafka.bootstrap-servers"))
                .isEqualTo("kafka:9092");
        assertThat(property(sources, "spring.kafka.consumer.group-id"))
                .isEqualTo("transaction-group");
        assertThat(property(sources, "spring.kafka.consumer.auto-offset-reset"))
                .isEqualTo("earliest");
        assertThat(property(sources, "management.kafka.bootstrap-servers"))
                .isNull();
    }

    private Object property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
