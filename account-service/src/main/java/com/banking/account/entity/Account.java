package com.banking.account.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String customerName;
    private BigDecimal balance;

    public Account() {}

    public Account(String customerName, BigDecimal balance) {
        this.customerName = customerName;
        this.balance = balance;
    }

    // getters and setters
    public Long getId(){return id;}
    public void setId(Long id){this.id=id;}
    public String getCustomerName(){return customerName;}
    public void setCustomerName(String s){this.customerName=s;}
    public BigDecimal getBalance(){return balance;}
    public void setBalance(BigDecimal b){this.balance=b;}
}
