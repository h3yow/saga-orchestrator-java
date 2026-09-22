package com.github.h3yow.sagaorchestratorjava.demo;

import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.web.SagaView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地演示：POST /demo/orders/{requestId} 触发一次下单编排，
 * 随后可用 GET /sagas/{requestId} 查询单调推进的最终状态。
 * 同一个 requestId 重复 POST 不会重复执行（重放返回同一结果）。
 */
@RestController
@RequestMapping("/demo/orders")
public class OrderDemoController {

    private final OrderDemoService service;

    public OrderDemoController(OrderDemoService service) {
        this.service = service;
    }

    @PostMapping("/{requestId}")
    public ResponseEntity<SagaView> submit(@PathVariable String requestId) {
        SagaInstance instance = service.submitOrder(requestId);
        return ResponseEntity.ok(SagaView.from(instance));
    }
}
