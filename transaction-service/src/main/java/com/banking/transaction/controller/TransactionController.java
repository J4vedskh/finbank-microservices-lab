package com.banking.transaction.controller;

import com.banking.transaction.entity.Transaction;
import com.banking.transaction.repository.TransactionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/transactions")
public class TransactionController {
    private final TransactionRepository repo;
    public TransactionController(TransactionRepository repo){this.repo=repo;}

    @GetMapping public List<Transaction> all(){ return repo.findAll(); }

    @GetMapping("/account/{id}") public List<Transaction> byAccount(@PathVariable Long id){
        return repo.findByFromAccountOrToAccount(id,id);
    }

    @KafkaListener(topics = "payments", groupId = "transaction-group")
    public void consume(String msg){
        // simple parser: id|from|to|amount
        try{
            String[] parts = msg.split("\\|");
            Transaction t = new Transaction();
            t.setFromAccount(Long.parseLong(parts[1]));
            t.setToAccount(Long.parseLong(parts[2]));
            t.setAmount(new java.math.BigDecimal(parts[3]));
            t.setStatus("COMPLETED");
            repo.save(t);
        }catch(Exception e){
            // ignore minimal example
        }
    }
}
