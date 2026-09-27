package com.banking.payment.controller;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.api.PaymentResponse;
import com.banking.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public List<PaymentResponse> all() {
        return paymentService.findAll().stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @PostMapping
    public PaymentResponse create(
            @RequestHeader("Idempotency-Key")
            @NotBlank
            @Size(max = 128)
            @Pattern(regexp = "^[!-~]+$")
            String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        return PaymentResponse.from(paymentService.create(idempotencyKey, request));
    }
}
