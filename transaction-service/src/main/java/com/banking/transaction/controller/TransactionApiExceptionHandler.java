package com.banking.transaction.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

@RestControllerAdvice(assignableTypes = TransactionController.class)
public class TransactionApiExceptionHandler {
    private static final URI VALIDATION_TYPE =
            URI.create("urn:finbank:problem:validation-failed");
    private static final URI ACCOUNT_HISTORY_INSTANCE =
            URI.create("/transactions/account");

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class
    })
    ProblemDetail handleInvalidAccountHistoryRequest() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request values are invalid."
        );
        problem.setType(VALIDATION_TYPE);
        problem.setTitle("Request validation failed");
        problem.setInstance(ACCOUNT_HISTORY_INSTANCE);
        return problem;
    }
}
