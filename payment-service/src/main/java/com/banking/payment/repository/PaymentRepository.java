package com.banking.payment.repository;

import com.banking.payment.entity.Payment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByIdempotencyKeyHash(String idempotencyKeyHash);
    Slice<Payment> findAllByOrderByIdAsc(Pageable pageable);
    Slice<Payment> findByIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);
}
