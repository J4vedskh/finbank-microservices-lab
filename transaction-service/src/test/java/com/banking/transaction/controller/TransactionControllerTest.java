package com.banking.transaction.controller;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        verify(transactionService).findAll();
    }

    @Test
    void listAccountHistory_delegatesPathIdToService() throws Exception {
        when(transactionService.findByAccount(7L)).thenReturn(List.of(savedTransaction()));

        mockMvc.perform(get("/transactions/account/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fromAccount").value(1))
                .andExpect(jsonPath("$[0].toAccount").value(2));

        verify(transactionService).findByAccount(7L);
    }

    private Transaction savedTransaction() {
        Transaction transaction = new Transaction();
        transaction.setId(99L);
        transaction.setFromAccount(1L);
        transaction.setToAccount(2L);
        transaction.setAmount(new BigDecimal("750.00"));
        transaction.setStatus("COMPLETED");
        return transaction;
    }
}
