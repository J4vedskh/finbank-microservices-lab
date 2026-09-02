package com.banking.transaction.controller;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.service.TransactionService;
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
    public List<Transaction> all() {
        return transactionService.findAll();
    }

    @GetMapping("/account/{id}")
    public List<Transaction> byAccount(@PathVariable Long id) {
        return transactionService.findByAccount(id);
    }
}
