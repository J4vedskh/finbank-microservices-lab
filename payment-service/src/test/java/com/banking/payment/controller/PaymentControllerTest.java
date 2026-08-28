package com.banking.payment.controller;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @Test
    void listPayments_delegatesToService() throws Exception {
        when(paymentService.findAll()).thenReturn(List.of(savedPayment()));

        mockMvc.perform(get("/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].status").value("CREATED"));

        verify(paymentService).findAll();
    }

    @Test
    void createPayment_validRequest_delegatesValidatedInputToService() throws Exception {
        when(paymentService.create(any(CreatePaymentRequest.class))).thenReturn(savedPayment());

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccount": 1,
                                  "toAccount": 2,
                                  "amount": 750.00
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.fromAccount").value(1))
                .andExpect(jsonPath("$.toAccount").value(2))
                .andExpect(jsonPath("$.amount").value(750.00))
                .andExpect(jsonPath("$.status").value("CREATED"));

        ArgumentCaptor<CreatePaymentRequest> requestCaptor =
                ArgumentCaptor.forClass(CreatePaymentRequest.class);
        verify(paymentService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().fromAccount()).isEqualTo(1L);
        assertThat(requestCaptor.getValue().toAccount()).isEqualTo(2L);
        assertThat(requestCaptor.getValue().amount()).isEqualByComparingTo("750.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"toAccount\":2,\"amount\":750.00}",
            "{\"fromAccount\":1,\"amount\":750.00}"
    })
    void createPayment_missingAccountId_returnsBadRequestWithoutSideEffects(String request) throws Exception {
        assertBadRequestWithoutSideEffects(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fromAccount\":0,\"toAccount\":2,\"amount\":750.00}",
            "{\"fromAccount\":1,\"toAccount\":-2,\"amount\":750.00}"
    })
    void createPayment_nonPositiveAccountId_returnsBadRequestWithoutSideEffects(String request) throws Exception {
        assertBadRequestWithoutSideEffects(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fromAccount\":1,\"toAccount\":2,\"amount\":0}",
            "{\"fromAccount\":1,\"toAccount\":2,\"amount\":-0.01}"
    })
    void createPayment_nonPositiveAmount_returnsBadRequestWithoutSideEffects(String request) throws Exception {
        assertBadRequestWithoutSideEffects(request);
    }

    @Test
    void createPayment_missingAmount_returnsBadRequestWithoutSideEffects() throws Exception {
        assertBadRequestWithoutSideEffects("""
                {
                  "fromAccount": 1,
                  "toAccount": 2
                }
                """);
    }

    @Test
    void createPayment_sameSourceAndDestination_returnsBadRequestWithoutSideEffects() throws Exception {
        assertBadRequestWithoutSideEffects("""
                {
                  "fromAccount": 1,
                  "toAccount": 1,
                  "amount": 750.00
                }
                """);
    }

    @Test
    void createPayment_clientCannotOverrideServerOwnedIdOrStatus() throws Exception {
        when(paymentService.create(any(CreatePaymentRequest.class))).thenReturn(savedPayment());

        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 999,
                                  "fromAccount": 1,
                                  "toAccount": 2,
                                  "amount": 750.00,
                                  "status": "COMPLETED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("CREATED"));

        ArgumentCaptor<CreatePaymentRequest> requestCaptor =
                ArgumentCaptor.forClass(CreatePaymentRequest.class);
        verify(paymentService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().fromAccount()).isEqualTo(1L);
        assertThat(requestCaptor.getValue().toAccount()).isEqualTo(2L);
        assertThat(requestCaptor.getValue().amount()).isEqualByComparingTo("750.00");
    }

    private void assertBadRequestWithoutSideEffects(String request) throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    private Payment savedPayment() {
        Payment payment = new Payment();
        payment.setId(42L);
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        return payment;
    }
}
