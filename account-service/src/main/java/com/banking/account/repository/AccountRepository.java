package com.banking.account.repository;

import com.banking.account.entity.Account;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Slice<Account> findAllByOrderByIdAsc(Pageable pageable);
    Slice<Account> findByIdGreaterThanOrderByIdAsc(Long afterId, Pageable pageable);
}
