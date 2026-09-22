package com.github.h3yow.sagaorchestratorjava.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 单次编排执行过程中的共享上下文，供步骤间传递数据。
 */
public class SagaContext {

    private final Map<String, Object> attributes = new HashMap<>();

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    public Object get(String key) {
        return attributes.get(key);
    }
}
