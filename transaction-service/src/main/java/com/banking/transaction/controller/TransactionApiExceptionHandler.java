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
    private static final URI TRANSACTION_LIST_INSTANCE = URI.create("/transactions");
    private static final URI ACCOUNT_HISTORY_INSTANCE =
            URI.create("/transactions/account");

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
        URI instance = "byAccount".equals(exception.getMethod().getName())
                ? ACCOUNT_HISTORY_INSTANCE
                : TRANSACTION_LIST_INSTANCE;
        return validationProblem(instance);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return validationProblem(instanceForParameter(exception.getParameter().getParameterName()));
    }

    private URI instanceForParameter(String parameterName) {
        if ("id".equals(parameterName)) {
            return ACCOUNT_HISTORY_INSTANCE;
        }
        return TRANSACTION_LIST_INSTANCE;
    }

    private ProblemDetail validationProblem(URI instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request values are invalid."
        );
        problem.setType(VALIDATION_TYPE);
        problem.setTitle("Request validation failed");
        problem.setInstance(instance);
        return problem;
    }
}
