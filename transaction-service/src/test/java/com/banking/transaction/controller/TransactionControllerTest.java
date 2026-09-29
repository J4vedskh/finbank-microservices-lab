package com.banking.transaction.controller;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    @Test
    void listTransactions_delegatesToService() throws Exception {
        when(transactionService.findAll()).thenReturn(List.of(savedTransaction()));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(7))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].paymentId").value(42))
                .andExpect(jsonPath("$[0].fromAccount").value(1))
                .andExpect(jsonPath("$[0].toAccount").value(2))
                .andExpect(jsonPath("$[0].amount").value(750.00))
                .andExpect(jsonPath("$[0].createdAt")
                        .value("2026-09-27T09:30:00Z"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        verify(transactionService).findAll();
    }

    @Test
    void listAccountHistory_delegatesPathIdToService() throws Exception {
        when(transactionService.findByAccount(7L)).thenReturn(List.of(savedTransaction()));

        mockMvc.perform(get("/transactions/account/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].length()").value(7))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].paymentId").value(42))
                .andExpect(jsonPath("$[0].fromAccount").value(1))
                .andExpect(jsonPath("$[0].toAccount").value(2))
                .andExpect(jsonPath("$[0].amount").value(750.00))
                .andExpect(jsonPath("$[0].createdAt")
                        .value("2026-09-27T09:30:00Z"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        verify(transactionService).findByAccount(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void listAccountHistory_nonPositiveIdReturnsSafeProblemWithoutSideEffects(
            String accountId
    ) throws Exception {
        assertInvalidAccountHistoryProblem(accountId)
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(HandlerMethodValidationException.class));

        verifyNoInteractions(transactionService);
    }

    @Test
    void listAccountHistory_nonNumericIdReturnsSafeProblemWithoutSideEffects() throws Exception {
        assertInvalidAccountHistoryProblem("not-a-number")
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentTypeMismatchException.class));

        verifyNoInteractions(transactionService);
    }

    private ResultActions assertInvalidAccountHistoryProblem(String accountId) throws Exception {
        return mockMvc.perform(get("/transactions/account/{id}", accountId))
                .andExpect(status().isBadRequest())
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
                .andExpect(jsonPath("$.instance").value("/transactions/account"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.field").doesNotExist())
                .andExpect(jsonPath("$.rejectedValue").doesNotExist())
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    private Transaction savedTransaction() {
        Transaction transaction = new Transaction();
        transaction.setId(99L);
        transaction.setPaymentId(42L);
        transaction.setFromAccount(1L);
        transaction.setToAccount(2L);
        transaction.setAmount(new BigDecimal("750.00"));
        transaction.setCreatedAt(Instant.parse("2026-09-27T09:30:00Z"));
        transaction.setStatus("COMPLETED");
        return transaction;
    }
}
