package com.banking.payment.controller;

import com.banking.payment.config.PaymentRecoverySecurityConfiguration;
import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.service.PaymentOutboxEventNotFoundException;
import com.banking.payment.service.PaymentOutboxRecoveryCommandConflictException;
import com.banking.payment.service.PaymentOutboxRecoveryNotAllowedException;
import com.banking.payment.service.PaymentOutboxRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentOutboxRecoveryController.class)
@Import(PaymentRecoverySecurityConfiguration.class)
class PaymentOutboxRecoveryControllerTest {
    private static final String USERNAME = "recovery-operator";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String PASSWORD_HASH =
            "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);
    private static final String COMMAND_KEY = "recovery-command-0002";
    private static final String REASON = "Kafka delivery was checked before requeue";
    private static final Instant REQUEUED_AT = Instant.parse("2026-09-11T05:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentOutboxRecoveryService recoveryService;

    @DynamicPropertySource
    static void operatorCredentials(DynamicPropertyRegistry registry) {
        registry.add("payment.recovery.operator.username", () -> USERNAME);
        registry.add("payment.recovery.operator.password-hash", () -> PASSWORD_HASH);
        registry.add("server.ssl.enabled", () -> true);
    }

    @Test
    void requeueExhausted_unauthenticatedRequestIsRejectedBeforeService() throws Exception {
        mockMvc.perform(validRequest())
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_insecureRequestRedirectsBeforeAuthenticationOrService() throws Exception {
        mockMvc.perform(validRequest().secure(false))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.startsWith("https://")
                ));

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_wrongPasswordIsRejectedBeforeService() throws Exception {
        mockMvc.perform(validRequest().with(httpBasic(USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_authenticatedUserWithoutRecoveryAuthorityIsForbidden() throws Exception {
        mockMvc.perform(validRequest().with(user("viewer")
                        .authorities(new SimpleGrantedAuthority("PAYMENT_READ"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_authorizedOperatorUsesPrincipalAsActorAndReturnsSafeReceipt()
            throws Exception {
        when(recoveryService.requeueExhausted(7L, COMMAND_KEY, USERNAME, REASON))
                .thenReturn(audit());

        mockMvc.perform(validRequest().with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.eventId").value(7))
                .andExpect(jsonPath("$.requeuedAt").value(REQUEUED_AT.toString()))
                .andExpect(jsonPath("$.actor").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.recoveryKeyHash").doesNotExist())
                .andExpect(jsonPath("$.outboxEvent").doesNotExist())
                .andExpect(jsonPath("$.previousLastError").doesNotExist());

        verify(recoveryService).requeueExhausted(7L, COMMAND_KEY, USERNAME, REASON);
    }

    @Test
    void requeueExhausted_clientActorFieldCannotOverrideAuthenticatedPrincipal() throws Exception {
        when(recoveryService.requeueExhausted(7L, COMMAND_KEY, USERNAME, REASON))
                .thenReturn(audit());

        mockMvc.perform(validRequest()
                        .with(httpBasic(USERNAME, PASSWORD))
                        .content("{\"reason\":\"" + REASON + "\",\"actor\":\"forged-actor\"}"))
                .andExpect(status().isOk());

        verify(recoveryService).requeueExhausted(7L, COMMAND_KEY, USERNAME, REASON);
    }

    @Test
    void requeueExhausted_missingKeyIsBadRequestWithoutServiceCall() throws Exception {
        mockMvc.perform(post("/internal/payment-outbox/7/recovery")
                        .secure(true)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_invalidKeyIsBadRequestWithoutServiceCall() throws Exception {
        mockMvc.perform(post("/internal/payment-outbox/7/recovery")
                        .secure(true)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .header("Idempotency-Key", "too-short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_oversizedKeyIsBadRequestWithoutServiceCall() throws Exception {
        mockMvc.perform(post("/internal/payment-outbox/7/recovery")
                        .secure(true)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .header("Idempotency-Key", "a".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_blankReasonIsBadRequestWithoutServiceCall() throws Exception {
        mockMvc.perform(post("/internal/payment-outbox/7/recovery")
                        .secure(true)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .header("Idempotency-Key", COMMAND_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_oversizedReasonIsBadRequestWithoutServiceCall() throws Exception {
        mockMvc.perform(post("/internal/payment-outbox/7/recovery")
                        .secure(true)
                        .with(httpBasic(USERNAME, PASSWORD))
                        .header("Idempotency-Key", COMMAND_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(recoveryService);
    }

    @Test
    void requeueExhausted_missingEventReturnsNotFoundProblem() throws Exception {
        when(recoveryService.requeueExhausted(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentOutboxEventNotFoundException(7L));

        mockMvc.perform(validRequest().with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Outbox event not found"))
                .andExpect(jsonPath("$.detail").value("Payment outbox event 7 was not found"));
    }

    @Test
    void requeueExhausted_ineligibleEventReturnsConflictProblem() throws Exception {
        when(recoveryService.requeueExhausted(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentOutboxRecoveryNotAllowedException(7L));

        mockMvc.perform(validRequest().with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recovery not allowed"));
    }

    @Test
    void requeueExhausted_commandConflictReturnsSafeConflictProblem() throws Exception {
        when(recoveryService.requeueExhausted(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new PaymentOutboxRecoveryCommandConflictException());

        mockMvc.perform(validRequest().with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recovery command conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Recovery command key is already assigned to another request"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(COMMAND_KEY)
                )));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
        return post("/internal/payment-outbox/7/recovery")
                .secure(true)
                .header("Idempotency-Key", COMMAND_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody());
    }

    private String validBody() {
        return "{\"reason\":\"" + REASON + "\"}";
    }

    private PaymentOutboxRecoveryAudit audit() {
        Payment payment = new Payment();
        payment.setId(42L);
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("PENDING_RETRY");
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                payment,
                "payments",
                "42",
                "42|1|2|750.00",
                REQUEUED_AT.minusSeconds(30)
        );
        return new PaymentOutboxRecoveryAudit(
                event,
                "a".repeat(64),
                USERNAME,
                REASON,
                REQUEUED_AT,
                REQUEUED_AT.minusSeconds(1),
                5,
                "TimeoutException"
        );
    }
}
