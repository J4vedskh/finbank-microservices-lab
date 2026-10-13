package com.banking.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-observability;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.bootstrap-servers=localhost:65535",
        "spring.kafka.producer.properties." + ProducerConfig.MAX_BLOCK_MS_CONFIG + "=100",
        "spring.jpa.show-sql=false",
        "payment.outbox.poll-delay-ms=3600000",
        "management.health.diskspace.enabled=false"
})
@AutoConfigureObservability
@AutoConfigureMockMvc
class PrometheusEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesPrometheusMetricsWithoutOperatorCredentials() throws Exception {
        mockMvc.perform(get("/payments"))
                .andExpect(status().isOk());

        String prometheus = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("# HELP")))
                .andExpect(content().string(containsString("http_server_requests_seconds_count")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("jvm_threads_live_threads")))
                .andExpect(content().string(containsString("process_uptime_seconds")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertMetricSample(prometheus, "http_server_requests_seconds_count",
                "method=\"GET\"", "outcome=\"SUCCESS\"", "status=\"200\"", "uri=\"/payments\"");
        assertMetricSample(prometheus, "jvm_memory_used_bytes", "area=\"heap\"");
    }

    @Test
    void keepsTheLegacyMetricsPathUnavailable() throws Exception {
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isNotFound());
    }

    private void assertMetricSample(String exposition, String metric, String... requiredLabels) {
        assertThat(exposition.lines()
                        .filter(line -> line.startsWith(metric + "{"))
                        .toList())
                .anyMatch(line -> Arrays.stream(requiredLabels).allMatch(line::contains));
    }
}
