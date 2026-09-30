package com.banking.transaction.repository;

import com.banking.transaction.entity.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Slice<Transaction> findAllByOrderByIdAsc(Pageable pageable);
    Slice<Transaction> findByIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);
    List<Transaction> findByFromAccountOrToAccount(Long from, Long to);
    Optional<Transaction> findByPaymentId(Long paymentId);
}
