package com.github.h3yow.sagaorchestratorjava.web;

import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编排查询接口：按请求标识读取一致且单调推进的状态（终结后即为最终重放结果）。
 */
@RestController
@RequestMapping("/sagas")
public class SagaController {

    private final SagaOrchestrator orchestrator;

    public SagaController(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<SagaView> get(@PathVariable String requestId) {
        return orchestrator.get(requestId)
                .map(s -> ResponseEntity.ok(SagaView.from(s)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
