package com.banking.transaction.repository;

import com.banking.transaction.entity.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Slice<Transaction> findAllByOrderByIdAsc(Pageable pageable);
    Slice<Transaction> findByIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);
    @Query("""
            select t from Transaction t
            where (t.fromAccount = :accountId or t.toAccount = :accountId)
            order by t.id asc
            """)
    Slice<Transaction> findAccountHistory(
            @Param("accountId") Long accountId,
            Pageable pageable
    );

    @Query("""
            select t from Transaction t
            where (t.fromAccount = :accountId or t.toAccount = :accountId)
              and t.id > :afterId
            order by t.id asc
            """)
    Slice<Transaction> findAccountHistoryAfterId(
            @Param("accountId") Long accountId,
            @Param("afterId") Long afterId,
            Pageable pageable
    );
    Optional<Transaction> findByPaymentId(Long paymentId);
}
