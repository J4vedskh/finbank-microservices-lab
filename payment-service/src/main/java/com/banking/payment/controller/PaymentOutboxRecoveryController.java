package com.banking.payment.controller;

import com.banking.payment.api.PaymentOutboxRecoveryRequest;
import com.banking.payment.api.PaymentOutboxRecoveryResponse;
import com.banking.payment.entity.PaymentOutboxRecoveryAudit;
import com.banking.payment.service.PaymentOutboxEventNotFoundException;
import com.banking.payment.service.PaymentOutboxRecoveryCommandConflictException;
import com.banking.payment.service.PaymentOutboxRecoveryNotAllowedException;
import com.banking.payment.service.PaymentOutboxRecoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payment-outbox")
public class PaymentOutboxRecoveryController {
    private final PaymentOutboxRecoveryService recoveryService;

    public PaymentOutboxRecoveryController(PaymentOutboxRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/{eventId}/recovery")
    public PaymentOutboxRecoveryResponse requeueExhausted(
            @PathVariable @Positive Long eventId,
            @RequestHeader("Idempotency-Key")
            @NotBlank
            @Size(min = 16, max = 128)
            @Pattern(regexp = "^[!-~]+$")
            String recoveryKey,
            @Valid @RequestBody PaymentOutboxRecoveryRequest request,
            Authentication authentication
    ) {
        PaymentOutboxRecoveryAudit audit = recoveryService.requeueExhausted(
                eventId,
                recoveryKey,
                authentication.getName(),
                request.reason()
        );
        return new PaymentOutboxRecoveryResponse(eventId, audit.getRequeuedAt());
    }

    @ExceptionHandler(PaymentOutboxEventNotFoundException.class)
    ProblemDetail handleMissingEvent(PaymentOutboxEventNotFoundException failure) {
        return problem(HttpStatus.NOT_FOUND, "Outbox event not found", failure.getMessage());
    }

    @ExceptionHandler(PaymentOutboxRecoveryNotAllowedException.class)
    ProblemDetail handleRecoveryNotAllowed(PaymentOutboxRecoveryNotAllowedException failure) {
        return problem(HttpStatus.CONFLICT, "Recovery not allowed", failure.getMessage());
    }

    @ExceptionHandler(PaymentOutboxRecoveryCommandConflictException.class)
    ProblemDetail handleCommandConflict(PaymentOutboxRecoveryCommandConflictException failure) {
        return problem(HttpStatus.CONFLICT, "Recovery command conflict", failure.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid recovery request", "Recovery request is invalid");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
