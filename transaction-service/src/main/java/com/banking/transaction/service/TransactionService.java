package com.banking.transaction.service;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

@Service
public class TransactionService {
    private static final String EXPECTED_FORMAT =
            "expected paymentId|fromAccount|toAccount|amount";

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public List<Transaction> findAll() {
        return transactionRepository.findAll();
    }

    public List<Transaction> findByAccount(Long accountId) {
        return transactionRepository.findByFromAccountOrToAccount(accountId, accountId);
    }

    public Transaction recordPaymentEvent(String payload) {
        PaymentEvent paymentEvent = parse(payload);

        return transactionRepository.findByPaymentId(paymentEvent.paymentId())
                .map(existing -> acceptReplay(existing, paymentEvent))
                .orElseGet(() -> persist(paymentEvent));
    }

    private Transaction persist(PaymentEvent paymentEvent) {
        Transaction transaction = new Transaction();
        transaction.setPaymentId(paymentEvent.paymentId());
        transaction.setFromAccount(paymentEvent.fromAccount());
        transaction.setToAccount(paymentEvent.toAccount());
        transaction.setAmount(paymentEvent.amount());
        transaction.setStatus("COMPLETED");

        try {
            return transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException exception) {
            return transactionRepository.findByPaymentId(paymentEvent.paymentId())
                    .map(existing -> acceptReplay(existing, paymentEvent))
                    .orElseThrow(() -> exception);
        }
    }

    private Transaction acceptReplay(Transaction existing, PaymentEvent paymentEvent) {
        if (sameBusinessEvent(existing, paymentEvent)) {
            return existing;
        }
        throw new PaymentEventConflictException(paymentEvent.paymentId());
    }

    private boolean sameBusinessEvent(Transaction existing, PaymentEvent paymentEvent) {
        return Objects.equals(existing.getPaymentId(), paymentEvent.paymentId())
                && Objects.equals(existing.getFromAccount(), paymentEvent.fromAccount())
                && Objects.equals(existing.getToAccount(), paymentEvent.toAccount())
                && existing.getAmount() != null
                && existing.getAmount().compareTo(paymentEvent.amount()) == 0;
    }

    private PaymentEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new MalformedPaymentEventException("payload must not be blank");
        }

        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) {
            throw new MalformedPaymentEventException(EXPECTED_FORMAT);
        }

        try {
            long paymentId = positiveLong(parts[0], "paymentId");
            long fromAccount = positiveLong(parts[1], "fromAccount");
            long toAccount = positiveLong(parts[2], "toAccount");
            BigDecimal amount = normalizedAmount(parts[3]);

            if (fromAccount == toAccount) {
                throw new MalformedPaymentEventException(
                        "source and destination accounts must be different"
                );
            }
            return new PaymentEvent(paymentId, fromAccount, toAccount, amount);
        } catch (NumberFormatException exception) {
            throw new MalformedPaymentEventException(EXPECTED_FORMAT, exception);
        }
    }

    private long positiveLong(String value, String fieldName) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new MalformedPaymentEventException(fieldName + " must be positive");
        }
        return parsed;
    }

    private BigDecimal normalizedAmount(String value) {
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() <= 0) {
            throw new MalformedPaymentEventException("amount must be positive");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new MalformedPaymentEventException(
                    "amount must have at most two decimal places",
                    exception
            );
        }
    }

    private record PaymentEvent(
            long paymentId,
            long fromAccount,
            long toAccount,
            BigDecimal amount
    ) {
    }
}
