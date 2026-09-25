package com.banking.account.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(assignableTypes = AccountController.class)
public class AccountApiExceptionHandler {
    private static final URI VALIDATION_TYPE =
            URI.create("urn:finbank:problem:validation-failed");
    private static final URI ACCOUNT_INSTANCE = URI.create("/accounts");

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    ProblemDetail handleInvalidRequest() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request values are invalid."
        );
        problem.setType(VALIDATION_TYPE);
        problem.setTitle("Request validation failed");
        problem.setInstance(ACCOUNT_INSTANCE);
        return problem;
    }
}
