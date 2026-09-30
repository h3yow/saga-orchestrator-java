# Saga Orchestrator（本地可运行的分布式事务编排）

基于 Spring Boot 3 / Java 21 的**进程内 Saga 编排器**：用本地模拟参与方替代真实下游，
实现「多业务流程 + 顺序正向执行 + 失败逆序补偿」，支持**参与方自报超时与编排侧硬超时两类超时**，
并在**重复提交、混合业务并发到达、下游卡死**下保证结果
要么全部生效、要么全部回退——不允许部分生效、重复生效或漏补偿。

> 所有状态与“下游”都在单进程内模拟（内存仓储 + 模拟参与方），`mvn test` 即可完整验证，
> 无需数据库 / 消息队列 / 真实第三方服务。

---

## 1. 编排协议

### 1.0 支持的业务流程与按类型选择（`core/SagaDefinitionRegistry`）

系统内可注册任意多种业务流程，每种流程由一个 `SagaDefinitionFactory` 描述自己的
**业务类型标识（businessType）、步骤数、参与方组合**；Spring 启动时全部工厂注入注册表，
重复注册同一类型会直接报错，避免两个流程静默抢同一标识。

| businessType | 流程 | 步数 | 参与方 |
|---|---|---|---|
| `ORDER_PLACEMENT` | 下单（既有流程） | 3 | inventory / payment / shipping |
| `TRAVEL_BOOKING` | 差旅预订（新增） | 4 | flight / hotel / visa / loyalty |

- 提交时按 `businessType` 选中对应流程；两种流程可以**同时跑、互不影响**：参与方完全不交叉，
  熔断器按参与方维度隔离，实例/流水/幂等键都按 requestId 隔离。
- **未注册的业务类型一律明确拒绝**（HTTP 400，错误信息带已注册类型列表），不会默默套用默认流程；
  请求里 `businessType` 留空时，为兼容历史提交契约仍显式选用 `ORDER_PLACEMENT`。

### 1.1 下单 Saga 定义（`core/OrderSagaFactory`）

| 顺序 | 步骤 | 参与方 | 正向动作 | 补偿动作 |
|---|---|---|---|---|
| 1 | reserve-stock | inventory | `reserveStock`（预留库存） | `releaseStock`（释放库存） |
| 2 | charge-payment | payment | `charge`（扣款） | `refund`（退款） |
| 3 | arrange-shipping | shipping | `arrangeShipment`（安排发货） | `cancelShipment`（取消发货） |

正向按上表顺序逐步执行；**任一步明确失败后，从失败点的前一步开始逆序补偿**，
失败步骤本身未生效，不补偿。

### 1.1b 差旅预订 Saga 定义（`core/TravelSagaFactory`，四步）

| 顺序 | 步骤 | 参与方 | 正向动作 | 补偿动作 |
|---|---|---|---|---|
| 1 | book-flight | flight | `bookFlight`（预订机票） | `cancelFlight`（取消机票） |
| 2 | book-hotel | hotel | `bookHotel`（预订酒店） | `cancelHotel`（取消酒店） |
| 3 | apply-visa | visa | `applyVisa`（办理签证） | `withdrawVisa`（撤回签证） |
| 4 | award-loyalty | loyalty | `awardPoints`（累计积分） | `revokePoints`（扣回积分） |

步数（4 步）与参与方组合与下单流程完全不同，失败回滚、幂等去重规则完全一致。

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
| `TIMEOUT` | **参与方自报超时**：下游自己判断并睡眠后抛出（既有机制，照旧可用） | 是 |
| `ORCHESTRATOR_TIMEOUT` | **编排侧硬超时**：下游长时间既不返回也不报错，被编排器按时间上限掐断 | 是 |
| `TRANSIENT` | 其他瞬时故障（连接抖动等） | 是 |
| `RETRIES_EXHAUSTED` | 有界重试耗尽后仍失败（最终分类，消息中保留末次失败分类） | — |
| `CIRCUIT_OPEN` | 熔断 OPEN 期间快速失败，未触达下游 | 否 |

> 步骤状态中保留最终分类（如持续超时最终记为 `RETRIES_EXHAUSTED`，业务拒绝记为 `BUSINESS`），
> 查询/日志中可直接区分失败原因。

### 3.1b 两类超时如何共存、如何区分（兼容范围）

| 维度 | 参与方自报超时（旧） | 编排侧硬超时（新） |
|---|---|---|
| 谁判定 | 参与方自己（睡眠到阈值后抛 `TIMEOUT`） | 编排器（`ResilientCaller` 用 `future.get(时间上限)`） |
| 下游表现 | 最终会返回一个超时错误 | **卡死**：长时间不返回、也不报错 |
| 失败分类 | `TIMEOUT` | `ORCHESTRATOR_TIMEOUT` |
| 日志判定依据 | “模拟下游超时(NNms)” | “编排侧硬超时：参与方[X]在NNms内未返回，已掐断” |
| 后续 | 重试 → 重试上限 → 失败回滚 | 完全相同：**当作可重试瞬时故障**，走同一套重试上限与逆序补偿 |

- 两种机制互不冲突、可同时存在：编排器只会在“到点仍未返回”时掐断；参与方若更早自报超时，
  按旧路径处理。
- 被掐断的调用**一律不算成功**：编排线程在时间上限处停止等待并 `cancel(true)` 中断工作线程，
  该次调用不会标记步骤成功、不参与最终成功判定；模拟下游在挂起期间不产生副作用、不记账，
  因此被掐断不会留下任何“已生效”痕迹（真实系统中仍需配合下游幂等键防止“迟到生效”，
  本工程的幂等键模型与此一致）。
- 重试耗尽后的 `RETRIES_EXHAUSTED` 消息中会带“末次分类=TIMEOUT / ORCHESTRATOR_TIMEOUT”，
  流水与日志里两类超时可明确区分。

### 3.2 重试（`core/ResilientCaller`）

- 对 `TIMEOUT / ORCHESTRATOR_TIMEOUT / TRANSIENT` 做有界重试，固定退避；
  `BUSINESS / CIRCUIT_OPEN` 立即上抛。
- 每次尝试、硬超时掐断、重试等待、最终耗尽都有 WARN 日志，含 requestId、参与方、操作、尝试次数。
- 下游调用统一在独立的 daemon 工作线程池（`saga-downstream-call-*`）上执行，
  因此编排线程可以在硬超时到达时立即放弃等待；线程池随容器关闭而 `shutdownNow`。

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
| `saga.resilience.call-timeout-millis` | `2000` | **编排侧每次下游调用的硬超时时间上限（毫秒）**：到点下游仍未返回即掐断，按 `ORCHESTRATOR_TIMEOUT`（可重试瞬时故障）处理，再走重试上限与失败回滚；`<=0` 表示不启用硬超时（只保留参与方自报超时机制） |
| `saga.resilience.circuit-failure-threshold` | `3` | 连续失败多少次开启熔断 |
| `saga.resilience.circuit-open-millis` | `2000` | 熔断 OPEN 保持时长（毫秒），之后进入 HALF_OPEN |
| `saga.resilience.circuit-success-threshold` | `1` | HALF_OPEN 连续成功多少次后回到 CLOSED |

### 3.5 并发与容量保证

- **同一 requestId 只真正执行一次**：仓储 `createIfAbsent` 原子去重 + 编排器实例锁双重保证；
  同一标识无论多少线程同时提交，只有一个线程驱动，其余线程等待后直接拿到同一终态。
- **不同 requestId 互不阻塞**：编排锁是“每实例一把”，不同实例在各自线程（调用在独立下游线程池上）
  完全并行；不存在全局串行锁。已用 32 线程混合提交验证：84 个步骤若串行需 3360ms，
  实测墙钟远低于其一半（见 `MixedFlowConcurrencyTest` 输出的“串行下界 vs 实际”）。
- **状态只单调向前**：`SagaInstance` 状态机拒绝任何非法/倒退迁移；流水序号在实例锁内分配，
  任意时刻查询到的历史都是 `1..N` 连续、无重复、无缺号；查询返回快照副本，终态后永不再变。
- **事件不丢不重**：每个关键判定（受理/去重、步骤执行/成功/失败、补偿各节点、终态）都追加
  带序号流水；参与方副作用只在“真正生效”后记一次账，故障/超时/熔断/被掐断的调用均不记账。
- 已验证规模：32 个工作线程 + 72 次提交（24 个唯一标识，每个标识含重复提交）+ 并发查询线程，
  中途任意时刻快照自洽（见 `HighConcurrencyConsistencyTest`）。

---

## 4. HTTP 接口（本地启动后可手动验证）

启动：

```bash
mvn spring-boot:run
```

提交（同一 `requestId` 重复 POST 是幂等的，返回同一终态）：

```bash
# 下单流程（businessType 留空同样默认 ORDER_PLACEMENT）
curl -s -X POST http://localhost:8080/api/sagas \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-001","businessType":"ORDER_PLACEMENT"}'

# 差旅预订流程（四步，参与方与下单完全不同）
curl -s -X POST http://localhost:8080/api/sagas \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"trip-001","businessType":"TRAVEL_BOOKING"}'

# 未注册业务类型：明确 400 拒绝，不会套用默认流程
curl -i -X POST http://localhost:8080/api/sagas \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"bad-001","businessType":"NOT_REGISTERED"}'
```

查询单调状态与流水：

```bash
curl -s http://localhost:8080/api/sagas/demo-001
```

响应包含：`requestId / status / steps(每步状态与失败分类) / history(带序号的判定流水) / duplicated`。

---

## 5. 本地验证方法

```bash
# 运行全部测试（19 个，含既有 4 大场景 + 新增多流程/硬超时/高并发 + 基线 + HTTP 冒烟）
mvn test

# 只看某个场景
mvn test -Dtest=DuplicateSubmitTest             # 重复提交（顺序/8线程并发/终态重放）
mvn test -Dtest=TimeoutRetryTest                # 参与方自报超时：首次重试成功 / 持续超时耗尽后补偿
mvn test -Dtest=HardTimeoutTest                 # 编排侧硬超时：下游卡死被掐断、重试、回滚/恢复
mvn test -Dtest=FailureRollbackTest             # 业务失败立即回滚 / 补偿幂等
mvn test -Dtest=CircuitBreakerTest              # 熔断开启快速失败 + 自动恢复
mvn test -Dtest=TravelSagaTest                  # 第二种业务流程（四步）成功/逆序补偿/未知类型拒绝
mvn test -Dtest=MixedFlowConcurrencyTest        # 32线程两流程混合（重复+独立标识），并行不串行
mvn test -Dtest=HighConcurrencyConsistencyTest  # 高并发下流水连续、状态单调、中途查询自洽
mvn test -Dtest=ConcurrentCompensationTest      # 高并发失败补偿各自独立、补偿恰好一次
```

> 硬超时相关用例通过测试属性把时间上限调小（`call-timeout-millis=150`、`backoff-millis=20`），
> 无需等待默认 2000ms；本地想观察默认行为可直接 `mvn spring-boot:run` 后用 HTTP 提交。

测试通过 `SideEffectLedger`（只记录**真正落地**的副作用）断言：

- **无重复**：任意 `(请求, 参与方, 方向)` 的副作用次数恒为 0 或 1，并发下亦然；
- **无丢失**：成功路径每步 EXECUTE 恰好 1 次；失败路径所有已生效步骤 COMPENSATE 恰好 1 次；
  高并发场景还会核对账本总条数（如 24 个实例 × 平均步数 = 84）与每条流水可追溯到实例；
- **失败原因可区分**：断言步骤的 `FailureType`
  （`BUSINESS` / `TIMEOUT` / `ORCHESTRATOR_TIMEOUT` / `RETRIES_EXHAUSTED` / `CIRCUIT_OPEN`）。

每个场景都会在测试日志（`System.out`）打印：**请求标识、编排终态、参与方执行/补偿流水、
编排判定流水（含单调序号与判定依据）、各步骤状态**，格式见 `tests/TestSupport.printReport`。

| 测试类 | 覆盖场景 |
|---|---|
| `HappyPathTest` | 三步全部成功，无补偿 |
| `DuplicateSubmitTest` | 顺序重复提交、8 线程并发提交、终态后再次驱动 |
| `TimeoutRetryTest` | **参与方自报超时**：首次超时第 2 次成功；持续超时重试耗尽 → 补偿 |
| `HardTimeoutTest` | **编排侧硬超时**：下游永久挂起被掐断 3 次耗尽 → 补偿（无副作用痕迹）；首次掐断后重试恢复 |
| `FailureRollbackTest` | 不可重试业务失败立即回滚（不重试）；回滚后重放补偿幂等 |
| `CircuitBreakerTest` | 达阈值熔断 OPEN、OPEN 期间快速失败、恢复后 HALF_OPEN 试探成功 |
| `TravelSagaTest` | 差旅四步流程成功；签证失败逆序补偿机票/酒店；未注册类型 400 拒绝且不建实例 |
| `MixedFlowConcurrencyTest` | 32 线程混合两流程 + 同标识重复 + 不同标识，验证并行、去重、流水、账本总数 |
| `HighConcurrencyConsistencyTest` | 72 次提交/24 标识 + 并发查询：序号连续、状态单调、中途/终态快照自洽、流水可追溯 |
| `ConcurrentCompensationTest` | 32 提交/16 个失败请求：并发补偿互不串台、补偿恰好一次 |
| `SagaControllerSmokeTest` | HTTP 提交/重复提交/查询/404 |

---

## 6. 代码结构

```
core/
  SagaStatus / StepStatus / FailureType   生命周期与失败分类枚举（含 ORCHESTRATOR_TIMEOUT）
  SagaStep / SagaDefinition / SagaContext 步骤与 Saga 定义
  SagaDefinitionFactory / SagaDefinitionRegistry  业务流程工厂接口与按类型注册表
  SagaInstance / StepState / HistoryEvent 实例状态机、步骤状态、判定流水
  SagaRepository / InMemorySagaRepository 原子去重的内存仓储
  RetryPolicy / CircuitBreakerConfig / CircuitBreaker  重试策略与熔断器
  ResilientCaller                         硬超时掐断/超时判定/有界重试/熔断包装
  SagaOrchestrator                        正向执行 + 逆序补偿 + 终态保护
  SagaService / OrderSagaFactory / TravelSagaFactory  幂等受理、下单(3步)与差旅(4步)流程
participant/
  ParticipantClient(接口, core 中) / AbstractSimulatedParticipant  幂等+故障注入(含永久挂起)+记账
  Inventory/Payment/ShippingParticipant   下单流程的三个本地模拟参与方
  Flight/Hotel/Visa/LoyaltyParticipant    差旅流程的四个本地模拟参与方
  FaultPlan                               故障注入（第N次超时/业务失败/持续失败/永久挂起）
  SideEffectLedger / LedgerEntry          副作用账本（测试断言依据）
web/                REST 控制器、DTO、全局异常处理（未注册业务类型 -> 400）
config/             saga.resilience.* 配置属性绑定（含 call-timeout-millis）
tests/              场景测试与统一日志打印
```
