package com.banking.payment.controller;

import com.banking.payment.api.PaymentOutboxDeadLetterHandoffPage;
import com.banking.payment.api.PaymentOutboxDeadLetterHandoffSummary;
import com.banking.payment.config.PaymentRecoverySecurityConfiguration;
import com.banking.payment.service.PaymentOutboxDeadLetterHandoffInspectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentOutboxDeadLetterHandoffInspectionController.class)
@Import(PaymentRecoverySecurityConfiguration.class)
class PaymentOutboxDeadLetterHandoffInspectionControllerTest {
    private static final String PATH =
            "/internal/payment-outbox/dead-letter-handoffs";
    private static final String USERNAME = "recovery-operator";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String PASSWORD_HASH =
            "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentOutboxDeadLetterHandoffInspectionService inspectionService;

    @DynamicPropertySource
    static void operatorCredentials(DynamicPropertyRegistry registry) {
        registry.add("payment.recovery.operator.username", () -> USERNAME);
        registry.add("payment.recovery.operator.password-hash", () -> PASSWORD_HASH);
        registry.add("server.ssl.enabled", () -> true);
    }

    @Test
    void list_unauthenticatedRequestIsRejectedBeforeService() throws Exception {
        mockMvc.perform(get(PATH).secure(true))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(inspectionService);
    }

    @Test
    void list_insecureRequestRedirectsBeforeAuthenticationOrService() throws Exception {
        mockMvc.perform(get(PATH)
                        .secure(false)
                        .with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.startsWith("https://")
                ));

        verifyNoInteractions(inspectionService);
    }

    @Test
    void list_recoveryAuthorityWithoutInspectionAuthorityIsForbidden() throws Exception {
        mockMvc.perform(get(PATH).secure(true).with(user("recovery-only")
                        .authorities(new SimpleGrantedAuthority(
                                PaymentRecoverySecurityConfiguration.RECOVERY_AUTHORITY
                        ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(inspectionService);
    }

    @Test
    void list_inspectionAuthorityReturnsOnlySafeBoundedFields() throws Exception {
        when(inspectionService.list(40L, 2)).thenReturn(page());

        mockMvc.perform(get(PATH)
                        .secure(true)
                        .param("afterId", "40")
                        .param("limit", "2")
                        .with(user("inspection-operator")
                                .authorities(new SimpleGrantedAuthority(
                                        PaymentRecoverySecurityConfiguration
                                                .HANDOFF_INSPECTION_AUTHORITY
                                ))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.handoffs.length()").value(1))
                .andExpect(jsonPath("$.handoffs[0].length()").value(6))
                .andExpect(jsonPath("$.handoffs[0].handoffId").value(41))
                .andExpect(jsonPath("$.handoffs[0].eventId").value(77))
                .andExpect(jsonPath("$.handoffs[0].exhaustionSequence").value(2))
                .andExpect(jsonPath("$.handoffs[0].exhaustedAt")
                        .value("2026-09-17T05:30:00Z"))
                .andExpect(jsonPath("$.handoffs[0].attemptCount").value(5))
                .andExpect(jsonPath("$.handoffs[0].failureType")
                        .value("TimeoutException"))
                .andExpect(jsonPath("$.nextCursor").value(41))
                .andExpect(jsonPath("$.handoffs[0].payload").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].eventKey").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].topic").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].payment").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].fromAccount").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].toAccount").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].amount").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].lastError").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].actor").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].reason").doesNotExist())
                .andExpect(jsonPath("$.handoffs[0].recoveryKeyHash").doesNotExist());

        verify(inspectionService).list(40L, 2);
    }

    @Test
    void list_configuredBasicOperatorUsesSafeDefaults() throws Exception {
        when(inspectionService.list(
                null,
                PaymentOutboxDeadLetterHandoffInspectionService.DEFAULT_LIMIT
        )).thenReturn(new PaymentOutboxDeadLetterHandoffPage(List.of(), null));

        mockMvc.perform(get(PATH)
                        .secure(true)
                        .with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoffs").isEmpty())
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        verify(inspectionService).list(
                null,
                PaymentOutboxDeadLetterHandoffInspectionService.DEFAULT_LIMIT
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?afterId=0",
            "?afterId=-1",
            "?afterId=not-a-number",
            "?limit=0",
            "?limit=-1",
            "?limit=101",
            "?limit=not-a-number"
    })
    void list_invalidQueryIsBadRequestWithoutServiceCall(String query) throws Exception {
        mockMvc.perform(get(PATH + query)
                        .secure(true)
                        .with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.title")
                        .value("Invalid handoff inspection request"))
                .andExpect(jsonPath("$.detail")
                        .value("Dead-letter handoff inspection request is invalid"));

        verifyNoInteractions(inspectionService);
    }

    private PaymentOutboxDeadLetterHandoffPage page() {
        return new PaymentOutboxDeadLetterHandoffPage(
                List.of(new PaymentOutboxDeadLetterHandoffSummary(
                        41L,
                        77L,
                        2,
                        Instant.parse("2026-09-17T05:30:00Z"),
                        5,
                        "TimeoutException"
                )),
                41L
        );
    }
}
