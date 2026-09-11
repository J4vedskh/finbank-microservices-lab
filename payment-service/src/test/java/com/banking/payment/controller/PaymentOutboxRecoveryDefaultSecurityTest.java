package com.banking.payment.controller;

import com.banking.payment.config.PaymentRecoverySecurityConfiguration;
import com.banking.payment.service.PaymentOutboxRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentOutboxRecoveryController.class)
@Import(PaymentRecoverySecurityConfiguration.class)
class PaymentOutboxRecoveryDefaultSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentOutboxRecoveryService recoveryService;

    @Test
    void requeueExhausted_withoutConfiguredOperatorHasNoFallbackCredential() throws Exception {
        mockMvc.perform(post("/internal/payment-outbox/7/recovery")
                        .secure(true)
                        .with(httpBasic("admin", "admin"))
                        .header("Idempotency-Key", "recovery-command-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Kafka delivery was checked before requeue\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void unlistedEndpointIsDeniedEvenToRecoveryAuthority() throws Exception {
        mockMvc.perform(get("/actuator/env").with(user("recovery-operator")
                        .authorities(new SimpleGrantedAuthority(
                                PaymentRecoverySecurityConfiguration.RECOVERY_AUTHORITY
                        ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void legacyPrometheusPathRemainsOutsideTheAuthenticationBoundary() throws Exception {
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(recoveryService);
    }
}
