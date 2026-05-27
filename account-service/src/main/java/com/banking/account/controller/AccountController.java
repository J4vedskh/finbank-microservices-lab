package com.banking.account.controller;

import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {
    private final AccountRepository repo;
    public AccountController(AccountRepository repo){this.repo=repo;}

    @GetMapping
    public List<Account> all(){ return repo.findAll(); }

    @PostMapping
    public Account create(@RequestBody Account a){ return repo.save(a); }
}
