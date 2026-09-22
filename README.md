# Saga Orchestrator（本地可运行的分布式事务编排）

基于 Spring Boot 3 / Java 21 的**进程内 Saga 编排器**：用本地模拟参与方替代真实下游，
实现「顺序正向执行 + 失败逆序补偿」，并在**重复提交、超时重试、并发到达**下保证结果
要么全部生效、要么全部回退——不允许部分生效、重复生效或漏补偿。

> 所有状态与“下游”都在单进程内模拟（内存仓储 + 模拟参与方），`mvn test` 即可完整验证，
> 无需数据库 / 消息队列 / 真实第三方服务。

---

## 1. 编排协议

### 1.1 下单 Saga 定义（`core/OrderSagaFactory`）

| 顺序 | 步骤 | 参与方 | 正向动作 | 补偿动作 |
|---|---|---|---|---|
| 1 | reserve-stock | inventory | `reserveStock`（预留库存） | `releaseStock`（释放库存） |
| 2 | charge-payment | payment | `charge`（扣款） | `refund`（退款） |
| 3 | arrange-shipping | shipping | `arrangeShipment`（安排发货） | `cancelShipment`（取消发货） |

正向按上表顺序逐步执行；**任一步明确失败后，从失败点的前一步开始逆序补偿**，
失败步骤本身未生效，不补偿。

### 1.2 编排状态机（单调推进，终态不可再执行）

```
PENDING ──► RUNNING ──► COMPLETED            （全部成功，事务生效）
                 │
                 └────► COMPENSATING ──► COMPENSATED          （全部回退，无残留）
                                        └► COMPENSATION_FAILED （补偿失败，需人工介入）
```

- `COMPLETED / COMPENSATED / COMPENSATION_FAILED` 为**终态**；对终态编排再次提交/驱动，
  直接返回，不产生任何执行或补偿流水（`SagaOrchestrator` 在实例锁内校验终态）。
- 非法的状态迁移直接抛 `IllegalStateException`，状态不可能倒退。

### 1.3 一次执行的生命周期（`SagaService.submit`）

1. **原子受理去重**：仓储 `createIfAbsent` 基于 `ConcurrentHashMap.computeIfAbsent`，
   同一 `requestId` 在 JVM 内只有一个 `SagaInstance`，并发提交只有一个胜出。
2. **串行驱动**：`SagaOrchestrator.drive` 以实例对象为锁；并发/重复提交的其他线程
   要么等待首个执行完成后看到终态直接返回，要么被幂等键拦截。
3. **逐步执行**：每步经 `ResilientCaller` 施加「熔断 → （超时/重试由参与方模拟与包装）」保护。
4. **失败补偿**：逆序补偿所有“已生效”步骤；补偿同样走弹性包装。
5. **全程流水**：每个关键动作追加一条带单调序号的 `HistoryEvent`（事件类型 + 步骤 + 判定依据），
   查询接口原样返回，状态可解释。

---

## 2. 幂等与补偿规则

### 2.1 幂等键与去重位置（两道防线）

| 防线 | 位置 | 保证 |
|---|---|---|
| 入口去重 | `InMemorySagaRepository.createIfAbsent` | 同一 requestId 只有一个编排实例 |
| 参与方去重 | `AbstractSimulatedParticipant` 的 `appliedKeys` | 同一 `(requestId, 方向, 操作)` 的正向/补偿动作只**落地**一次 |

- 失败的调用（超时/业务拒绝/熔断）**永远不会**写入“已生效”集合，也不会记入副作用账本，
  因此重试与重放安全。
- 补偿动作与正向动作使用**同一套幂等机制**，补偿本身幂等：回滚完成后再重复提交同一请求，
  补偿也不会重复执行（见 `FailureRollbackTest.compensationIsIdempotent...`）。

### 2.2 补偿规则

- 仅 `EXECUTED` 的步骤才会被补偿；`NOT_STARTED / FAILED`（未生效）不补偿。
- 补偿按**逆序**执行，任一补偿失败 → 编排进入 `COMPENSATION_FAILED` 终态并在流水中标记
  “需人工介入，禁止遗漏补偿”，绝不静默吞掉。
- 补偿调用同样受有界重试保护（瞬时失败会重试）；`BUSINESS / CIRCUIT_OPEN` 不重试。

### 2.3 查询语义

`GET /api/sagas/{requestId}` 返回同一实例的**单调**状态、每步状态与完整流水；
终态之后结果永远不变。`duplicated` 字段标识该请求是否曾被重复受理。

---

## 3. 失败分类、重试与熔断

### 3.1 失败分类（`core/FailureType`，失败原因可区分）

| 分类 | 含义 | 是否重试 |
|---|---|---|
| `BUSINESS` | 不可重试的业务失败（如余额不足、业务拒绝） | **否**，立即失败并补偿 |
| `TIMEOUT` | 下游超时（参与方睡眠后抛出） | 是 |
| `TRANSIENT` | 其他瞬时故障（连接抖动等） | 是 |
| `RETRIES_EXHAUSTED` | 有界重试耗尽后仍失败（最终分类） | — |
| `CIRCUIT_OPEN` | 熔断 OPEN 期间快速失败，未触达下游 | 否 |

> 步骤状态中保留最终分类（如持续超时最终记为 `RETRIES_EXHAUSTED`，业务拒绝记为 `BUSINESS`），
> 查询/日志中可直接区分失败原因。

### 3.2 重试（`core/ResilientCaller`）

- 仅对 `TIMEOUT / TRANSIENT` 做有界重试，固定退避；`BUSINESS / CIRCUIT_OPEN` 立即上抛。
- 每次尝试、重试等待、最终耗尽都有 WARN 日志，含 requestId、参与方、操作、尝试次数。

### 3.3 熔断（`core/CircuitBreaker`，参与方维度）

- **CLOSED**：连续失败达到阈值 → 转 **OPEN**；期间任一成功清零计数。
- **OPEN**：在打开窗口内 `allowRequest()=false`，调用方不触达下游、立即快速失败
  （`CIRCUIT_OPEN`）；窗口超时后惰性转 **HALF_OPEN** 放行一次试探。
- **HALF_OPEN**：连续成功达到阈值 → 回 **CLOSED**（故障自动恢复）；任意失败立即回 OPEN。

### 3.4 配置项（`src/main/resources/application.properties`，前缀 `saga.resilience`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `saga.resilience.max-attempts` | `3` | 单次下游调用最大尝试次数（含首次）；`<=1` 不重试 |
| `saga.resilience.backoff-millis` | `50` | 可重试失败两次尝试之间的固定退避（毫秒） |
| `saga.resilience.circuit-failure-threshold` | `3` | 连续失败多少次开启熔断 |
| `saga.resilience.circuit-open-millis` | `2000` | 熔断 OPEN 保持时长（毫秒），之后进入 HALF_OPEN |
| `saga.resilience.circuit-success-threshold` | `1` | HALF_OPEN 连续成功多少次后回到 CLOSED |

---

## 4. HTTP 接口（本地启动后可手动验证）

启动：

```bash
mvn spring-boot:run
```

提交（同一 `requestId` 重复 POST 是幂等的，返回同一终态）：

```bash
curl -s -X POST http://localhost:8080/api/sagas \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-001","businessType":"ORDER_PLACEMENT"}'
```

查询单调状态与流水：

```bash
curl -s http://localhost:8080/api/sagas/demo-001
```

响应包含：`requestId / status / steps(每步状态与失败分类) / history(带序号的判定流水) / duplicated`。

---

## 5. 本地验证方法

```bash
# 运行全部测试（11 个，含 4 大必测场景 + 基线 + HTTP 冒烟）
mvn test

# 只看某个场景
mvn test -Dtest=DuplicateSubmitTest     # 重复提交（顺序/8线程并发/终态重放）
mvn test -Dtest=TimeoutRetryTest        # 首次超时重试成功 / 持续超时耗尽后补偿
mvn test -Dtest=FailureRollbackTest     # 业务失败立即回滚 / 补偿幂等
mvn test -Dtest=CircuitBreakerTest      # 熔断开启快速失败 + 自动恢复
```

测试通过 `SideEffectLedger`（只记录**真正落地**的副作用）断言：

- **无重复**：任意 `(请求, 参与方, 方向)` 的副作用次数恒为 0 或 1，并发下亦然；
- **无丢失**：成功路径每步 EXECUTE 恰好 1 次；失败路径所有已生效步骤 COMPENSATE 恰好 1 次；
- **失败原因可区分**：断言步骤的 `FailureType`（`BUSINESS` / `RETRIES_EXHAUSTED` / `CIRCUIT_OPEN`）。

每个场景都会在测试日志（`System.out`）打印：**请求标识、编排终态、参与方执行/补偿流水、
编排判定流水、各步骤状态**，格式见 `tests/TestSupport.printReport`。

| 测试类 | 覆盖场景 |
|---|---|
| `HappyPathTest` | 三步全部成功，无补偿 |
| `DuplicateSubmitTest` | 顺序重复提交、8 线程并发提交、终态后再次驱动 |
| `TimeoutRetryTest` | 首次超时第 2 次成功；持续超时重试耗尽 → 补偿 |
| `FailureRollbackTest` | 不可重试业务失败立即回滚（不重试）；回滚后重放补偿幂等 |
| `CircuitBreakerTest` | 达阈值熔断 OPEN、OPEN 期间快速失败、恢复后 HALF_OPEN 试探成功 |
| `SagaControllerSmokeTest` | HTTP 提交/重复提交/查询/404 |

---

## 6. 代码结构

```
core/
  SagaStatus / StepStatus / FailureType   生命周期与失败分类枚举
  SagaStep / SagaDefinition / SagaContext 步骤与 Saga 定义
  SagaInstance / StepState / HistoryEvent 实例状态机、步骤状态、判定流水
  SagaRepository / InMemorySagaRepository 原子去重的内存仓储
  RetryPolicy / CircuitBreakerConfig / CircuitBreaker  重试策略与熔断器
  ResilientCaller                         超时判定/有界重试/熔断包装
  SagaOrchestrator                        正向执行 + 逆序补偿 + 终态保护
  SagaService / OrderSagaFactory          幂等受理与下单 Saga 定义
participant/
  ParticipantClient(接口, core 中) / AbstractSimulatedParticipant  幂等+故障注入+记账
  Inventory/Payment/ShippingParticipant   三个本地模拟参与方
  FaultPlan                               故障注入（第N次超时/业务失败/持续失败）
  SideEffectLedger / LedgerEntry          副作用账本（测试断言依据）
web/                REST 控制器、DTO、全局异常处理
config/             saga.resilience.* 配置属性绑定
tests/              场景测试与统一日志打印
```
