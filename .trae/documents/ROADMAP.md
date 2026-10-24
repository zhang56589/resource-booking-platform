# 会议室预约系统｜Builder 主控路线图

## 基线（阶段0 已完成）

| 项 | 状态 |
|---|---|
| 编译自检（mvn clean compile） | ✅ 零错误零警告 |
| Maven package -DskipTests | ✅ jar 生成 |
| Spring 容器启动 | 🔴 被 Redisson eager 初始化阻断（Redis 未启动）—— 非代码问题 |
| Bean 装配静态审计 | ✅ 30 项通过（见 STATE.md） |
| Controller 返回统一 | ✅ 21 个接口 34 个出口 100% Result 包裹 |
| 分布式锁 | ✅ Controller 层锁包事务，finally + isHeldByCurrentThread 保护 |
| DB FOR UPDATE | ✅ 时间重叠 hasTimeConflict(lt/gt) 双重校验 |
| MQ | ✅ afterCommit 事务后发送 + @ConditionalOnProperty 可选加载 |
| 全局异常 | ✅ BusinessException 最前置 + TransactionSystemException 锁超时业务化 |
| 枚举 | ✅ BookingStatusEnum 0/1/2/3 / RoomStatusEnum / UserRoleEnum |
| TokenInterceptor | ✅ 白名单 + 角色校验 + username 写入 request（**红线：禁止修改**） |
| Redis eager 初始化 | 🟡 维持现状（先起 Redis 再启后端） |

## 绝对禁止修改清单（红线）

1. `TokenInterceptor.java` —— 稳定模块，禁止擅自修改
2. `Result.java` 统一返回体 / `BusinessException` —— 禁止修改
3. `GlobalExceptionHandler.java` 已有的锁超时、业务异常分支 —— 禁止覆盖，新增只能追加
4. `MeetingBookingController.submit` / `approve` **锁在外、事务在内**的基础骨架 —— 仅允许调整锁 key、锁策略，禁止颠倒顺序
5. 不新增业务逻辑、不引入重型中间件（分布式限流、延迟队列、分库分表等）

---

## 阶段序列（依赖顺序：P0 → P1 → P2，强依赖阶段必须按序执行）

### P0-1｜锁粒度拆分优化（高优）✅ **已完成 2026-10-24**
- **目标**：approve 改为预约单粒度锁（booking:id:{bookingId}），submit 保持会议室粒度（booking:room:{roomId}）。理由：approve 是对已存在 booking 单的状态变更，只需要串行化**同一个 bookingId** 的并发审批请求，不需要把整个会议室锁死；submit 仍然需要会议室粒度锁防止同时段冲突
- **影响文件**：`MeetingBookingController.java`（approve 方法锁 key 改 bookingId）、`MeetingBookingServiceImpl.java`（新增注释说明两种锁粒度）
- **依赖**：无

### P0-2｜本地消息表 RocketMQ 可靠消息最终一致性（高优）✅ **已完成 2026-10-24**
- **目标**：新增 `sys_outbox` 表 + 定时任务轮询 outbox 表 → 发 MQ。解决当前 MQ 发送在 afterCommit 回调里但 Producer 异常吞掉导致"预约成功但通知永远没发"的隐患（DB 提交成功但 MQ 发送失败会静默跳过）
- **影响文件**：`docs/schema.sql`（sys_outbox CREATE TABLE）、新增 `SysOutbox` 实体 + Mapper + XML + Service + Impl、新增 `OutboxScheduler` 定时任务（`@Scheduled(cron="0/5 * * * * ?")`）、`MeetingBookingServiceImpl`（submitBooking + approve 事务内 outbox save 替代 afterCommit Producer，删除 runAfterCommit + bookingNotificationProducer）、`AdminSystemBackendApplication`（加 `@EnableScheduling`）
- **依赖**：P0-1（锁粒度确认后再改 outbox，避免锁内 MQ 逻辑漂移）
- **实现要点**：MySQL 5.7 无 FOR UPDATE SKIP LOCKED → markProcessing 用 UPDATE status=3 + LIMIT 抢占 → 再 SELECT WHERE status=3；SendCallback 为抽象类 → 用匿名类而非 lambda；RocketMQTemplate @Autowired(required=false) 优雅降级
- **隐患消除**：B-2 ✅ 已修复 —— outbox save 失败抛 BusinessException 触发事务回滚，保证"预约成功但通知永远没发"不再发生

### P0-3｜RBAC 细粒度权限改造（高优）✅ **已完成 2026-10-24**
- **目标**：新增 `@RequiresPermission("模块:动作")` 细粒度权限注解 + `PermissionAspect` AOP 切面（查 sys_role_permission 映射），与现有 `@RequireAdmin` + TokenInterceptor 拦截两层并行兼容
- **影响文件**：`docs/schema.sql`（追加 3 张表 + 预置数据）、新增 `SysRole` / `SysPermission` / `SysRolePermission` 3 个实体、3 个 Mapper + `SysRolePermissionMapper.xml`、新增 `@RequiresPermission` 注解、新增 `PermissionAspect` 切面、`MeetingBookingController`（移除 `requireAdminId` inline 双重检查）、`pom.xml`（追加 `spring-boot-starter-aop`）
- **依赖**：无
- **方案修正（与原 ROADMAP 差异）**：不修改 TokenInterceptor（红线），不改 SysUser.role 字段类型（保持 Integer），AOP 层直接读 request.getAttribute("role") 的 Integer 值查映射表；TokenInterceptor 对 @RequireAdmin 的校验保持不动，两层独立并行
- **实现要点**：PermissionAspect 在 TokenInterceptor preHandle 之后执行 → roleId 已写入 request → 查 sys_role_permission JOIN sys_permission → 匹配权限码；SysRole.id 与 SysUser.role 对齐（0=employee, 1=admin），后续扩展 auditor(2) 只需 INSERT 新角色 + 映射
- **Mapper XML**：SysRolePermissionMapper 自定义 `selectPermissionCodesByRoleId`（INNER JOIN sys_permission）

### P0-4｜幂等拦截器 RequestId 防重复提交（高优）✅ **已完成 2026-10-24**
- **目标**：新增 `IdempotentInterceptor` + Redisson SETNX（key=`idempotent:{userId}:{requestId}`, TTL=60s）。`X-Request-Id` Header **可选**，存在则校验，不存在则跳过（降级模式）；Redis 不可达 → warn + 放行
- **影响文件**：新增 `IdempotentInterceptor`（Redisson RBucket.trySet 实现 SETNX）、`WebConfig`（在 TokenInterceptor 之后注册，只匹配 11 条写操作路径）
- **依赖**：无（Redisson starter 已在）
- **实现要点**：拦截器注册顺序 = TokenInterceptor 先 → IdempotentInterceptor 后（前者写 userId 到 request，后者读取）；匹配列表只覆盖 POST/PUT/DELETE 写操作，GET 只读接口不受影响；Redis 异常降级放行（幂等是增强不是安全屏障）
- **方案修正**：原 ROADMAP 写"前端必须带 X-Request-Id"→ 修正为可选 Header；原计划 StringRedisTemplate → 实际用 Redisson RBucket（保持与项目现有分布式锁技术栈一致）

### P1-1｜数据库索引优化 + 软删除改造（中优）✅ **已完成 2026-10-24**
- **目标**：① meeting_booking 表加复合索引 (room_id, start_time, end_time) + (user_id, res_status)；② meeting_room / meeting_booking 加 deleted tinyint(1) DEFAULT 0 软删除 + MyBatis-Plus `@TableLogic`；③ operation_log 加索引 (create_time)
- **影响文件**：DDL ALTER TABLE、Entity 加 deleted 字段 + `@TableLogic`、MyBatis XML（如存在手写 join 需加 deleted=0 条件）
- **依赖**：无
- **实施摘要**：索引（idx_booking_room_time / idx_booking_user_status / idx_oplog_create_time）基线 schema.sql 已存在，无需新增；本阶段只补 `meeting_room.deleted` + `meeting_booking.deleted` 两列 + `@TableLogic`；手写 SQL 共 6 处追加 `deleted=0`（2 处 selectByIdForUpdate + 4 处 XML JOIN）；B-6 已修复
- **环境注意**：本机 Maven 默认 JDK 1.8 不支持 pattern matching，须先 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'`

### P1-2｜批量审批、会议室批量启停接口（中优）✅ **已完成 2026-10-24**
- **目标**：新增 `POST /api/booking/batch-approve`（入参 bookingIds: List<Long>, status, rejectReason）、`PUT /api/room/batch-enable` + `PUT /api/room/batch-disable`（入参 ids: List<Long>）。批量操作在一个事务里，每个元素复用单次的业务校验逻辑（通过 Service 循环调用，不直接 foreach updateById 跳过校验）
- **影响文件**：Controller 新增方法 + DTO + Service 新增批量方法（复用单次校验）
- **依赖**：无
- **实施摘要**：新增 `BookingBatchApproveDTO` / `RoomBatchDTO`；批量审批锁粒度与单次 approve 一致（`booking:approve:{bookingId}`），Service 循环内逐元素 tryLock/unlock（不同时持多锁，无死锁）；会议室批量操作复用单次 enable/disable/delete 全部校验（含未完结预约拦截）；Controller 加 `@RequireAdmin` + `@RequiresPermission` 双层校验；幂等拦截器追加 4 条批量路径

### P1-3｜Nacos @RefreshScope 动态配置抽离（中优）✅ **已完成 2026-10-24**
- **目标**：把 application.yml 里的 `jwt.secretKey` / `jwt.expireMinutes` / `redisson.*` / `rocketmq.*` 抽到 Nacos 配置中心（dataId=admin-system-backend.yaml, group=DEFAULT_GROUP）。配置类加 `@RefreshScope` 热刷新
- **影响文件**：`JwtProperties` 加 `@RefreshScope`、application.yml 改为 bootstrap.yml + 移除对应 key、Nacos 配置初始化脚本
- **依赖**：无（但需要 Nacos 启动）
- **实施摘要**：pom.xml 加 `spring-cloud-starter-alibaba-nacos-config`；application.yml 用 `spring.config.import: optional:nacos:admin-system-backend.yaml?group=DEFAULT_GROUP`（非 bootstrap.yml 方式，省一个 starter 依赖）；jwt / rocketmq 本地默认值保留作 Nacos 宕机兜底；`JwtProperties` 加 `@RefreshScope`
- **方案修正**：bootstrap.yml → spring.config.import；移除本地 key → 保留本地默认值

### P1-4｜MDC 全链路 TraceId 埋点（中优）✅ **已完成 2026-10-24**
- **目标**：新增 `TraceIdInterceptor`（Servlet 层，优先级高于 TokenInterceptor）+ `MDCUtil`。入站请求生成 TraceId（UUID）写入 MDC + response Header `X-Trace-Id`；出站 Redis / DB / MQ 调用自动带 TraceId。logback-spring.xml 配置 pattern 含 `%X{traceId}`
- **影响文件**：新增 `TraceIdInterceptor`、`WebConfig` 注册、新增 `logback-spring.xml`（如存在则追加 pattern）
- **依赖**：无
- **实施摘要**：新增 `TraceIdInterceptor`（入站继承或生成 X-Trace-Id → MDC + 响应头；afterCompletion 清 MDC 防线程池串号）；新增 `logback-spring.xml`（pattern `%d{...} [%X{traceId:-}] [%thread] %-5level %logger{36} - %msg%n`）；WebConfig order=-100 先于 TokenInterceptor；前端 request.js 带 X-Trace-Id 属 P1-5 顺带事项

### P1-5｜前端独立阶段：动态菜单、按钮权限 v-permission（中优）✅ **已完成 2026-10-24**
- **目标**：登录后调后端 `/api/user/menu` 返回菜单树（role 过滤）；Element Plus 侧边栏由菜单树动态渲染；自定义 `v-permission` 指令（如 `v-permission="'booking:approve'"`）控制按钮显隐；router/index.js 改为动态 addRoute
- **影响文件**：前端 `router/index.js`、`api/auth.js`、新增 `permission.js` 指令、新增后端 `MenuController` + `SysMenu` 实体
- **依赖**：P0-3（RBAC 先做好，动态菜单才有数据来源）
- **实施摘要**：后端新增 sys_menu + sys_role_menu 两张表（DDL 入 schema.sql）+ MenuController `GET /api/user/menu` 返回 `{menus, permissions}`；前端 permission.js 指令 + Login 拉取存 localStorage + Home.vue 数据驱动侧边栏（兜底静态菜单）+ request.js X-Trace-Id；**方案修正**：保留静态路由表 + meta.role 守卫（不重写为 addRoute，降低风险），不引入 pinia（存 localStorage）

### P2-1｜收尾：README / 接口文档 / SQL 脚本汇总（低优）✅ **已完成 2026-10-24**
- **目标**：所有阶段完成后，README 最终对齐、OpenAPI 文档校验（Knife4j `/doc.html`）、6 张业务表 + 新增表的 CREATE TABLE SQL 统一输出到 `docs/schema.sql`
- **影响文件**：`README.md`、新增 `docs/schema.sql`
- **依赖**：所有 P0 / P1 阶段
- **实施摘要**：README 对齐 16 项核心能力 + 4 个批量接口 + /api/user/menu 接口清单 + 亮点一（双粒度锁）/亮点三（Outbox）改写 + JDK 21 编译提示 + 12 张表部署说明；新增 `docs/seed.sql`（sys_user 3 账号 BCrypt 真实 hash + RBAC + 菜单 + 演示会议室）；修复 schema.sql sys_user 列名 `nickname`→`real_name`（实体映射不一致 bug）；schema.sql 补 sys_user 预置账号；核对 12 张表 CREATE 齐全

---

## 阶段依赖图

```
P0-1 锁粒度  ──→  P0-2 本地消息表
                 
P0-3 RBAC  ──→  P1-5 前端动态菜单

P0-4 幂等 （独立，不阻塞任何阶段）

P1-1 索引软删 / P1-2 批量接口 / P1-3 Nacos / P1-4 TraceId （全独立）

所有 P0 + P1 ──→  P2-1 收尾
```

## 预计影响文件统计

| 阶段 | 新增文件 | 修改文件 |
|---|---|---|
| P0-1 | 0 | 2 |
| P0-2 | 4 | 1 |
| P0-3 | 5 | 2 + DDL |
| P0-4 | 2 | 1 |
| P1-1 | 0 | Entity XML + DDL |
| P1-2 | 2 | 2 |
| P1-3 | 1 | 3 |
| P1-4 | 2 | 1 |
| P1-5 | 前端 5 + 后端 2 | 前端 3 |
| P2-1 | 1 docs/schema.sql | README.md |
