package com.banking.account.controller;

import com.banking.account.api.CreateAccountRequest;
import com.banking.account.api.AccountResponse;
import com.banking.account.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> all(
            @RequestParam(required = false) @Positive Long afterId,
            @RequestParam(defaultValue = "50")
            @Min(1) @Max(AccountService.MAX_LIMIT) int limit
    ) {
        Slice<AccountResponse> accounts = accountService.findAll(afterId, limit)
                .map(AccountResponse::from);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (accounts.hasNext() && !accounts.isEmpty()) {
            Long lastReturnedId = accounts.getContent()
                    .get(accounts.getNumberOfElements() - 1)
                    .id();
            response.header(
                    HttpHeaders.LINK,
                    "</accounts?afterId=" + lastReturnedId + "&limit=" + limit + ">; rel=\"next\""
            );
        }
        return response.body(accounts.getContent());
    }

    @PostMapping
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accountService.create(request));
    }
}
