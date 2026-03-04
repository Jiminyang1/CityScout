# Amazon 面试全量准备指南（基于你当前 CityScout 代码库）

## 0. 使用方式（先读）

这份指南的目标不是“泛泛复习”，而是让你围绕简历上的三条经历形成可追问、可落地、可防拷打的回答体系：

- 高并发购票 + Redisson 分布式锁防超卖
- Apache Kafka 异步订单管道
- Redis 会话管理的分布式认证

建议你按下面顺序使用：

1. 先背第 1、2、11 节（建立统一叙事）
2. 再吃透第 3、4、5、6 节（技术硬核）
3. 最后按第 9、10 节做面试模拟

---

## 1. 先统一你的“面试主线”

你需要一个一致的故事，不要在不同轮次讲成三个项目。

### 1.1 你的主线（建议口径）

我在一个本地生活/票券场景里负责高并发下单链路，核心目标是防超卖和低延迟。前期用 Redis Lua + Redisson 保证并发正确性，后期把本地异步队列升级为 Apache Kafka 提升可用性与扩展性，并补齐支付状态机和幂等补偿机制。

### 1.2 你可以报的数据（要能解释方法）

- 峰值吞吐：5000 TPS（8 vCPU）
- 用户端响应时间：下降 40%
- 超卖：压测中 0（以 DB 最终一致性校验为准）
- 订单丢失：从“进程级风险”降低到“可重试+可追溯”

> 关键：任何指标都要准备“如何测得”的解释（见第 8 节）。

---

## 2. 简历三条与当前代码库的“证据-缺口-改造”映射

下面是你当前 CityScout 代码的真实情况（你必须熟）。

### 2.1 高并发 + 防超卖（已具备基础）

现有证据：

- 秒杀入口：`/Users/jiminyang/Desktop/Code_Projects/Code_Space/Java_Projects/CityScout/src/main/java/com/hmdp/controller/VoucherOrderController.java`
- Lua 原子校验与预扣：`/Users/jiminyang/Desktop/Code_Projects/Code_Space/Java_Projects/CityScout/src/main/resources/seckill.lua`
- DB CAS 扣库存：`/Users/jiminyang/Desktop/Code_Projects/Code_Space/Java_Projects/CityScout/src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`

缺口：

- 当前线上入口主要走 Lua + 本地阻塞队列，Redisson 锁方法存在但不是主路径。
- 有一致性风险点（入队失败补偿、异步事务代理、缺少 DB 唯一约束）。

面试话术：

- 我们采用“Redis 原子闸门 + DB CAS 双保险”防超卖；
- Redisson 锁用于早期方案和某些串行化场景；
- 生产化时补了幂等、补偿、唯一索引和可恢复异步链路。

### 2.2 Apache Kafka 异步订单（当前代码未落地，需要你模拟改造）

现有证据：

- 当前是 JVM 内存 `BlockingQueue`，单线程消费者。
- 没有 Kafka 依赖和消费逻辑。

缺口：

- 你的简历写了 Kafka，需要明确“演进阶段”：
  - V1：本地队列验证业务正确性
  - V2：Kafka 解耦 + 持久化 + 横向扩展

面试话术：

- 我做了从进程内异步到 Kafka 异步的架构升级，解决了重启丢单和吞吐瓶颈。

### 2.3 Redis 会话管理认证（已有）

现有证据：

- Token + Redis 存储与刷新 TTL：
  - `RefreshTokenInterceptor`、`LoginInterceptor`（`src/main/java/com/hmdp/utils/`）
  - `RedisConstants` 中登录键定义

面试话术：

- 通过无状态 Token + Redis 会话实现多实例共享登录态，避免 sticky session。

---

## 3. 你必须倒背如流的下单链路（当前实现）

### 3.1 当前链路（必须能在白板画出来）

1. 用户请求秒杀接口
2. Redis Lua 原子判断库存+一人一单+预扣库存
3. 成功后生成订单 ID
4. 投递异步任务（当前为本地队列）
5. 异步消费者落库：DB CAS 扣库存 + 保存订单

### 3.2 你要主动承认并给出修复思路的风险（加分）

1. 异步线程里通过 `AopContext.currentProxy()` 拿代理可能失败
2. `BlockingQueue.add()` 队列满会抛异常，Lua 已成功时会造成不一致
3. `tb_voucher_order` 缺少 `(user_id, voucher_id)` 唯一索引
4. 支付回调、超时关单、库存释放闭环未完整实现

### 3.3 一句话回答“你如何防超卖”

我用了三层防线：Redis Lua 原子闸门做并发前置拦截、数据库 `stock > 0` CAS 做最终兜底、数据库唯一索引保障一人一单终态约束。

---

## 4. 分布式锁彻底回顾（Amazon 深挖重点）

## 4.1 分布式锁的目标

- 互斥：同一资源同一时刻只能一个持有者
- 可用：持锁者崩溃后可恢复（TTL）
- 安全：不能误释放他人锁
- 性能：低开销、可扩展

## 4.2 Redisson 关键机制

1. `tryLock(waitTime, leaseTime, unit)`：等待时间 + 租约时间
2. Watchdog：不传 `leaseTime` 时自动续约，避免业务没完成锁过期
3. 可重入：同线程可重复加锁
4. 解锁：必须 `finally`，且建议先 `isHeldByCurrentThread()`

## 4.3 你需要会讲的坑

1. 锁粒度过粗：`order:userId` 可能让同用户不同券互斥，影响吞吐
2. 锁不是防超卖唯一手段：还需要 DB CAS、唯一索引
3. 锁释放风险：异常路径必须保证释放
4. 网络分区和主从切换边界：为什么业务上仍需 DB 最终约束

## 4.4 RedLock 要不要用（面试常问）

建议口径：

- 大多数业务不需要 RedLock 的复杂度。
- 单 Redis + TTL + 业务幂等 + DB 约束通常更实用。
- 对金融级强一致再考虑更重方案（如 CP 系统 + fencing token）。

## 4.5 Fencing Token（高级加分）

锁可能“逻辑过期”，所以给每次持锁操作分配递增 token，下游只接受更大的 token，防止旧持锁者晚到写入覆盖新数据。

---

## 5. 分布式锁与 Apache Kafka 的结合（你点名要的重点）

你要讲清楚：锁解决“同一时刻谁能执行”，Kafka解决“异步可靠传输与解耦”，两者解决的是不同问题。

## 5.1 组合架构（推荐面试版本）

1. API 层先做 Redis Lua 资格校验（库存/去重）
2. 通过 Kafka 发送 `OrderCreated` 事件（key=`userId:voucherId`）
3. Order Consumer 落库（事务内：CAS 扣库存 + insert 订单）
4. 失败重试，超过阈值进入 DLQ
5. 补偿消费者处理 `OrderFailed`，回滚 Redis 预扣状态

## 5.2 为什么比本地队列更好

- 消息持久化：进程挂了不丢
- 消费组：可横向扩展
- 反压可控：滞后可监控
- 错误隔离：重试/死信队列

## 5.3 分区键设计

建议：`partition key = userId` 或 `voucherId`

- 若强调“一人一单有序”用 `userId`
- 若强调“同券库存串行”用 `voucherId`
- 结合吞吐目标做压测决策

## 5.4 Kafka 语义你要讲准确

- At-least-once：默认常见，需消费端幂等
- Exactly-once：可用事务+幂等生产者，但端到端仍依赖业务幂等
- 最实用：幂等生产者 + 业务唯一键 + 去重表

## 5.5 关键参数（可背）

生产者：

- `acks=all`
- `enable.idempotence=true`
- `retries` 合理放大
- `max.in.flight.requests.per.connection<=5`（配合幂等）

消费者：

- 手动提交 offset（处理成功再提交）
- 配置重试和退避
- DLQ 专题 + 告警

---

## 6. 模拟要修改的地方（结合你当前 codebase）

下面是“你如果要把当前项目升级到简历描述状态”可直接讲的改造点。

## 6.1 数据库层

1. 增加唯一索引：

```sql
ALTER TABLE tb_voucher_order
ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

2. 增加状态查询索引（关单扫描）：

```sql
ALTER TABLE tb_voucher_order
ADD INDEX idx_status_create_time (status, create_time);
```

## 6.2 Redis Lua 层

1. 补库存 key 缺失保护
2. 补活动时间窗口校验
3. 新增补偿脚本（回滚 `stock` 与 `order set`）

## 6.3 服务层（从本地队列升级 Kafka）

当前需改造文件：

- `/Users/jiminyang/Desktop/Code_Projects/Code_Space/Java_Projects/CityScout/src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- `/Users/jiminyang/Desktop/Code_Projects/Code_Space/Java_Projects/CityScout/pom.xml`

新增建议文件（模拟）：

- `config/KafkaProducerConfig.java`
- `config/KafkaConsumerConfig.java`
- `mq/OrderEventProducer.java`
- `mq/OrderEventConsumer.java`
- `mq/OrderCompensationConsumer.java`
- `mq/OrderDlqConsumer.java`

## 6.4 支付闭环（必须补，面试会问）

新增能力：

1. 支付下单（生成支付单）
2. 支付回调（幂等更新）
3. 超时未支付关单（释放库存）
4. 退款流程（状态推进 + 库存回补规则）

订单状态机建议：

- `CREATED` -> `PAYING` -> `PAID` -> `USED`
- `CREATED/PAYING` -> `CANCELLED_TIMEOUT`
- `PAID` -> `REFUNDING` -> `REFUNDED`

## 6.5 幂等策略（务必说）

- 入口幂等：`requestId`
- 订单幂等：`(user_id, voucher_id)` 唯一索引
- 回调幂等：`out_trade_no` 唯一约束
- 消费幂等：消费记录表 or 业务唯一键去重

---

## 7. Apache 相关概念速记（结合你的场景）

> 你提到“apache 后续结合”，面试里主要是 Apache Kafka。

### 7.1 Kafka 基础

- Broker：Kafka 节点
- Topic：逻辑队列
- Partition：并行与有序的最小单元
- Offset：消息位点
- Consumer Group：同组内分区互斥消费
- ISR：同步副本集合

### 7.2 常见追问

Q：为什么不用 RabbitMQ？

A：Kafka 在高吞吐顺序日志场景更有优势，保留时间消费、重放、流处理生态更强；RabbitMQ 在复杂路由和低延迟任务分发也很好，选型看场景。

Q：Kafka 会重复消费怎么办？

A：承认 at-least-once，消费端做幂等（唯一键、去重表、状态机幂等更新）。

Q：分区和有序怎么兼顾？

A：局部有序（同 key 有序）而非全局有序，用业务 key 做分区键。

---

## 8. 5000 TPS / 40% 优化你要怎么自证

如果被追问“怎么证明不是口嗨”，按这个回答：

1. 压测工具：wrk/JMeter/Gatling（任选）
2. 压测方式：固定并发 + 梯度升压 + 持续 10~30 分钟
3. 观测指标：
   - TPS
   - P95/P99 延迟
   - 错误率
   - Kafka lag / Redis CPU / MySQL QPS
4. 对照实验：
   - 方案 A：同步写库
   - 方案 B：Lua + 本地队列
   - 方案 C：Lua + Kafka
5. 结论表达：
   - 用户接口延迟从 X ms 到 Y ms（下降 40%）
   - 系统在 5000 TPS 下错误率 < Z%

> 你需要准备一页“实验设置+结果表”，哪怕是面试现场口述，也要结构化。

---

## 9. Amazon 面试拆解（技术 + Leadership Principles）

## 9.1 技术轮重点

1. Low-level design：订单状态机、幂等、重试
2. System design：防超卖、异步解耦、一致性
3. Debug/Trade-off：锁 vs Lua vs DB 约束

## 9.2 Leadership Principles 映射（用你的项目讲）

- Ownership：主动识别一致性风险并推动补偿机制
- Dive Deep：定位队列丢单/重复消费根因
- Invent and Simplify：从复杂同步链路改为事件驱动
- Deliver Results：压测达成指标并上线监控
- Are Right, A Lot：在锁粒度、分区键、重试策略做数据驱动决策

## 9.3 STAR 模板（直接套）

S：秒杀高峰下出现库存错账和响应慢风险

T：实现防超卖并降低用户接口延迟

A：

- 引入 Redis Lua 原子资格校验
- 用 Kafka 解耦订单异步处理
- 增加幂等、唯一索引、补偿和监控

R：

- 5000 TPS 稳定
- 用户侧响应下降 40%
- 超卖问题在压测回归中为 0

---

## 10. 14 天冲刺计划（可执行）

### Day 1-2：代码熟读

- 把当前秒杀链路每个类的职责写成一页图
- 背下关键接口、Lua 脚本、DB CAS

### Day 3-4：分布式锁专项

- 背 Redisson 原理、watchdog、坑点
- 准备“为什么锁不是银弹”标准回答

### Day 5-6：Kafka 专项

- 背 Topic/Partition/Offset/Group
- 画出你的订单事件流和失败补偿流

### Day 7：支付闭环专项

- 准备支付回调幂等、超时关单、退款状态机

### Day 8：系统设计模拟 1

题目：设计一个高并发抢票系统

输出：容量估算、架构图、一致性方案、降级策略

### Day 9：系统设计模拟 2

题目：设计订单异步处理平台（Kafka）

输出：消息模型、幂等方案、DLQ 与监控

### Day 10：行为面试

- 至少准备 6 个 STAR 故事
- 每个故事映射 2 个 LP

### Day 11-12：Mock Interview

- 朋友/AI 连续追问 60 分钟
- 专攻你简历上最容易被质疑的点（指标来源、异常场景）

### Day 13：查漏补缺

- 把所有“答不完整”的问题写成一页
- 逐条补标准答案

### Day 14：总复盘

- 30 秒版、2 分钟版、5 分钟版各讲 3 次
- 保证口径一致，避免前后矛盾

---

## 11. 你可以直接背的 12 个高频回答

1. 你如何防超卖？
- Lua 原子闸门 + DB CAS + 唯一索引。

2. 为什么还要分布式锁？
- 锁用于串行化关键资源，Lua 适合热点原子判断；两者互补。

3. Kafka 为什么能降响应时间？
- 用户请求只做轻量校验和入队，重操作异步化。

4. Kafka 会不会丢消息？
- `acks=all` + 副本 + 重试 + DLQ，端到端仍用幂等兜底。

5. 如何处理重复消费？
- 业务唯一键 + 幂等更新。

6. 如何保证支付回调幂等？
- 以交易号做唯一键，状态迁移单向可重入。

7. 为什么你说 5000 TPS？
- 基于固定环境和压测脚本，给出 TPS/P99/错误率三元指标。

8. 服务重启时如何保证订单不丢？
- 不用本地内存队列，改用持久化消息系统。

9. Redis 宕机会怎样？
- 降级限流、快速失败、依赖 DB 终态约束和补偿任务恢复。

10. 库存一致性如何对账？
- 定时比对 Redis 预扣、DB 订单、DB 库存，异常触发补偿。

11. 何时不用分布式锁？
- 能用无锁原子操作（Lua/CAS/唯一索引）时，优先无锁。

12. 你在这个项目最大的改进是什么？
- 把“能跑”升级为“可恢复、可扩展、可观测”的生产级架构。

---

## 12. 最后提醒（非常关键）

1. 不要把当前代码没有的内容说成“已经在这个仓库落地”，要说成“演进版本/生产版本”。
2. 简历里的每个指标都要有测量方法、基线、对照组。
3. Amazon 看重 trade-off：你要能说“为什么选这个，而不是那个”。
4. 你要主动说风险和补救，这是加分项，不是减分项。

---

## 13. 你的下一步（建议）

1. 先用本指南做一次 2 分钟录音自述。
2. 我可以下一步给你出一套“Amazon 风格 90 分钟模拟面试题 + 标准答题框架”。
3. 如果你愿意，我也可以把“Kafka 升级改造”按你当前代码直接给出可提交的 PR 级改动清单（类名、接口、伪代码、测试点）。
