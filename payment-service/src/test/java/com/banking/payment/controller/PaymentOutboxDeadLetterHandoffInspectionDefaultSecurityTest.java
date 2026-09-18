package com.banking.payment.controller;

import com.banking.payment.config.PaymentRecoverySecurityConfiguration;
import com.banking.payment.service.PaymentOutboxDeadLetterHandoffInspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentOutboxDeadLetterHandoffInspectionController.class)
@Import(PaymentRecoverySecurityConfiguration.class)
class PaymentOutboxDeadLetterHandoffInspectionDefaultSecurityTest {
    private static final String PATH =
            "/internal/payment-outbox/dead-letter-handoffs";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentOutboxDeadLetterHandoffInspectionService inspectionService;

    @Test
    void list_withoutConfiguredOperatorHasNoFallbackCredential() throws Exception {
        mockMvc.perform(get(PATH)
                        .secure(true)
                        .with(httpBasic("admin", "admin")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(inspectionService);
    }

    @Test
    void unlistedEndpointIsDeniedEvenToInspectionAuthority() throws Exception {
        mockMvc.perform(get("/actuator/env").with(user("inspection-operator")
                        .authorities(new SimpleGrantedAuthority(
                                PaymentRecoverySecurityConfiguration
                                        .HANDOFF_INSPECTION_AUTHORITY
                        ))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(inspectionService);
    }
}
