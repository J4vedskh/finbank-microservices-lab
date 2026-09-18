package com.banking.payment.controller;

import com.banking.payment.api.PaymentOutboxDeadLetterHandoffPage;
import com.banking.payment.service.PaymentOutboxDeadLetterHandoffInspectionService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Validated
@RestController
@RequestMapping("/internal/payment-outbox/dead-letter-handoffs")
public class PaymentOutboxDeadLetterHandoffInspectionController {
    private final PaymentOutboxDeadLetterHandoffInspectionService inspectionService;

    public PaymentOutboxDeadLetterHandoffInspectionController(
            PaymentOutboxDeadLetterHandoffInspectionService inspectionService
    ) {
        this.inspectionService = inspectionService;
    }

    @GetMapping
    public PaymentOutboxDeadLetterHandoffPage list(
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return inspectionService.list(afterId, limit);
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail handleInvalidRequest() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Dead-letter handoff inspection request is invalid"
        );
        problem.setTitle("Invalid handoff inspection request");
        return problem;
    }
}
