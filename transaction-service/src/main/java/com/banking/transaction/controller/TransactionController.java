package com.banking.transaction.controller;

import com.banking.transaction.api.TransactionResponse;
import com.banking.transaction.service.TransactionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ResponseEntity<List<TransactionResponse>> all(
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "50")
            @Min(1) @Max(TransactionService.MAX_LIMIT) int limit
    ) {
        Slice<TransactionResponse> transactions = transactionService.findAll(afterId, limit)
                .map(TransactionResponse::from);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (transactions.hasNext() && !transactions.isEmpty()) {
            Long lastId = transactions.getContent()
                    .get(transactions.getNumberOfElements() - 1)
                    .id();
            response.header(
                    HttpHeaders.LINK,
                    "</transactions?afterId=%d&limit=%d>; rel=\"next\"".formatted(lastId, limit)
            );
        }
        return response.body(transactions.getContent());
    }

    @GetMapping("/account/{id}")
    public List<TransactionResponse> byAccount(@PathVariable @Positive Long id) {
        return transactionService.findByAccount(id).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
