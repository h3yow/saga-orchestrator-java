# Saga Orchestrator（本地可运行的分布式事务编排）

一个基于 Spring Boot 的**进程内编排式 Saga**：用本地模拟的下游参与方替代真实远程服务，
演示跨多个参与方的正向执行、失败逆序补偿，并在**重试、重放与并发**下保持结果一致且可解释。

- 最终结果只有两种业务结局：**全部生效（SUCCEEDED）** 或 **全部回退（COMPENSATED）**；
  补偿本身无法完成时为 **COMPENSATION_FAILED**，流水可精确定位补偿缺口。
- 不允许部分生效后不了了之、不允许重复生效、不允许漏补偿。

---

## 1. 快速开始

```bash
# 运行全部测试（覆盖需求中的四类场景）
mvn test

# 启动应用
mvn spring-boot:run

# 另开终端：提交一次下单编排（inventory 扣库存 -> payment 扣支付 -> shipping 安排发货）
curl -X POST http://localhost:8080/demo/orders/REQ-001

# 按请求标识查询单调推进的状态（终结后即为最终重放结果）
curl http://localhost:8080/sagas/REQ-001

# 重复提交同一 requestId：返回完全一致的结果，编排不会再次执行
curl -X POST http://localhost:8080/demo/orders/REQ-001
```

返回示例（状态 + 每步执行/补偿尝试次数与失败原因）：

```json
{
  "requestId": "REQ-001", "sagaType": "order-creation", "status": "SUCCEEDED",
  "failedStep": null, "failureReason": null,
  "steps": [
    {"name": "deduct-inventory",   "status": "SUCCEEDED", "forwardAttempts": 1, "compensateAttempts": 0, "failureReason": null},
    {"name": "charge-payment",     "status": "SUCCEEDED", "forwardAttempts": 1, "compensateAttempts": 0, "failureReason": null},
    {"name": "arrange-shipping",   "status": "SUCCEEDED", "forwardAttempts": 1, "compensateAttempts": 0, "failureReason": null}
  ]
}
```

控制台会同时打印请求标识、执行/补偿流水（`[SAGA]` / `[PARTICIPANT]` / `[LEDGER]`）与熔断判定（`[CIRCUIT]`）。

---

## 2. 编排协议（Orchestration Saga）

`SagaDefinition` 是一个**有序步骤序列**，每步绑定一个参与方（`SagaStep(name, participant)`）。
`SagaOrchestrator.submit(requestId, definition, context)` 的协议如下：

1. **正向执行**：严格按步骤顺序调用 `participant.forward(idempotencyKey, ctx)`。
2. **任一步彻底失败**后立即停止正向流程：
   - 可重试瞬时故障（超时等）在重试预算内重试用尽；
   - 不可重试业务失败立即终止（不重试）；
   - 熔断开启直接快速失败。
3. **逆序补偿**：从失败步的前一步开始，按严格逆序调用已生效步骤的
   `participant.compensate(idempotencyKey, ctx)`；失败步及其后未到达的步骤标记
   `COMPENSATION_SKIPPED`（显式声明“从未生效、无需补偿”，避免漏补偿歧义）。
4. **终结状态机（单调、不可逆）**：

   ```
   RUNNING ──全部成功──────────────▶ SUCCEEDED
           └─失败且全部补偿完成────▶ COMPENSATED
           └─失败但某步补偿也失败──▶ COMPENSATION_FAILED（在缺口处停止，原因可定位）
   ```

   所有状态只能前进，已终结实例永远不会被再次执行。

### 幂等键

- 每步正向动作的幂等键为 `requestId:step-<序号>`；补偿与正向**同一业务键配对**，
  在台账中按方向独立去重。
- 因此“同一步的重试”命中同一 key，只生效一次；补偿的重试也只生效一次。

---

## 3. 幂等、重放与并发规则

| 风险 | 机制 |
|------|------|
| 重复提交 / 超时重试 / HTTP 重放 | 以 `requestId` 为幂等键；`SagaRepository` 为每个 requestId 维护一把锁，`getOrCreate` 在锁内判定并创建，重复提交直接返回同一实例，**编排只执行一次**。已终结实例重放只读结果，不再触发任何 forward/compensate。 |
| 同请求并发到达 | 线程在 per-requestId 锁上排队：首线程执行，其余线程随后读到同一最终实例（测试中 16 并发验证）。 |
| 下游物理重试导致重复副作用 | 模拟参与方内置 `ParticipantLedger` 幂等表：`recordForwardIfAbsent / recordCompensationIfAbsent` 按 key 去重，重复物理调用不生效但会打印 `DUPLICATE` 日志。这模拟了真实下游必须具备的幂等能力。 |
| 补偿重复 | 补偿在台账中独立去重；且只有“正向确认生效”的步骤才会被补偿，未生效直接 SKIP。 |
| 状态查询不一致 | 实例状态为 volatile、计数为原子量；`GET /sagas/{requestId}` 任意时刻读到单调推进中的一致快照，终结后恒定。 |

---

## 4. 失败分类、超时、重试与熔断

`ResilienceCaller` 为每次物理调用叠加三道防护，顺序为
**熔断准入 → 单次超时 → 有界重试**（正向与补偿都受保护）。

### 4.1 失败必须区分对待

- `RETRYABLE`（`RetryableException`）：超时、瞬时错误 —— 允许在预算内重试。
- `NON_RETRYABLE`（`NonRetryableException`）：业务失败（如余额不足）—— **立即失败，不重试**，
  直接进入补偿。失败原因保留业务信息（如 `INSUFFICIENT_BALANCE`）。
- `CircuitBreakerOpenException`：熔断 OPEN 期间快速失败，不发起真实调用、不阻塞线程。

### 4.2 超时

调用提交到独立守护线程池，以 `future.get(timeoutMs)` 有界等待；超时即 `cancel(true)`
中断执行线程（模拟参与方响应中断），并归类为 RETRYABLE。

### 4.3 有界重试

仅对 RETRYABLE 生效，最多 `max-attempts` 次（含首次），退避为指数退避
`retry-backoff-ms * 2^(attempt-1)`（封顶 10 倍基数）。重试期间熔断一旦开启则立即抛出、不再等待。

### 4.4 熔断（按参与方维度隔离）

```
CLOSED  连续失败达到 failure-threshold ──▶ OPEN
OPEN    直接快速失败；经过 open-state-duration-ms 后放行一次探测 ──▶ HALF_OPEN
HALF_OPEN 探测成功 ──▶ CLOSED（计数清零，自动恢复）；探测失败 ──▶ OPEN（重新计时）
```

---

## 5. 配置项（`src/main/resources/application.properties`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `saga.resilience.timeout-ms` | `200` | 单次下游调用超时（毫秒），超时计为 RETRYABLE。 |
| `saga.resilience.max-attempts` | `3` | 最大尝试次数（含首次）；仅对 RETRYABLE 生效。 |
| `saga.resilience.retry-backoff-ms` | `20` | 指数退避基数（毫秒），封顶 10 倍。 |
| `saga.resilience.failure-threshold` | `5` | 同一参与方连续失败多少次后熔断 OPEN。 |
| `saga.resilience.open-state-duration-ms` | `1000` | OPEN 持续时间，到期进入 HALF_OPEN 探测。 |

---

## 6. 代码结构

```
saga/          编排核心
  SagaOrchestrator      顺序执行 + 逆序补偿 + 幂等/并发控制 + 流水日志
  SagaDefinition/Step   步骤定义
  SagaInstance/Record   实例与单步流水（单调状态机、原子计数）
  SagaRepository        进程内存储 + per-requestId 锁（幂等去重、并发串行）
  SagaContext           步骤间上下文
resilience/    韧性层
  ResilienceCaller      超时 + 有界重试 + 熔断联动（forward/compensate 共用）
  CircuitBreaker        CLOSED/OPEN/HALF_OPEN 状态机（可注入可控时钟）
  ParticipantException  及 Retryable/NonRetryable/CircuitBreakerOpen 分类
participant/   Participant 接口 + ParticipantLedger 幂等台账与生效流水
mock/          MockParticipant（OK/SLOW/FLAKY/ALWAYS_FAIL/BUSINESS_FAIL 剧本）
demo/          下单三步 Saga 装配与 HTTP 演示接口
web/           GET /sagas/{requestId} 状态查询
```

---

## 7. 测试场景与本地验证

测试位于 `src/test/.../tests`，每个用例都会在日志打印**请求标识、执行/补偿流水与判定依据**：

| 测试类 | 场景 | 关键断言 |
|--------|------|----------|
| `SagaHappyPathTest` | 全部参与方正常 | SUCCEEDED；三步各生效一次、无补偿 |
| `SagaIdempotentReplayTest` | 串行重复提交 + 16 并发重复提交 | 返回同一实例；每步只执行一次；台账无重复、无丢失 |
| `SagaTimeoutRollbackTest` | 支付持续慢调用超时 | 恰好 3 次物理尝试；原因为 timeout（RETRYABLE）；逆序只补偿库存，支付/发货 SKIPPED |
| `SagaPersistentFailureRollbackTest` | 发货持续瞬时失败 | 3 次尝试后逆序补偿支付、库存；重放不产生第二次补偿 |
| `SagaBusinessFailureTest` | 支付业务失败（余额不足） | NON_RETRYABLE，**只物理调用 1 次**（不重试），立即回滚 |
| `CircuitBreakerTest` | 熔断开启/快速失败/半开探测/恢复/参与方隔离 | OPEN 后零物理调用；HALF_OPEN 单次探测；成功恢复 CLOSED（可控时钟） |
| `SagaRecoveryAndIntegrationTest` | 瞬时故障重试内恢复；熔断与编排联动 | FLAKY 3 次物理调用仅 1 次生效；熔断开启后新订单支付步零物理调用、原因为 circuit breaker is OPEN |
| `SagaCompensationFailureTest` | 补偿本身持续失败 | COMPENSATION_FAILED；缺口定位到具体步骤；补偿也有界重试；终结后不偷偷补试 |

运行：

```bash
mvn test                      # 全量
mvn test -Dtest=CircuitBreakerTest   # 单个场景
```

判定“无重复、无丢失”的依据是 `ParticipantLedger` 的生效流水：
`forwardEntries()` / `compensateEntries()` 中每个 `participant#requestId:step-n`
有且仅有一条，并按生效顺序排列（补偿严格逆序）。

---

## 8. 从演示走向生产的说明

本工程用**进程内存储与模拟参与方**保证本地零依赖即可运行。真实落地时通常替换：

- `SagaRepository` → 持久化存储（实例与步骤流水落库，状态推进用乐观锁/唯一约束）；
- `ParticipantLedger` 的幂等职责 → 各真实下游自身的幂等接口（业务幂等键透传）；
- 超时执行线程 → 异步 RPC 客户端的原生超时；
- `COMPENSATION_FAILED` → 异步补偿重试队列/人工介入告警（当前实现停在缺口并暴露状态）。
