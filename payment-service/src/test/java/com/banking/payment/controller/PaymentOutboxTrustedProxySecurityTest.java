package com.banking.payment.controller;

import com.banking.payment.api.PaymentOutboxDeadLetterHandoffPage;
import com.banking.payment.config.PaymentRecoverySecurityConfiguration;
import com.banking.payment.entity.Payment;
import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.service.PaymentOutboxDeadLetterHandoffInspectionService;
import com.banking.payment.service.PaymentOutboxRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        PaymentOutboxRecoveryController.class,
        PaymentOutboxDeadLetterHandoffInspectionController.class
})
@Import(PaymentRecoverySecurityConfiguration.class)
class PaymentOutboxTrustedProxySecurityTest {
    private static final String TRUSTED_PROXY = "10.20.30.40";
    private static final String UNTRUSTED_PEER = "10.20.30.41";
    private static final String ISSUER = "https://identity.example.test/issuer";
    private static final String AUDIENCE = "finbank-payment-operations";
    private static final String SUBJECT = "proxy-operator";
    private static final String COMMAND_KEY = "proxy-recovery-0001";
    private static final String REASON = "Kafka delivery was checked before requeue";
    private static final String RECOVERY_PATH =
            "/internal/payment-outbox/7/recovery";
    private static final String INSPECTION_PATH =
            "/internal/payment-outbox/dead-letter-handoffs";
    private static final Instant REQUEUED_AT = Instant.parse("2026-09-22T05:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "paymentOperatorJwtDecoder")
    private JwtDecoder jwtDecoder;

    @MockBean
    private PaymentOutboxRecoveryService recoveryService;

    @MockBean
    private PaymentOutboxDeadLetterHandoffInspectionService inspectionService;

    @DynamicPropertySource
    static void trustedProxyConfiguration(DynamicPropertyRegistry registry) {
        registry.add("payment.recovery.authentication.mode", () -> "jwt");
        registry.add("payment.recovery.authentication.external-jwt.issuer-uri",
                () -> ISSUER);
        registry.add("payment.recovery.authentication.external-jwt.jwk-set-uri",
                () -> "https://identity.example.test/.well-known/jwks.json");
        registry.add("payment.recovery.authentication.external-jwt.audience",
                () -> AUDIENCE);
        registry.add("payment.recovery.transport.mode", () -> "trusted-proxy");
        registry.add("payment.recovery.transport.trusted-proxy-addresses[0]",
                () -> TRUSTED_PROXY);
        registry.add("server.ssl.enabled", () -> false);
        registry.add("server.forward-headers-strategy", () -> "none");
    }

    @Test
    void trustedProxyHttpsHeaderAllowsInspectionAfterJwtAuthorization() throws Exception {
        when(jwtDecoder.decode("inspection-token")).thenReturn(jwt(
                "inspection-token",
                PaymentRecoverySecurityConfiguration.HANDOFF_INSPECTION_SCOPE
        ));
        when(inspectionService.list(
                null,
                PaymentOutboxDeadLetterHandoffInspectionService.DEFAULT_LIMIT
        )).thenReturn(new PaymentOutboxDeadLetterHandoffPage(List.of(), null));

        mockMvc.perform(trustedProxy(get(INSPECTION_PATH))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer inspection-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoffs").isEmpty());

        verify(inspectionService).list(
                null,
                PaymentOutboxDeadLetterHandoffInspectionService.DEFAULT_LIMIT
        );
        verifyNoInteractions(recoveryService);
    }

    @Test
    void trustedProxyHttpsHeaderAllowsRecoveryAndPreservesJwtActor() throws Exception {
        when(jwtDecoder.decode("recovery-token")).thenReturn(jwt(
                "recovery-token",
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        ));
        when(recoveryService.requeueExhausted(7L, COMMAND_KEY, SUBJECT, REASON))
                .thenReturn(audit());

        mockMvc.perform(trustedProxy(post(RECOVERY_PATH))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer recovery-token")
                        .header("Idempotency-Key", COMMAND_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(7));

        verify(recoveryService).requeueExhausted(7L, COMMAND_KEY, SUBJECT, REASON);
        verifyNoInteractions(inspectionService);
    }

    @Test
    void sameHeaderFromUntrustedPeerRedirectsBeforeJwtOrServices() throws Exception {
        mockMvc.perform(untrustedProxy(get(INSPECTION_PATH))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer inspection-token"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(untrustedProxy(post(RECOVERY_PATH))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer recovery-token")
                        .header("Idempotency-Key", COMMAND_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().is3xxRedirection());

        verifyNoInteractions(jwtDecoder, inspectionService, recoveryService);
    }

    @Test
    void malformedOrConflictingHeadersRedirectBeforeJwtOrServices() throws Exception {
        mockMvc.perform(fromTrustedPeer(get(INSPECTION_PATH)))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(fromTrustedPeer(get(INSPECTION_PATH))
                        .header("Forwarded", "proto=https")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer inspection-token"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(fromTrustedPeer(get(INSPECTION_PATH))
                        .header("X-Forwarded-Proto", "http"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(fromTrustedPeer(get(INSPECTION_PATH))
                        .header("X-Forwarded-Proto", "https, http"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(fromTrustedPeer(get(INSPECTION_PATH))
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(fromTrustedPeer(get(INSPECTION_PATH))
                        .header("X-Forwarded-Proto", "https")
                        .header("Forwarded", "proto=http"))
                .andExpect(status().is3xxRedirection());

        verifyNoInteractions(jwtDecoder, inspectionService, recoveryService);
    }

    @Test
    void directHttpsStillWorksInTrustedProxyMode() throws Exception {
        when(jwtDecoder.decode("inspection-token")).thenReturn(jwt(
                "inspection-token",
                PaymentRecoverySecurityConfiguration.HANDOFF_INSPECTION_SCOPE
        ));
        when(inspectionService.list(
                null,
                PaymentOutboxDeadLetterHandoffInspectionService.DEFAULT_LIMIT
        )).thenReturn(new PaymentOutboxDeadLetterHandoffPage(List.of(), null));

        mockMvc.perform(get(INSPECTION_PATH)
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer inspection-token"))
                .andExpect(status().isOk());

        verify(inspectionService).list(
                null,
                PaymentOutboxDeadLetterHandoffInspectionService.DEFAULT_LIMIT
        );
    }

    private MockHttpServletRequestBuilder trustedProxy(
            MockHttpServletRequestBuilder request
    ) {
        return fromTrustedPeer(request).header("X-Forwarded-Proto", "https");
    }

    private MockHttpServletRequestBuilder untrustedProxy(
            MockHttpServletRequestBuilder request
    ) {
        return request.secure(false)
                .with(servletRequest -> {
                    servletRequest.setRemoteAddr(UNTRUSTED_PEER);
                    return servletRequest;
                })
                .header("X-Forwarded-Proto", "https");
    }

    private MockHttpServletRequestBuilder fromTrustedPeer(
            MockHttpServletRequestBuilder request
    ) {
        return request.secure(false).with(servletRequest -> {
            servletRequest.setRemoteAddr(TRUSTED_PROXY);
            return servletRequest;
        });
    }

    private Jwt jwt(String token, String scopes) {
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject(SUBJECT)
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", scopes)
                .build();
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
                SUBJECT,
                REASON,
                REQUEUED_AT,
                REQUEUED_AT.minusSeconds(1),
                5,
                "TimeoutException"
        );
    }
}
