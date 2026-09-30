# Saga Orchestrator（本地可运行的分布式事务编排）

基于 Spring Boot 3 / Java 21 的**进程内 Saga 编排器**：用本地模拟参与方替代真实下游，
实现「顺序正向执行 + 失败逆序补偿」，并在**重复提交、两类超时、多业务流程混合、高并发到达**
下保证结果要么全部生效、要么全部回退——不允许部分生效、重复生效或漏补偿。

> 所有状态与“下游”都在单进程内模拟（内存仓储 + 模拟参与方），`mvn test` 即可完整验证，
> 无需数据库 / 消息队列 / 真实第三方服务。

本轮能力扩展：

1. **多业务流程**：除“下单”（三步）外新增“账户开立”（四步，参与方组合完全不同），
   提交时按 `businessType` 精确选中，两种流程可同时并发运行、互不影响；未注册类型明确拒绝。
2. **编排侧硬超时**：给每一次下游调用设可配置时间上限，下游长期不返回时由编排器主动掐断，
   按可重试瞬时故障处理，再走正常重试上限与失败回滚；与原有“参与方自报超时”并存且可区分。
3. **并发与容量**：可扛 32 线程混合提交（同标识重复 + 不同标识并发 + 两种流程混合），
   同标识只执行一次、不同标识并行不互斥、流水序号连续唯一、任意时刻查询状态自洽。

---

## 1. 编排协议

### 1.1 业务流程如何描述与选择

每种业务流程实现一个 `SagaFactory`（`businessType()` 给出标识，`create()` 给出有序步骤），
启动时由 `SagaDefinitionRegistry` 自动收集注册；提交请求按 `businessType` **精确匹配**（大小写不敏感）：

- 命中已注册类型 → 使用对应定义创建/复用实例；
- `businessType` 空白或未注册 → 直接抛 `IllegalArgumentException`（HTTP 映射为 400），
  **绝不静默套用默认流程**；
- 同一 `requestId` 始终只能对应首次受理时的业务类型，换类型重复提交同样被 400 拒绝。

#### 流程一：下单 `ORDER_PLACEMENT`（`core/OrderSagaFactory`，三步）

| 顺序 | 步骤 | 参与方 | 正向动作 | 补偿动作 |
|---|---|---|---|---|
| 1 | reserve-stock | inventory | `reserveStock`（预留库存） | `releaseStock`（释放库存） |
| 2 | charge-payment | payment | `charge`（扣款） | `refund`（退款） |
| 3 | arrange-shipping | shipping | `arrangeShipment`（安排发货） | `cancelShipment`（取消发货） |

#### 流程二：账户开立 `ACCOUNT_OPENING`（`core/AccountOpeningSagaFactory`，四步）

| 顺序 | 步骤 | 参与方 | 正向动作 | 补偿动作 |
|---|---|---|---|---|
| 1 | verify-identity | kyc | `verifyIdentity`（实名核验） | `revokeVerification`（撤销核验） |
| 2 | open-account | account | `openAccount`（开立账户） | `closeAccount`（关闭账户） |
| 3 | bind-card | card | `bindCard`（绑定银行卡） | `unbindCard`（解绑银行卡） |
| 4 | send-welcome | notification | `sendWelcome`（开户通知） | `recallNotification`（撤回通知） |

两种流程步数不同（3 vs 4）、参与方组合完全不重叠
（inventory/payment/shipping vs kyc/account/card/notification），因此可同时并发运行：
各自的正向执行、逆序补偿、幂等去重都按 `requestId` 独立成立，互不影响。

正向按对应定义顺序逐步执行；**任一步明确失败后，从失败点的前一步开始逆序补偿**，
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

1. **精确选流程**：`SagaDefinitionRegistry.resolve(businessType)`，未注册/空白即拒绝；
   同一 requestId 换类型重复提交也拒绝。
2. **原子受理去重**：仓储 `createIfAbsent` 基于 `ConcurrentHashMap.computeIfAbsent`，
   同一 `requestId` 在 JVM 内只有一个 `SagaInstance`，并发提交只有一个胜出。
3. **串行驱动、跨请求并行**：`SagaOrchestrator.drive` 以实例对象为锁；同一实例的并发/重复提交
   线程要么等待首个执行完成后看到终态直接返回，要么被幂等键拦截；不同 requestId 使用不同实例锁，
   且下游调用在独立派发线程上执行（见 3.2），因此不同标识之间不会互相阻塞成接近串行。
4. **逐步执行**：每步经 `ResilientCaller` 施加「编排侧硬超时 → 熔断 → 有界重试」保护。
5. **失败补偿**：逆序补偿所有“已生效”步骤；补偿同样走弹性包装。
6. **全程流水**：每个关键动作追加一条带单调序号的 `HistoryEvent`（事件类型 + 步骤 + 判定依据），
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

## 3. 失败分类、两类超时、重试与熔断

### 3.1 失败分类（`core/FailureType`，失败原因可区分）

| 分类 | 含义 | 是否重试 |
|---|---|---|
| `BUSINESS` | 不可重试的业务失败（如余额不足、业务拒绝） | **否**，立即失败并补偿 |
| `TIMEOUT` | **参与方自报超时**：下游在约定时间内自行判定并上报（睡眠后抛出） | 是 |
| `HARD_TIMEOUT` | **编排侧硬超时**：下游长时间不返回，编排器达到时间上限主动掐断等待 | 是 |
| `TRANSIENT` | 其他瞬时故障（连接抖动等） | 是 |
| `RETRIES_EXHAUSTED` | 有界重试耗尽后仍失败（最终分类） | — |
| `CIRCUIT_OPEN` | 熔断 OPEN 期间快速失败，未触达下游 | 否 |

> 步骤状态同时保留**最终分类**与**底层分类（rootCauseType）**：例如持续自报超时最终记为
> `RETRIES_EXHAUSTED` 且 rootCauseType=`TIMEOUT`；持续卡死被掐断最终也是 `RETRIES_EXHAUSTED`
> 但 rootCauseType=`HARD_TIMEOUT`。查询视图的每个步骤带 `failureType` 与 `rootCauseType` 两个字段，
> 流水文本也会标注“（参与方自报超时）/（编排侧硬超时主动掐断）”，日志、结果中两类超时一眼可区分。

### 3.2 编排侧硬超时（`core/HardTimeoutCaller`，本轮新增）

- **机制**：每一次下游调用（单次尝试）都提交到固定大小的派发线程池，编排驱动线程用
  `Future.get(callTimeoutMillis, MS)` 等待；超时即 `future.cancel(true)` **主动停止等待**，
  本次调用归类 `HARD_TIMEOUT`（可重试瞬时故障），随后走与其它瞬时故障完全相同的
  「有界重试 → 重试上限 → 失败回滚」链路。
- **被掐断调用的零副作用保证**：取消后编排器不等待下游、立即判定本次失败；模拟参与方收到中断
  后在写入“已生效集合”/副作用账本**之前**终止，因此被掐断的调用一律不算成功、不记账，
  也不会被补偿为“已生效”。（真实系统中对应“结果未知的调用不得提交为成功”，下游需幂等支持重放。）
- **容量/互不阻塞**：工作线程数 `dispatch-pool-size`（默认 64）≥ 混合并发度，单个下游卡死只占住
  自己的工作线程，其他 requestId 的调用在空闲工作线程上照常快速完成（容量测试中正常请求
  单请求耗时远低于硬超时上限）。
- **与原超时方式的兼容范围**：`call-timeout-millis <= 0` 即关闭编排侧硬超时，退回“仅参与方自报
  TIMEOUT”的旧行为；默认开启（1000ms）时，短于上限的自报超时依旧是 `TIMEOUT`，两条路径并存。
- 补偿调用同样受硬超时保护；若补偿在重试上限后仍失败，编排进入 `COMPENSATION_FAILED` 终态。

### 3.3 重试（`core/ResilientCaller`）

- 仅对 `TIMEOUT / HARD_TIMEOUT / TRANSIENT` 做有界重试，固定退避；
  `BUSINESS / CIRCUIT_OPEN` 立即上抛。
- 每次尝试、掐断、重试等待、最终耗尽都有 WARN 日志，含 requestId、参与方、操作、尝试次数与
  最终/底层分类；重试耗尽时异常同时携带 rootFailureType，写入步骤状态供查询区分。

### 3.4 熔断（`core/CircuitBreaker`，参与方维度）

- **CLOSED**：连续失败达到阈值 → 转 **OPEN**；期间任一成功清零计数。
- **OPEN**：在打开窗口内 `allowRequest()=false`，调用方不触达下游、立即快速失败
  （`CIRCUIT_OPEN`）；窗口超时后惰性转 **HALF_OPEN** 放行一次试探。
- **HALF_OPEN**：连续成功达到阈值 → 回 **CLOSED**（故障自动恢复）；任意失败立即回 OPEN。

### 3.5 配置项（`src/main/resources/application.properties`，前缀 `saga.resilience`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `saga.resilience.max-attempts` | `3` | 单次下游调用最大尝试次数（含首次）；`<=1` 不重试 |
| `saga.resilience.backoff-millis` | `50` | 可重试失败两次尝试之间的固定退避（毫秒） |
| `saga.resilience.circuit-failure-threshold` | `3` | 连续失败多少次开启熔断 |
| `saga.resilience.circuit-open-millis` | `2000` | 熔断 OPEN 保持时长（毫秒），之后进入 HALF_OPEN |
| `saga.resilience.circuit-success-threshold` | `1` | HALF_OPEN 连续成功多少次后回到 CLOSED |
| `saga.resilience.call-timeout-millis` | `1000` | **编排侧硬超时：每次下游调用（单次尝试）的等待时间上限（毫秒）**；超过即主动掐断并按 `HARD_TIMEOUT` 重试，最终走重试上限与回滚；`<=0` 关闭（仅保留参与方自报超时） |
| `saga.resilience.dispatch-pool-size` | `64` | **硬超时派发工作线程数**；不同 requestId 在不同工作线程上执行，容量需 ≥ 预期混合并发度（默认即可扛 32 线程混合提交） |

---

## 3A. 并发与容量保证

- **同标识只执行一次**：入口原子去重（`createIfAbsent`）+ 实例锁内驱动 + 参与方幂等键，三道防线。
  同一 `requestId` 的并发/顺序/终态后重复提交，业务副作用次数恒为 0 或 1。
- **不同标识并行不互斥**：驱动锁是**每实例**的（不同 requestId 互不竞争）；下游调用在 64 个派发
  工作线程上执行，单个下游长期卡死只占住自己的线程，不会把其他请求拖成串行。
- **状态只单调向前**：`SagaInstance.transitionTo` 自身加锁并校验合法迁移，终态不可再迁移；
  `status` 与各步骤字段以 volatile 发布，并发查询线程永远看不到回退或半成品状态。
- **流水唯一、连续、可追溯**：事件序号由专用流水锁保护，在每个实例内从 1 严格递增、不跳号不重复；
  每条事件携带 requestId；查询返回锁内拷贝的快照，任意时刻快照与状态自洽。
- **验证水位**：`MixedConcurrencyTest` 以 32 线程混合提交（8 个热 key 各 2 线程 + 16 个冷 key，
  两种业务类型各半），并在执行期间持续采样查询状态；`HardTimeoutConcurrencyTest` 在 10 个请求
  同时卡死的情况下验证 22 个正常请求不被阻塞。

---

## 4. HTTP 接口（本地启动后可手动验证）

启动：

```bash
mvn spring-boot:run
```

提交（同一 `requestId` 重复 POST 是幂等的，返回同一终态；`businessType` 必须已注册）：

```bash
# 下单流程（三步）
curl -s -X POST http://localhost:8080/api/sagas \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-001","businessType":"ORDER_PLACEMENT"}'

# 账户开立流程（四步）
curl -s -X POST http://localhost:8080/api/sagas \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-002","businessType":"ACCOUNT_OPENING"}'

# 未注册/空白业务类型 -> HTTP 400，error 中给出已注册类型清单
curl -i -X POST http://localhost:8080/api/sagas \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-003","businessType":"UNKNOWN_X"}'
```

查询单调状态与流水：

```bash
curl -s http://localhost:8080/api/sagas/demo-001
```

响应包含：`requestId / status / steps(每步状态、failureType 与 rootCauseType) /
history(带序号的判定流水) / duplicated`。

---

## 5. 本地验证方法

```bash
# 运行全部测试（22 个：4 大原始必测场景 + 本轮新增场景 + 基线 + HTTP 冒烟）
mvn test

# 只看某个场景
mvn test -Dtest=HappyPathTest                 # 无故障全成功（下单三步）
mvn test -Dtest=DuplicateSubmitTest           # 重复提交（顺序/8线程并发/终态重放）
mvn test -Dtest=TimeoutRetryTest              # 参与方自报超时：首次重试成功 / 持续耗尽补偿
mvn test -Dtest=HardTimeoutTest               # 编排侧硬超时：掐断重试成功 / 持续掐断耗尽回滚 / 两类超时区分
mvn test -Dtest=HardTimeoutConcurrencyTest    # 10个请求卡死时22个正常请求不被阻塞
mvn test -Dtest=MultiFlowTest                 # 开户四步独立执行/补偿/幂等；未注册类型拒绝
mvn test -Dtest=MixedConcurrencyTest          # 32线程两种流程混合：热key去重+冷key并行+流水连续
mvn test -Dtest=FailureRollbackTest           # 业务失败立即回滚 / 补偿幂等
mvn test -Dtest=CircuitBreakerTest            # 熔断开启快速失败 + 自动恢复
```

本地手动演示硬超时（把时间上限调小，观察日志中 `HARD_TIMEOUT` 与回滚）：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--saga.resilience.call-timeout-millis=200
# 硬超时只能通过故障注入在单测中稳定复现（真实下游均为即时成功的本地模拟）；
# 生产语义见 3.2：任何未在上限内返回的下游调用都会被掐断、重试、必要时回滚。
```

测试通过 `SideEffectLedger`（只记录**真正落地**的副作用）断言：

- **无重复**：任意 `(请求, 参与方, 方向)` 的副作用次数恒为 0 或 1，并发下亦然；
- **无丢失**：成功路径每步 EXECUTE 恰好 1 次；失败路径所有已生效步骤 COMPENSATE 恰好 1 次；
- **被掐断零副作用**：硬超时的尝试既不 EXECUTE 记账，也不会触发补偿；
- **失败原因可区分**：断言步骤的 `failureType` 与 `rootCauseType`
  （`BUSINESS` / `RETRIES_EXHAUSTED+TIMEOUT` / `RETRIES_EXHAUSTED+HARD_TIMEOUT` / `CIRCUIT_OPEN`）；
- **流水自洽**：每个实例事件序号 1..N 连续唯一，且并发期间采样到的状态全部是合法生命周期状态。

每个场景都会在测试日志（`System.out`）打印：**请求标识、编排终态、参与方执行/补偿流水、
编排判定流水（含失败分类/底层分类/判定依据）、各步骤状态**，格式见 `tests/TestSupport.printReport`。

| 测试类 | 覆盖场景 |
|---|---|
| `HappyPathTest` | 下单三步全部成功，无补偿 |
| `DuplicateSubmitTest` | 顺序重复提交、8 线程并发提交、终态后再次驱动 |
| `TimeoutRetryTest` | **参与方自报超时**首次重试成功；持续超时耗尽 → 补偿（旧路径不回归） |
| `HardTimeoutTest` | **编排侧硬超时**：首次卡住被掐断后重试成功；持续卡住耗尽回滚；两类超时分类区分 |
| `HardTimeoutConcurrencyTest` | 32 线程中 10 个请求卡死时，22 个正常请求并行不阻塞；终态/流水一致 |
| `MultiFlowTest` | 开户四步成功/第3步失败逆序补偿/重复幂等；未注册类型拒绝；同标识换类型拒绝 |
| `MixedConcurrencyTest` | 32 线程两种流程混合（热key重复+冷key并行）：去重、终态、序号连续、状态采样合法 |
| `FailureRollbackTest` | 不可重试业务失败立即回滚（不重试）；回滚后重放补偿幂等 |
| `CircuitBreakerTest` | 达阈值熔断 OPEN、OPEN 期间快速失败、恢复后 HALF_OPEN 试探成功 |
| `SagaControllerSmokeTest` | HTTP 提交/重复提交/查询/404；未注册类型 400；开户流程四步 |

---

## 6. 代码结构

```
core/
  SagaStatus / StepStatus / FailureType   生命周期与失败分类枚举（含 HARD_TIMEOUT）
  SagaStep / SagaDefinition / SagaContext 步骤与 Saga 定义
  SagaFactory(接口)                        业务流程工厂（businessType + create）
  OrderSagaFactory                         下单流程（三步）
  AccountOpeningSagaFactory                账户开立流程（四步）
  SagaDefinitionRegistry                   流程注册表：精确选型、未注册明确拒绝
  SagaInstance / StepState / HistoryEvent  实例状态机、步骤状态（含 rootCauseType）、判定流水
  SagaRepository / InMemorySagaRepository 原子去重的内存仓储
  RetryPolicy / CircuitBreakerConfig / CircuitBreaker  重试策略与熔断器
  HardTimeoutCaller                        编排侧硬超时：有界派发池 + future 超时掐断
  ResilientCaller                          硬超时/熔断/有界重试包装，保留底层失败分类
  SagaOrchestrator                         正向执行 + 逆序补偿 + 终态保护
  SagaService                              幂等受理、流程选型、类型一致性校验
participant/
  ParticipantClient(接口, core 中) / AbstractSimulatedParticipant  幂等+故障注入(按请求)+记账
  Inventory/Payment/ShippingParticipant    下单流程三个本地模拟参与方
  Kyc/Account/Card/NotificationParticipant 开户流程四个本地模拟参与方
  FaultPlan                                故障注入（第N次超时/业务失败/持续失败/长期挂起/按请求）
  SideEffectLedger / LedgerEntry           副作用账本（测试断言依据）
web/                REST 控制器、DTO（StepView 含 rootCauseType）、全局异常处理
config/             saga.resilience.* 配置属性绑定（含 call-timeout-millis / dispatch-pool-size）
tests/              场景测试与统一日志打印
```
