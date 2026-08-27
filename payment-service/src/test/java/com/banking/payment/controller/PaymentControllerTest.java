package com.banking.payment.controller;

import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentRepository paymentRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void createPayment_validRequest_savesCreatedPaymentAndPublishesOneEvent() throws Exception {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(42L);
            return payment;
        });

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

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getFromAccount()).isEqualTo(1L);
        assertThat(savedPayment.getToAccount()).isEqualTo(2L);
        assertThat(savedPayment.getAmount()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(savedPayment.getStatus()).isEqualTo("CREATED");
        verify(kafkaTemplate).send("payments", "42|1|2|750.00");
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
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            assertThat(payment.getId()).isNull();
            assertThat(payment.getStatus()).isEqualTo("CREATED");
            payment.setId(42L);
            return payment;
        });

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

        verify(paymentRepository).save(any(Payment.class));
        verify(kafkaTemplate).send("payments", "42|1|2|750.00");
    }

    private void assertBadRequestWithoutSideEffects(String request) throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentRepository);
        verify(kafkaTemplate, never()).send(anyString(), anyString());
    }
}
