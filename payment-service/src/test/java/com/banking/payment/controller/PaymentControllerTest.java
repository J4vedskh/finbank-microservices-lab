package com.banking.payment.controller;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.entity.Payment;
import com.banking.payment.service.PaymentIdempotencyConflictException;
import com.banking.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(com.banking.payment.config.PaymentRecoverySecurityConfiguration.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @Test
    void listPayments_usesDefaultsAndReturnsNextLinkWithoutInternalFields() throws Exception {
        when(paymentService.findAll(isNull(), eq(PaymentService.DEFAULT_LIMIT))).thenReturn(
                new SliceImpl<>(
                        List.of(savedPayment()),
                        PageRequest.of(0, PaymentService.DEFAULT_LIMIT),
                        true
                )
        );

        mockMvc.perform(get("/payments"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Link",
                        "</payments?afterId=42&limit=50>; rel=\"next\""
                ))
                .andExpect(jsonPath("$[0].length()").value(5))
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].fromAccount").value(1))
                .andExpect(jsonPath("$[0].toAccount").value(2))
                .andExpect(jsonPath("$[0].amount").value(750.00))
                .andExpect(jsonPath("$[0].idempotencyKeyHash").doesNotExist())
                .andExpect(jsonPath("$[0].status").value("CREATED"));

        verify(paymentService).findAll(null, PaymentService.DEFAULT_LIMIT);
    }

    @Test
    void listPayments_usesCustomCursorAndOmitsLinkOnFinalPage() throws Exception {
        Payment payment = savedPayment();
        payment.setId(43L);
        when(paymentService.findAll(42L, 2)).thenReturn(
                new SliceImpl<>(List.of(payment), PageRequest.of(0, 2), false)
        );

        mockMvc.perform(get("/payments")
                        .param("afterId", "42")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Link"))
                .andExpect(jsonPath("$[0].id").value(43))
                .andExpect(jsonPath("$[0].idempotencyKeyHash").doesNotExist());

        verify(paymentService).findAll(42L, 2);
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
    void listPayments_invalidPaginationReturnsSafeProblemWithoutServiceCall(String query) throws Exception {
        assertInvalidPaymentProblem(mockMvc.perform(get("/payments" + query)));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPayment_validRequest_delegatesValidatedInputToService() throws Exception {
        when(paymentService.create(anyString(), any(CreatePaymentRequest.class)))
                .thenReturn(savedPayment());

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "pay-key-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccount": 1,
                                  "toAccount": 2,
                                  "amount": 750.00
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.fromAccount").value(1))
                .andExpect(jsonPath("$.toAccount").value(2))
                .andExpect(jsonPath("$.amount").value(750.00))
                .andExpect(jsonPath("$.idempotencyKeyHash").doesNotExist())
                .andExpect(jsonPath("$.status").value("CREATED"));

        ArgumentCaptor<CreatePaymentRequest> requestCaptor =
                ArgumentCaptor.forClass(CreatePaymentRequest.class);
        verify(paymentService).create(eq("pay-key-42"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().fromAccount()).isEqualTo(1L);
        assertThat(requestCaptor.getValue().toAccount()).isEqualTo(2L);
        assertThat(requestCaptor.getValue().amount()).isEqualByComparingTo("750.00");
    }

    @Test
    void createPayment_missingIdempotencyKey_returnsBadRequestWithoutSideEffects() throws Exception {
        assertInvalidPaymentProblem(mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentRequest())));

        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "contains space", "clé"})
    void createPayment_invalidIdempotencyKey_returnsBadRequestWithoutSideEffects(String key) throws Exception {
        assertInvalidPaymentProblem(mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentRequest())));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPayment_oversizedIdempotencyKey_returnsBadRequestWithoutSideEffects() throws Exception {
        assertInvalidPaymentProblem(mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "a".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentRequest())));

        verifyNoInteractions(paymentService);
    }

    @Test
    void createPayment_conflictingIdempotencyKey_returnsConflict() throws Exception {
        when(paymentService.create(eq("pay-key-42"), any(CreatePaymentRequest.class)))
                .thenThrow(new PaymentIdempotencyConflictException());

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "pay-key-42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPaymentRequest()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.type")
                        .value("urn:finbank:problem:idempotency-key-conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Idempotency key conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("The Idempotency-Key is already associated with a different payment request."))
                .andExpect(jsonPath("$.instance").value("/payments"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void createPayment_malformedJsonReturnsSafeProblemWithoutSideEffects() throws Exception {
        assertInvalidPaymentProblem(mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "pay-key-42")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccount\":1,\"toAccount\":2,\"amount\":")));

        verifyNoInteractions(paymentService);
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
    void createPayment_amountWithMoreThanTwoDecimals_returnsBadRequestWithoutSideEffects() throws Exception {
        assertBadRequestWithoutSideEffects(
                "{\"fromAccount\":1,\"toAccount\":2,\"amount\":750.001}"
        );
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
        when(paymentService.create(anyString(), any(CreatePaymentRequest.class)))
                .thenReturn(savedPayment());

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "pay-key-42")
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
        verify(paymentService).create(eq("pay-key-42"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().fromAccount()).isEqualTo(1L);
        assertThat(requestCaptor.getValue().toAccount()).isEqualTo(2L);
        assertThat(requestCaptor.getValue().amount()).isEqualByComparingTo("750.00");
    }

    private void assertBadRequestWithoutSideEffects(String request) throws Exception {
        assertInvalidPaymentProblem(mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "pay-key-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)));

        verifyNoInteractions(paymentService);
    }

    private void assertInvalidPaymentProblem(
            org.springframework.test.web.servlet.ResultActions result
    ) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.type")
                        .value("urn:finbank:problem:validation-failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.detail")
                        .value("One or more request values are invalid."))
                .andExpect(jsonPath("$.instance").value("/payments"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.field").doesNotExist())
                .andExpect(jsonPath("$.rejectedValue").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.fromAccount").doesNotExist())
                .andExpect(jsonPath("$.toAccount").doesNotExist())
                .andExpect(jsonPath("$.amount").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    private String validPaymentRequest() {
        return "{\"fromAccount\":1,\"toAccount\":2,\"amount\":750.00}";
    }

    private Payment savedPayment() {
        Payment payment = new Payment();
        payment.setId(42L);
        payment.setIdempotencyKeyHash("a".repeat(64));
        payment.setFromAccount(1L);
        payment.setToAccount(2L);
        payment.setAmount(new BigDecimal("750.00"));
        payment.setStatus("CREATED");
        return payment;
    }
}
