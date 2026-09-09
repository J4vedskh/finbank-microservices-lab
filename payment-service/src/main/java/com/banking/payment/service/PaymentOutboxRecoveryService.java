package com.banking.payment.service;

import com.banking.payment.entity.PaymentOutboxEvent;
import com.banking.payment.entity.PaymentOutboxStatus;
import com.banking.payment.repository.PaymentOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

@Service
public class PaymentOutboxRecoveryService {
    private static final String EXHAUSTED_PAYMENT_STATUS = "PUBLISH_EXHAUSTED";

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final Clock clock;

    @Autowired
    public PaymentOutboxRecoveryService(PaymentOutboxRepository paymentOutboxRepository) {
        this(paymentOutboxRepository, Clock.systemUTC());
    }

    PaymentOutboxRecoveryService(
            PaymentOutboxRepository paymentOutboxRepository,
            Clock clock
    ) {
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public PaymentOutboxEvent requeueExhausted(Long eventId) {
        PaymentOutboxEvent event = paymentOutboxRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new PaymentOutboxEventNotFoundException(eventId));

        if (!isRecoverable(event)) {
            throw new PaymentOutboxRecoveryNotAllowedException(eventId);
        }

        event.requeue(clock.instant());
        event.getPayment().setStatus("PENDING_RETRY");
        return event;
    }

    private boolean isRecoverable(PaymentOutboxEvent event) {
        return event.getStatus() == PaymentOutboxStatus.PENDING
                && event.getExhaustedAt() != null
                && EXHAUSTED_PAYMENT_STATUS.equals(event.getPayment().getStatus());
    }
}
