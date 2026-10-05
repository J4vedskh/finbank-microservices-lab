package com.banking.transaction.controller;

import com.banking.transaction.service.TransactionNotFoundException;
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
    private static final URI NOT_FOUND_TYPE =
            URI.create("urn:finbank:problem:resource-not-found");
    private static final URI TRANSACTION_LIST_INSTANCE = URI.create("/transactions");
    private static final URI ACCOUNT_HISTORY_INSTANCE =
            URI.create("/transactions/account");

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
        return validationProblem(instanceForMethod(exception.getMethod().getName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String methodName = exception.getParameter().getMethod() == null
                ? null
                : exception.getParameter().getMethod().getName();
        return validationProblem(instanceForMethod(methodName));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail handleNotFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The requested resource was not found."
        );
        problem.setType(NOT_FOUND_TYPE);
        problem.setTitle("Resource not found");
        problem.setInstance(TRANSACTION_LIST_INSTANCE);
        return problem;
    }

    private URI instanceForMethod(String methodName) {
        return "byAccount".equals(methodName)
                ? ACCOUNT_HISTORY_INSTANCE
                : TRANSACTION_LIST_INSTANCE;
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
