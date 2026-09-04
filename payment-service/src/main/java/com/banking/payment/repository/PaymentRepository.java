package com.banking.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.banking.payment.entity.Payment;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByIdempotencyKeyHash(String idempotencyKeyHash);
}
