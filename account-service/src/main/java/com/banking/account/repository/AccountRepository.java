package com.banking.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.banking.account.entity.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {}
