package com.github.h3yow.sagaorchestratorjava.web;

import com.github.h3yow.sagaorchestratorjava.core.HistoryEvent;
import com.github.h3yow.sagaorchestratorjava.core.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.core.SagaService;
import com.github.h3yow.sagaorchestratorjava.core.StepState;
import com.github.h3yow.sagaorchestratorjava.web.dto.SagaView;
import com.github.h3yow.sagaorchestratorjava.web.dto.StepView;
import com.github.h3yow.sagaorchestratorjava.web.dto.SubmitRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 编排 REST 接口：
 * <ul>
 *   <li>POST /api/sagas：提交（同一 requestId 重复提交幂等，返回同一终态）</li>
 *   <li>GET  /api/sagas/{requestId}：查询单调状态与流水</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/sagas")
public class SagaController {

    private final SagaService sagaService;

    public SagaController(SagaService sagaService) {
        this.sagaService = sagaService;
    }

    @PostMapping
    public ResponseEntity<SagaView> submit(@RequestBody SubmitRequest request) {
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        SagaInstance instance = sagaService.submit(request.requestId(), request.businessType());
        return ResponseEntity.ok(toView(instance));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<SagaView> query(@PathVariable String requestId) {
        return sagaService.query(requestId)
                .map(instance -> ResponseEntity.ok(toView(instance)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private SagaView toView(SagaInstance instance) {
        List<StepView> stepViews = instance.steps().stream()
                .map(this::toStepView)
                .toList();
        List<String> historyViews = instance.history().stream()
                .map(SagaController::formatEvent)
                .toList();
        boolean duplicated = instance.history().stream()
                .anyMatch(e -> "SUBMIT_DEDUPED".equals(e.eventType()));
        return new SagaView(instance.requestId(), instance.status().name(),
                stepViews, historyViews, duplicated);
    }

    private StepView toStepView(StepState state) {
        return new StepView(state.stepName(), state.status().name(),
                state.failureType() == null ? null : state.failureType().name(),
                state.rootCauseType() == null ? null : state.rootCauseType().name(),
                state.failureMessage());
    }

    private static String formatEvent(HistoryEvent e) {
        String step = e.stepName() == null ? "-" : e.stepName();
        return "#" + e.seq() + " [" + e.eventType() + "] step=" + step
                + (e.detail() == null ? "" : " | " + e.detail());
    }
}
