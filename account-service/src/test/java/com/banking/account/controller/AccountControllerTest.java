package com.banking.account.controller;

import com.banking.account.api.CreateAccountRequest;
import com.banking.account.entity.Account;
import com.banking.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

    @Test
    void listAccounts_defaultsToFirstBoundedPageAndAdvertisesNextCursor() throws Exception {
        when(accountService.findAll(null, AccountService.DEFAULT_LIMIT)).thenReturn(
                new SliceImpl<>(
                        List.of(savedAccount()),
                        PageRequest.of(0, AccountService.DEFAULT_LIMIT),
                        true
                )
        );

        mockMvc.perform(get("/accounts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(
                        "Link",
                        "</accounts?afterId=42&limit=50>; rel=\"next\""
                ))
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].customerName").value("Asha Mehta"))
                .andExpect(jsonPath("$[0].balance").value(5000.00));

        verify(accountService).findAll(null, AccountService.DEFAULT_LIMIT);
    }

    @Test
    void listAccounts_usesCustomCursorAndOmitsLinkForFinalPage() throws Exception {
        when(accountService.findAll(40L, 2)).thenReturn(
                new SliceImpl<>(List.of(savedAccount()), PageRequest.of(0, 2), false)
        );

        mockMvc.perform(get("/accounts")
                .param("afterId", "40")
                .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Link"))
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].customerName").value("Asha Mehta"))
                .andExpect(jsonPath("$[0].balance").value(5000.00));

        verify(accountService).findAll(40L, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?afterId=0",
            "?afterId=-1",
            "?afterId=not-a-number",
            "?limit=0",
            "?limit=-1",
            "?limit=101",
            "?limit=not-a-number"
    })
    void listAccounts_invalidPaginationReturnsSafeProblemWithoutServiceCall(String query)
            throws Exception {
        mockMvc.perform(get("/accounts" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.type")
                        .value("urn:finbank:problem:validation-failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.detail")
                        .value("One or more request values are invalid."))
                .andExpect(jsonPath("$.instance").value("/accounts"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.rejectedValue").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());

        verifyNoInteractions(accountService);
    }

    @Test
    void createAccount_validRequest_delegatesValidatedInputToService() throws Exception {
        when(accountService.create(any(CreateAccountRequest.class))).thenReturn(savedAccount());

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerName": "Asha Mehta",
                                  "balance": 5000.00
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.customerName").value("Asha Mehta"))
                .andExpect(jsonPath("$.balance").value(5000.00));

        ArgumentCaptor<CreateAccountRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateAccountRequest.class);
        verify(accountService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().customerName()).isEqualTo("Asha Mehta");
        assertThat(requestCaptor.getValue().balance()).isEqualByComparingTo("5000.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"balance\":5000.00}",
            "{\"customerName\":\"\",\"balance\":5000.00}",
            "{\"customerName\":\"   \",\"balance\":5000.00}"
    })
    void createAccount_missingOrBlankName_returnsBadRequestWithoutSideEffects(String request) throws Exception {
        assertBadRequestWithoutSideEffects(request);
    }

    @Test
    void createAccount_missingBalance_returnsBadRequestWithoutSideEffects() throws Exception {
        assertBadRequestWithoutSideEffects("{\"customerName\":\"Asha Mehta\"}");
    }

    @Test
    void createAccount_negativeBalance_returnsBadRequestWithoutSideEffects() throws Exception {
        assertBadRequestWithoutSideEffects("""
                {
                  "customerName": "Asha Mehta",
                  "balance": -0.01
                }
                """);
    }

    @Test
    void createAccount_malformedJsonReturnsSafeProblemWithoutSideEffects() throws Exception {
        assertBadRequestWithoutSideEffects("{\"customerName\":\"Demo Customer\",\"balance\":");
    }

    @Test
    void createAccount_clientCannotOverrideServerOwnedId() throws Exception {
        when(accountService.create(any(CreateAccountRequest.class))).thenReturn(savedAccount());

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 999,
                                  "customerName": "Asha Mehta",
                                  "balance": 5000.00
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42));

        ArgumentCaptor<CreateAccountRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateAccountRequest.class);
        verify(accountService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().customerName()).isEqualTo("Asha Mehta");
        assertThat(requestCaptor.getValue().balance()).isEqualByComparingTo("5000.00");
    }

    private void assertBadRequestWithoutSideEffects(String request) throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON
                ))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.type")
                        .value("urn:finbank:problem:validation-failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.detail")
                        .value("One or more request values are invalid."))
                .andExpect(jsonPath("$.instance").value("/accounts"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.field").doesNotExist())
                .andExpect(jsonPath("$.rejectedValue").doesNotExist())
                .andExpect(jsonPath("$.customerName").doesNotExist())
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());

        verifyNoInteractions(accountService);
    }

    private Account savedAccount() {
        Account account = new Account("Asha Mehta", new BigDecimal("5000.00"));
        account.setId(42L);
        return account;
    }
}
