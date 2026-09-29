package com.banking.transaction.controller;

import com.banking.transaction.api.TransactionResponse;
import com.banking.transaction.service.TransactionService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/transactions")
public class TransactionController {
    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public List<TransactionResponse> all() {
        return transactionService.findAll().stream()
                .map(TransactionResponse::from)
                .toList();
    }

    @GetMapping("/account/{id}")
    public List<TransactionResponse> byAccount(@PathVariable @Positive Long id) {
        return transactionService.findByAccount(id).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
