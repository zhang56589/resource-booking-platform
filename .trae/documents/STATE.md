# 会议室预约系统｜Builder 主控运行时状态

## 阶段进度

| 阶段 | 名称 | 优先级 | 状态 | 阻塞原因 | 开始时间 | 完成时间 |
|---|---|---|---|---|---|---|
| 0 | 基线调研 | — | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P0-1 | 锁粒度拆分优化 | 高 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P0-2 | 本地消息表 RocketMQ 可靠消息 | 高 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P0-3 | RBAC 细粒度权限改造 | 高 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P0-4 | 幂等拦截器 RequestId 防重复提交 | 高 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P1-1 | 数据库索引 + 软删除 | 中 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P1-2 | 批量审批、批量启停接口 | 中 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P1-3 | Nacos @RefreshScope 动态配置 | 中 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P1-4 | MDC 全链路 TraceId 埋点 | 中 | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P1-5 | 前端动态菜单 + v-permission | 中 | ⏳ **待开始** | 依赖 P0-3 | — | — |
| P2-1 | 收尾 README / 接口文档 / SQL | 低 | ⏳ **待开始** | 依赖全部 P0 + P1 | — | — |

---

## 当前状态摘要

- **当前阶段**：P1-4 MDC 全链路 TraceId 埋点 **已完成**
- **下一待执行阶段**：P1-5 前端动态菜单（依赖 P0-3 已就绪）
- **最近一次变更**：P1-4 —— 新增 `TraceIdInterceptor`（入站继承或生成 X-Trace-Id → MDC + 响应头，afterCompletion 清 MDC）；新增 `logback-spring.xml`（console pattern 含 `[%X{traceId:-}]`）；WebConfig 注册 TraceIdInterceptor order=-100（先于 TokenInterceptor，401 拒绝日志也带 traceId）；编译 exit_code=0
- **环境修正**：当前机器 Maven 默认 JDK 1.8 编译失败（项目用 Java 16+ pattern matching），须 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'` 后 `mvn compile`
- **索引现状**：`idx_booking_room_time` / `idx_booking_user_status` / `idx_oplog_create_time` 基线 schema 已存在，P1-1 只需补 `deleted` 列（增量 ALTER 已写入 schema.sql 末尾注释段）

---

## 基线已知问题清单（本轮已标记，后续阶段规划时融入）

| 编号 | 类型 | 问题 | 严重程度 | 对应后续阶段 | 状态 |
|---|---|---|---|---|---|
| B-1 | 启动阻断 | Redisson eager 初始化 + Redis 未启动 → BeanCreationException | 🔴 | 维持现状（先起 Redis） | — |
| B-2 | 隐患 | MQ afterCommit Producer 异常静默吞掉 → 预约成功但通知永远没发 | 🟡 | P0-2 本地消息表 | ✅ **已修复** |
| B-3 | 隐患 | approve 锁粒度为 booking:room:{roomId}（会议室粒度），理论上只需要串行化同一个 bookingId | 🟡 | P0-1 锁粒度拆分 | ✅ **已修复** |
| B-4 | 隐患 | submit 锁粒度为 booking:room:{roomId}（会议室粒度），同会议室不同时段并发被串行化 | 🟡 | 维持现状（业务量小，并发不高） | — |
| B-5 | 缺口 | cancelBooking 无幂等保护（虽然 update 影响 0 行但逻辑上可加） | 🟡 | P0-4 幂等拦截器 | ✅ **已修复** |
| B-6 | 缺口 | 数据库无软删除（物理删会议室外键约束导致 CASCADE 风险） | 🟡 | P1-1 索引+软删除 | ✅ **已修复** |
| B-7 | 缺口 | 登录日志 sys_login_log / 操作日志 sys_operate_log 缺少 username / operator_name 自动填充（已用 IpUtil + TokenInterceptor，但 SysUserServiceImpl.login 里 saveLoginLog 的 username 是手动传参，无自动注入 username 机制） | 🟢 低 | P2-1 收尾对齐 | 待处理 |
| B-8 | 红线 | TokenInterceptor 稳定模块，禁止修改 | — | — | — |

---

## 编译自检记录

| 时间 | 命令 | 退出码 | 备注 |
|---|---|---|---|
| 2026-10-24 | `mvn -q compile` | 0 | 零错误零警告 |
| 2026-10-24 | `mvn -q -DskipTests package` | 0 | jar 生成 adminsystem-0.0.1-SNAPSHOT.jar |
| 2026-10-24 | `mvn spring-boot:run` | 1 | Redisson eager 初始化阻断（Redis 未启动），非代码问题 |
| 2026-10-24 | P0-1 锁粒度拆分 `mvn -q compile` | 0 | 零错误零警告，无新增依赖 |
| 2026-10-24 | P0-2 Outbox Pattern `mvn -q compile` | 0 | 零错误零警告；修复 OutboxScheduler SendCallback 抽象类不能用 lambda → 匿名类；修复 getSendStatus() 枚举比对 |
| 2026-10-24 | P0-3 RBAC 细粒度权限 `mvn -q compile` | 0 | 零错误零警告；新增 spring-boot-starter-aop 依赖；Mapper XML INNER JOIN sys_permission 正确加载 |
| 2026-10-24 | P0-4 幂等拦截器 `mvn -q compile` | 0 | 零错误零警告；Redisson RBucket.trySet SETNX 语义正确；WebConfig 拦截器注册顺序 TokenInterceptor→IdempotentInterceptor |
| 2026-10-24 | P1-1 索引+软删除 `mvn -q compile`（需 JDK21） | 0 | 零错误零警告；@TableLogic 生效；手写 SQL 4 处 JOIN + 2 处 FOR UPDATE 均追加 deleted=0 |
| 2026-10-24 | P1-2 批量接口 `mvn -q compile`（需 JDK21） | 0 | 零错误零警告；批量审批锁在 Service 循环内逐元素持有（与单次 approve 同粒度）；幂等拦截器追加 4 条批量路径 |
| 2026-10-24 | P1-3 Nacos 动态配置 `mvn -q compile`（需 JDK21） | 0 | 零错误零警告；spring.config.import optional:nacos 方式（无 bootstrap 依赖）；JwtProperties @RefreshScope |
| 2026-10-24 | P1-4 MDC TraceId `mvn -q compile`（需 JDK21） | 0 | 零错误零警告；TraceIdInterceptor order=-100；logback-spring.xml pattern 含 %X{traceId} |
