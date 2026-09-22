package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.InMemorySagaRepository;
import com.github.h3yow.sagaorchestratorjava.core.ResilientCaller;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 冒烟：POST 提交、POST 重复提交（duplicated=true，状态一致）、GET 查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SagaControllerSmokeTest {

    @Autowired MockMvc mvc;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    @BeforeEach
    void reset() {
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);
    }

    @Test
    void submitIsIdempotentAndQueryableOverHttp() throws Exception {
        String body = "{\"requestId\":\"http-001\",\"businessType\":\"ORDER_PLACEMENT\"}";

        mvc.perform(post("/api/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("http-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.duplicated").value(false));

        // 重复提交：同一终态，duplicated 标记为 true
        mvc.perform(post("/api/sagas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.duplicated").value(true));

        mvc.perform(get("/api/sagas/http-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.history.length()").value(org.hamcrest.Matchers.greaterThan(3)));

        mvc.perform(get("/api/sagas/not-exist"))
                .andExpect(status().isNotFound());
    }
}
