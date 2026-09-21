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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        PaymentOutboxRecoveryController.class,
        PaymentOutboxDeadLetterHandoffInspectionController.class
})
@Import(PaymentRecoverySecurityConfiguration.class)
class PaymentOutboxExternalJwtSecurityTest {
    private static final String ISSUER = "https://identity.example.test/issuer";
    private static final String AUDIENCE = "finbank-payment-operations";
    private static final String SUBJECT = "external-operator";
    private static final String COMMAND_KEY = "external-recovery-0001";
    private static final String REASON = "Kafka delivery was checked before requeue";
    private static final Instant REQUEUED_AT = Instant.parse("2026-09-21T05:30:00Z");
    private static final String RECOVERY_PATH =
            "/internal/payment-outbox/7/recovery";
    private static final String INSPECTION_PATH =
            "/internal/payment-outbox/dead-letter-handoffs";

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "paymentOperatorJwtDecoder")
    private JwtDecoder jwtDecoder;

    @MockBean(name = "genericJwtDecoder")
    private JwtDecoder genericJwtDecoder;

    @MockBean
    private PaymentOutboxRecoveryService recoveryService;

    @MockBean
    private PaymentOutboxDeadLetterHandoffInspectionService inspectionService;

    @DynamicPropertySource
    static void jwtConfiguration(DynamicPropertyRegistry registry) {
        registry.add("payment.recovery.authentication.mode", () -> "jwt");
        registry.add("payment.recovery.authentication.external-jwt.issuer-uri",
                () -> ISSUER);
        registry.add("payment.recovery.authentication.external-jwt.jwk-set-uri",
                () -> "https://identity.example.test/.well-known/jwks.json");
        registry.add("payment.recovery.authentication.external-jwt.audience",
                () -> AUDIENCE);
        registry.add("server.ssl.enabled", () -> true);
    }

    @Test
    void recoveryScope_usesJwtSubjectAsAuditActor() throws Exception {
        when(jwtDecoder.decode("recovery-token")).thenReturn(jwt(
                "recovery-token",
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        ));
        when(recoveryService.requeueExhausted(7L, COMMAND_KEY, SUBJECT, REASON))
                .thenReturn(audit());

        mockMvc.perform(validRecoveryRequest("recovery-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(7))
                .andExpect(jsonPath("$.requeuedAt").value(REQUEUED_AT.toString()));

        verify(recoveryService).requeueExhausted(
                7L,
                COMMAND_KEY,
                SUBJECT,
                REASON
        );
        verifyNoInteractions(inspectionService);
    }

    @Test
    void inspectionScope_canReadHandoffsButCannotRecover() throws Exception {
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoffs").isEmpty());
        mockMvc.perform(validRecoveryRequest("inspection-token"))
                .andExpect(status().isForbidden());

        verify(inspectionService).list(
                null,
                PaymentOutboxDeadLetterHandoffInspectionService.DEFAULT_LIMIT
        );
        verifyNoInteractions(recoveryService);
    }

    @Test
    void recoveryScope_cannotInspectHandoffs() throws Exception {
        when(jwtDecoder.decode("recovery-token")).thenReturn(jwt(
                "recovery-token",
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        ));

        mockMvc.perform(get(INSPECTION_PATH)
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer recovery-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(inspectionService, recoveryService);
    }

    @Test
    void unmappedScope_authenticatesButCannotUseOperatorRoutes() throws Exception {
        when(jwtDecoder.decode("unmapped-token")).thenReturn(jwt(
                "unmapped-token",
                "unrelated.scope"
        ));

        mockMvc.perform(get(INSPECTION_PATH)
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer unmapped-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(inspectionService, recoveryService);
    }

    @Test
    void invalidBearerToken_isUnauthorizedBeforeService() throws Exception {
        when(jwtDecoder.decode("invalid-token"))
                .thenThrow(new BadJwtException("signature detail must not be returned"));

        mockMvc.perform(get(INSPECTION_PATH)
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("signature detail")
                )));

        verifyNoInteractions(inspectionService, recoveryService);
        verifyNoInteractions(genericJwtDecoder);
    }

    @Test
    void competingGenericDecoderCannotBypassOperatorValidationPolicy() throws Exception {
        when(jwtDecoder.decode("bypass-token"))
                .thenThrow(new BadJwtException("operator policy rejected token"));
        when(genericJwtDecoder.decode("bypass-token")).thenReturn(Jwt
                .withTokenValue("bypass-token")
                .header("alg", "none")
                .issuer("https://wrong-issuer.example.test")
                .subject(SUBJECT)
                .audience(List.of("wrong-audience"))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE)
                .build());

        mockMvc.perform(validRecoveryRequest("bypass-token"))
                .andExpect(status().isUnauthorized());

        verify(genericJwtDecoder, never()).decode("bypass-token");
        verifyNoInteractions(inspectionService, recoveryService);
    }

    @Test
    void basicAuthentication_isUnavailableInJwtMode() throws Exception {
        mockMvc.perform(get(INSPECTION_PATH)
                        .secure(true)
                        .with(httpBasic("recovery-operator", "password")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(inspectionService, recoveryService);
    }

    @Test
    void forwardedProtoHeader_doesNotBypassDirectTlsRequirement() throws Exception {
        mockMvc.perform(get(INSPECTION_PATH)
                        .secure(false)
                        .header("X-Forwarded-Proto", "https")
                        .header("Forwarded", "proto=https")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer inspection-token"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.startsWith("https://")
                ));

        verifyNoInteractions(
                jwtDecoder,
                genericJwtDecoder,
                inspectionService,
                recoveryService
        );
    }

    @Test
    void forwardedProtoHeader_doesNotBypassRecoveryDirectTlsRequirement()
            throws Exception {
        mockMvc.perform(validRecoveryRequest("recovery-token")
                        .secure(false)
                        .header("X-Forwarded-Proto", "https, http")
                        .header("Forwarded", "proto=https"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.startsWith("https://")
                ));

        verifyNoInteractions(
                jwtDecoder,
                genericJwtDecoder,
                inspectionService,
                recoveryService
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    validRecoveryRequest(String token) {
        return post(RECOVERY_PATH)
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", COMMAND_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + REASON + "\"}");
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
