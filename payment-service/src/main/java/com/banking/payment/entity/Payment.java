package com.banking.payment.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_payment_idempotency_key_hash",
        columnNames = "idempotency_key_hash"
))
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "idempotency_key_hash", length = 64)
    private String idempotencyKeyHash;
    private Long fromAccount;
    private Long toAccount;
    private BigDecimal amount;
    private String status;
    public Payment(){}
    // getters/setters
    public Long getId(){return id;}
    public void setId(Long id){this.id=id;}
    @JsonIgnore
    public String getIdempotencyKeyHash(){return idempotencyKeyHash;}
    public void setIdempotencyKeyHash(String hash){this.idempotencyKeyHash=hash;}
    public Long getFromAccount(){return fromAccount;}
    public void setFromAccount(Long f){this.fromAccount=f;}
    public Long getToAccount(){return toAccount;}
    public void setToAccount(Long t){this.toAccount=t;}
    public BigDecimal getAmount(){return amount;}
    public void setAmount(BigDecimal a){this.amount=a;}
    public String getStatus(){return status;}
    public void setStatus(String s){this.status=s;}
}
