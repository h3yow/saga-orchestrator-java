package com.github.h3yow.sagaorchestratorjava.saga;

import java.util.HashMap;
import java.util.Map;

/**
 * 一次编排的上下文：在各步之间传递数据（如前置参与方产生的业务单号）。
 * 每个 requestId 独占一个实例，编排执行按顺序进行，普通 HashMap 即可。
 */
public class SagaContext {

    private final String requestId;
    private final Map<String, Object> attributes = new HashMap<>();

    public SagaContext(String requestId) {
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(attributes);
    }
}
