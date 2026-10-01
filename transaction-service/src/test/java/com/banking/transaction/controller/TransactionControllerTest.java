package com.banking.transaction.controller;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    @Test
    void listTransactions_usesDefaultBoundedSliceWithoutNextLink() throws Exception {
        when(transactionService.findAll(null, TransactionService.DEFAULT_LIMIT)).thenReturn(new SliceImpl<>(
                List.of(savedTransaction()),
                PageRequest.of(0, TransactionService.DEFAULT_LIMIT),
                false
        ));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LINK))
                .andExpect(jsonPath("$[0].length()").value(7))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].paymentId").value(42))
                .andExpect(jsonPath("$[0].fromAccount").value(1))
                .andExpect(jsonPath("$[0].toAccount").value(2))
                .andExpect(jsonPath("$[0].amount").value(750.00))
                .andExpect(jsonPath("$[0].createdAt")
                        .value("2026-09-27T09:30:00Z"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        verify(transactionService).findAll(null, TransactionService.DEFAULT_LIMIT);
    }

    @Test
    void listTransactions_withCursorAndAdditionalRowsAddsNextLink() throws Exception {
        Transaction first = savedTransaction();
        first.setId(99L);
        Transaction second = savedTransaction();
        second.setId(100L);
        when(transactionService.findAll(50L, 2)).thenReturn(new SliceImpl<>(
                List.of(first, second),
                PageRequest.of(0, 2),
                true
        ));

        mockMvc.perform(get("/transactions")
                        .param("afterId", "50")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.LINK,
                        "</transactions?afterId=100&limit=2>; rel=\"next\""
                ))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[1].id").value(100));

        verify(transactionService).findAll(50L, 2);
    }

    @Test
    void listAccountHistory_usesDefaultBoundedSliceWithoutNextLink() throws Exception {
        when(transactionService.findByAccount(7L, null, TransactionService.DEFAULT_LIMIT))
                .thenReturn(new SliceImpl<>(
                        List.of(savedTransaction()),
                        PageRequest.of(0, TransactionService.DEFAULT_LIMIT),
                        false
                ));

        mockMvc.perform(get("/transactions/account/7"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LINK))
                .andExpect(jsonPath("$[0].length()").value(7))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].paymentId").value(42))
                .andExpect(jsonPath("$[0].fromAccount").value(1))
                .andExpect(jsonPath("$[0].toAccount").value(2))
                .andExpect(jsonPath("$[0].amount").value(750.00))
                .andExpect(jsonPath("$[0].createdAt")
                        .value("2026-09-27T09:30:00Z"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        verify(transactionService).findByAccount(7L, null, TransactionService.DEFAULT_LIMIT);
    }

    @Test
    void listAccountHistory_withCursorOnFinalPageOmitsNextLink() throws Exception {
        when(transactionService.findByAccount(7L, 50L, 2)).thenReturn(new SliceImpl<>(
                List.of(savedTransaction()),
                PageRequest.of(0, 2),
                false
        ));

        mockMvc.perform(get("/transactions/account/7")
                        .param("afterId", "50")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LINK));

        verify(transactionService).findByAccount(7L, 50L, 2);
    }

    @Test
    void listAccountHistory_withCursorAndAdditionalRowsAddsExactNextLink() throws Exception {
        Transaction first = savedTransaction();
        first.setId(99L);
        Transaction second = savedTransaction();
        second.setId(100L);
        when(transactionService.findByAccount(7L, 50L, 2)).thenReturn(new SliceImpl<>(
                List.of(first, second),
                PageRequest.of(0, 2),
                true
        ));

        mockMvc.perform(get("/transactions/account/7")
                        .param("afterId", "50")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.LINK,
                        "</transactions/account/7?afterId=100&limit=2>; rel=\"next\""
                ))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[1].id").value(100));

        verify(transactionService).findByAccount(7L, 50L, 2);
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

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void listAccountHistory_nonPositiveAfterIdReturnsSafeProblemWithoutServiceCall(
            String afterId
    ) throws Exception {
        assertInvalidAccountHistoryProblem("7", "afterId", afterId)
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(HandlerMethodValidationException.class));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101"})
    void listAccountHistory_outOfRangeLimitReturnsSafeProblemWithoutServiceCall(
            String limit
    ) throws Exception {
        assertInvalidAccountHistoryProblem("7", "limit", limit)
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(HandlerMethodValidationException.class));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"afterId", "limit"})
    void listAccountHistory_nonNumericQueryReturnsSafeProblemWithoutServiceCall(
            String parameterName
    ) throws Exception {
        assertInvalidAccountHistoryProblem("7", parameterName, "not-a-number")
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentTypeMismatchException.class));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void listTransactions_nonPositiveAfterIdReturnsSafeProblemWithoutServiceCall(
            String afterId
    ) throws Exception {
        assertInvalidTransactionListProblem("afterId", afterId)
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(HandlerMethodValidationException.class));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101"})
    void listTransactions_outOfRangeLimitReturnsSafeProblemWithoutServiceCall(
            String limit
    ) throws Exception {
        assertInvalidTransactionListProblem("limit", limit)
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(HandlerMethodValidationException.class));

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"afterId", "limit"})
    void listTransactions_nonNumericQueryReturnsSafeProblemWithoutServiceCall(
            String parameterName
    ) throws Exception {
        assertInvalidTransactionListProblem(parameterName, "not-a-number")
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentTypeMismatchException.class));

        verifyNoInteractions(transactionService);
    }

    private ResultActions assertInvalidAccountHistoryProblem(String accountId) throws Exception {
        return assertInvalidAccountHistoryProblem(accountId, null, null);
    }

    private ResultActions assertInvalidAccountHistoryProblem(
            String accountId,
            String parameterName,
            String parameterValue
    ) throws Exception {
        var request = get("/transactions/account/{id}", accountId);
        if (parameterName != null) {
            request.param(parameterName, parameterValue);
        }
        return mockMvc.perform(request)
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

    private ResultActions assertInvalidTransactionListProblem(
            String parameterName,
            String parameterValue
    ) throws Exception {
        return mockMvc.perform(get("/transactions").param(parameterName, parameterValue))
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
                .andExpect(jsonPath("$.instance").value("/transactions"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.field").doesNotExist())
                .andExpect(jsonPath("$.rejectedValue").doesNotExist())
                .andExpect(jsonPath("$.afterId").doesNotExist())
                .andExpect(jsonPath("$.limit").doesNotExist())
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
