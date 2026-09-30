package com.banking.payment.controller;

import com.banking.payment.api.CreatePaymentRequest;
import com.banking.payment.api.PaymentResponse;
import com.banking.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<List<PaymentResponse>> all(
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "50")
            @Min(1) @Max(PaymentService.MAX_LIMIT) int limit
    ) {
        Slice<PaymentResponse> payments = paymentService.findAll(afterId, limit)
                .map(PaymentResponse::from);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (payments.hasNext() && !payments.isEmpty()) {
            Long lastId = payments.getContent()
                    .get(payments.getNumberOfElements() - 1)
                    .id();
            response.header(
                    HttpHeaders.LINK,
                    "</payments?afterId=" + lastId + "&limit=" + limit + ">; rel=\"next\""
            );
        }
        return response.body(payments.getContent());
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
