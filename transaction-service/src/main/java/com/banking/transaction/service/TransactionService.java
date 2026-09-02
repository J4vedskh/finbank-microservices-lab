package com.banking.transaction.service;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

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

        Transaction transaction = new Transaction();
        transaction.setFromAccount(paymentEvent.fromAccount());
        transaction.setToAccount(paymentEvent.toAccount());
        transaction.setAmount(paymentEvent.amount());
        transaction.setStatus("COMPLETED");
        return transactionRepository.save(transaction);
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
            BigDecimal amount = new BigDecimal(parts[3]);

            if (fromAccount == toAccount) {
                throw new MalformedPaymentEventException(
                        "source and destination accounts must be different"
                );
            }
            if (amount.signum() <= 0) {
                throw new MalformedPaymentEventException("amount must be positive");
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

    private record PaymentEvent(
            long paymentId,
            long fromAccount,
            long toAccount,
            BigDecimal amount
    ) {
    }
}
