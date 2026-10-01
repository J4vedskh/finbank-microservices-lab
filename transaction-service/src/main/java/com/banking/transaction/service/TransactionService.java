package com.banking.transaction.service;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Service
public class TransactionService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private static final String EXPECTED_FORMAT =
            "expected paymentId|fromAccount|toAccount|amount";

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public Slice<Transaction> findAll(Long afterId, int limit) {
        validateListRequest(afterId, limit);

        PageRequest pageRequest = PageRequest.of(0, limit);
        if (afterId == null) {
            return transactionRepository.findAllByOrderByIdAsc(pageRequest);
        }
        return transactionRepository.findByIdGreaterThanOrderByIdAsc(afterId, pageRequest);
    }

    @Transactional(readOnly = true)
    public Slice<Transaction> findByAccount(Long accountId, Long afterId, int limit) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        validateListRequest(afterId, limit);

        PageRequest pageRequest = PageRequest.of(0, limit);
        if (afterId == null) {
            return transactionRepository.findAccountHistory(accountId, pageRequest);
        }
        return transactionRepository.findAccountHistoryAfterId(
                accountId,
                afterId,
                pageRequest
        );
    }

    private void validateListRequest(Long afterId, int limit) {
        if (afterId != null && afterId <= 0) {
            throw new IllegalArgumentException("afterId must be positive");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "transaction list limit must be between 1 and " + MAX_LIMIT
            );
        }
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
