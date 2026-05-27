package com.banking.transaction.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.banking.transaction.entity.Transaction;

import java.util.List;
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByFromAccountOrToAccount(Long from, Long to);
}
