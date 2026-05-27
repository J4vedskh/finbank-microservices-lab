package com.banking.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.banking.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {}
