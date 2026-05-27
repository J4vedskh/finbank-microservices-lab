package com.banking.payment.controller;

import com.banking.payment.entity.Payment;
import com.banking.payment.repository.PaymentRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentRepository repo;
    private final KafkaTemplate<String,String> kafka;
    public PaymentController(PaymentRepository repo, KafkaTemplate<String,String> kafka){
        this.repo=repo; this.kafka=kafka;
    }

    @GetMapping public List<Payment> all(){ return repo.findAll(); }

    @PostMapping
    public Payment create(@RequestBody Payment p){
        p.setStatus("CREATED");
        Payment saved = repo.save(p);
        // publish simple event
        kafka.send("payments", saved.getId()+"|"+saved.getFromAccount()+"|"+saved.getToAccount()+"|"+saved.getAmount());
        return saved;
    }
}
