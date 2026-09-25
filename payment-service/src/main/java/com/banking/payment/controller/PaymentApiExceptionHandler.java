package com.banking.payment.controller;

import com.banking.payment.service.PaymentIdempotencyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;

@RestControllerAdvice(assignableTypes = PaymentController.class)
public class PaymentApiExceptionHandler {
    private static final URI VALIDATION_TYPE =
            URI.create("urn:finbank:problem:validation-failed");
    private static final URI IDEMPOTENCY_CONFLICT_TYPE =
            URI.create("urn:finbank:problem:idempotency-key-conflict");
    private static final URI PAYMENT_INSTANCE = URI.create("/payments");

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            HandlerMethodValidationException.class
    })
    ProblemDetail handleInvalidRequest() {
        return problem(
                HttpStatus.BAD_REQUEST,
                VALIDATION_TYPE,
                "Request validation failed",
                "One or more request values are invalid.",
                PAYMENT_INSTANCE
        );
    }

    @ExceptionHandler(PaymentIdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict() {
        return problem(
                HttpStatus.CONFLICT,
                IDEMPOTENCY_CONFLICT_TYPE,
                "Idempotency key conflict",
                "The Idempotency-Key is already associated with a different payment request.",
                PAYMENT_INSTANCE
        );
    }

    private ProblemDetail problem(
            HttpStatus status,
            URI type,
            String title,
            String detail,
            URI instance
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(instance);
        return problem;
    }
}
