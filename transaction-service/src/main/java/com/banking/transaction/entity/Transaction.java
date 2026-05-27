package com.banking.transaction.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long fromAccount;
    private Long toAccount;
    private BigDecimal amount;
    private Instant createdAt;
    private String status;
    public Transaction(){ this.createdAt = Instant.now(); }
    // getters/setters
    public Long getId(){return id;} public void setId(Long i){this.id=i;}
    public Long getFromAccount(){return fromAccount;} public void setFromAccount(Long f){this.fromAccount=f;}
    public Long getToAccount(){return toAccount;} public void setToAccount(Long t){this.toAccount=t;}
    public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal a){this.amount=a;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant i){this.createdAt=i;}
    public String getStatus(){return status;} public void setStatus(String s){this.status=s;}
}
