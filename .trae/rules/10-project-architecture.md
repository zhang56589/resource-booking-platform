# 10 · 项目架构与编码约定

面向后续编程体的长期上下文。目标是：改代码前先知道「请求怎么走、锁在哪、事务在哪、数据写到哪」，避免误改内核与红线。

## 一、一句话架构

前后端分离的资源预约中台：Vue3（`meeting-vue`，文档中称 frontend）+ Spring Boot 3.3.4（`admin_system_backend`，称 backend）。后端以**资源类型 + 资源实例 + 调度插件**承载会议室、工位等资源，固定「锁在外、事务在内」。

```
frontend（Vite 代理 /api）
  → Controller（外层 Redisson 锁）
    → Service 薄门面（DTO → ScheduleContext）
      → ScheduleManager（解析插件 + 加锁 + 调事务 Bean + 解锁，无事务）
        → ScheduleTxService（@Transactional）
          → ScheduleRulePlugin（validate → onBooked / onRejected / onCancelled）
            → Mapper / MySQL（FOR UPDATE 兜底）
          → 事务提交
    → finally 解锁
```

## 二、内核组件

| 组件 | 路径（`com.example.adminsystem`） | 职责 |
| --- | --- | --- |
| `ScheduleRulePlugin` | `plugin/ScheduleRulePlugin` | 插件接口：`pluginCode / supports / buildLockKey / validate / onBooked / onRejected / onCancelled` |
| `PluginRegistry` | `plugin/PluginRegistry` | 按 `supports(typeCode)` 路由；未命中再查 `resource_type.plugin_code` 二级路由；都没有抛 400 |
| `ScheduleManager` | `plugin/ScheduleManager` | `book / approve / cancel`：解析插件 → `buildLockKey` → `tryLock(5s, -1)` → 调 `ScheduleTxService` → `finally` 解锁；lockKey 为空则直接执行 |
| `ScheduleContext` | `plugin/ScheduleContext` | 内核唯一数据载体，扁平字段，不依赖具体资源实体 |
| `ScheduleTxService` | `service/ScheduleTxService` | 事务边界：`book / approve / cancel`，只做事务与插件调用 |
| `TimeExclusivePlugin` | `plugin/impl/TimeExclusivePlugin` | 时段独占规则：会议室与工位共用；`pluginCode=TIME_EXCLUSIVE` |
| `BookingOutboxWriter` | `plugin/support/BookingOutboxWriter` | 事务内写 `sys_outbox`（提交通知、审批通知） |

插件接口要点：

| 方法 | 约定 |
| --- | --- |
| `pluginCode()` | 全局唯一，与 `resource_type.plugin_code` 对应 |
| `supports(typeCode)` | 直接支持的类型编码；不命中时由 Registry 走二级路由 |
| `buildLockKey(ctx)` | 返回锁 key；返回 `null/空` 表示该动作不加分布式锁（如 CANCEL） |
| `validate(ctx)` | 按 `ctx.action` 分派 BOOK / APPROVE 校验；失败抛 `BusinessException` |
| `onBooked(ctx)` | 写预约主表 + 明细 + outbox |
| `onRejected(ctx)` / `onCancelled(ctx)` | 驳回 / 取消后的资源侧动作，事务内执行 |

## 三、两条调用链

### 1. 会议室（兼容老入口）

```
POST /api/booking/submit
  MeetingBookingController.submit
    → 抢 schedule:room:{instanceId}（外层）
    → MeetingBookingServiceImpl.submitBooking（DTO → ctx）
      → ScheduleManager.book（同 key 可重入）
        → ScheduleTxService.book → TimeExclusivePlugin
DELETE /api/booking/cancel/{id}
  MeetingBookingController.cancel（无分布式锁）
    → MeetingBookingServiceImpl.cancelBooking → ScheduleManager.cancel（buildLockKey 返回 null）
POST /api/booking/approve
  Controller 抢 schedule:approve:{bookingId}（外层）
    → Service.approve → ScheduleManager.approve（同 key 可重入）
POST /api/booking/batch-approve
  Service 在一个事务内循环，逐元素走 approve 语义
```

### 2. 通用入口（主线4）

```
POST /api/schedule/book
  ScheduleController.book
    → GeneralScheduleServiceImpl.book（校验实例归属资源类型）
      → ScheduleManager.book → 插件
DELETE /api/schedule/cancel/{id}
  ScheduleController.cancel
    → 由 schedule_booking.resource_type_id 反查 typeCode
      → ScheduleManager.cancel → 插件
```

## 四、关键机制

### 1. 锁

- 提交 / 通用预约：`schedule:room:{resourceInstanceId}`（实例粒度）。
- 审批：`schedule:approve:{bookingId}`（预约单粒度）。
- 取消：不加分布式锁，仅靠 DB 行锁 + 状态/归属校验。
- `leaseTime=-1` 启用 Redisson 看门狗；解锁前判断 `isHeldByCurrentThread()`。
- 锁等待 5s，失败返回 400；线程中断恢复中断标志后返回 400。
- DB 兜底：`TimeExclusivePlugin` 对 `resource_instance`、`schedule_booking` 执行 `FOR UPDATE`。
- MySQL 锁等待超时（errorCode 1205）由 `GlobalExceptionHandler` 转 400「系统繁忙，请稍后重试」。

### 2. 事务与锁顺序

- `ScheduleManager` **不加** `@Transactional`，只编排锁。
- 业务事务只在 `ScheduleTxService`（及其调用的插件）内。
- 顺序不可颠倒：`tryLock → 事务 → commit → finally unlock`。

### 3. Outbox / MQ

- `BookingOutboxWriter` 在业务事务内写 `sys_outbox(status=0)`。
- `scheduler/OutboxScheduler` 每 5s：`markProcessing(100)` → `selectProcessing()` → `asyncSend`。
- 成功 `status=1`；失败 `retry_count+1` 并指数退避；`retry_count >= max_retry(3)` → `status=2` 死信。
- MQ 未配置（`RocketMQTemplate` 为 null）时任务直接 return，记录保持 `status=0`、`retry_count=0`。
- Topic：`booking-notify-submit`、`booking-notify-approve`。
- 已知缺口：`selectProcessing()` 取全表 `status=3`，多实例可能重复发送，消费端按 `msg_id` 去重。

### 4. 过期释放

- 冲突 SQL 对 `status=0 AND start_time < NOW()` 懒失效。
- `scheduler/BookingExpireScheduler` 每 5 分钟把过期 PENDING 置为取消（`booking.expire.enabled` / `booking.expire.cron`）。

### 5. 幂等 / TraceId / 权限

- 幂等：`IdempotentInterceptor` 读 `X-Request-Id`，Redisson `trySet`，key `idempotent:{userId}:{requestId}`，TTL 60s；无 header 放行。只拦截写接口路径（含 `/api/schedule/*`）。
- TraceId：`TraceIdInterceptor` order=-100，入站继承或生成 `X-Trace-Id` → MDC + 响应头。
- 权限：`@RequireAdmin`（TokenInterceptor）+ `@RequiresPermission("模块:动作")`（`PermissionAspect`）双轨；权限码来自 `sys_role_permission`。

## 五、数据模型

| 分组 | 表 | 说明 |
| --- | --- | --- |
| 基础 | `sys_user` / `meeting_room` / `meeting_booking` | 用户、会议室老表、预约老表（兼容保留） |
| 日志 | `operation_log` / `sys_login_log` / `sys_operate_log` | 操作 / 登录 / 管理员关键操作 |
| 内核 | `resource_type` / `resource_instance` / `schedule_booking` / `schedule_booking_item` | 类型字典、实例、统一预约、明细 |
| 消息 | `sys_outbox` | 本地消息表 |
| 权限菜单 | `sys_role` / `sys_permission` / `sys_role_permission` / `sys_menu` / `sys_role_menu` | RBAC + 动态菜单 |

字段映射易错点：

- `meeting_room.enabled` ↔ 实体 `status`（`@TableField("enabled")`）。
- `meeting_booking.status` ↔ `resStatus`；`schedule_booking.status` ↔ `resStatus`。
- `enum`：预约状态 0 待审批 / 1 通过 / 2 驳回 / 3 取消；角色 0 员工 / 1 管理员。

## 六、编码约定

1. 状态一律用枚举或常量，禁止数字硬编码。
2. 入参 DTO 使用 `@Valid` + `@NotBlank/@NotNull`；业务失败抛 `BusinessException(code, msg)`，不裸抛 500。
3. 新增资源优先「加字典 + 加实例」，复用现有插件；只有规则不同才新增插件实现。
4. 新增调度动作必须走 `ScheduleManager` → `ScheduleTxService`，不得自己写锁或事务。
5. DB 变更必须同时给 `docs/schema.sql` 与迁移脚本，存量字段不做破坏性修改。
6. 前端接口统一放 `src/api/*.js`，组件不直接写 Axios；写请求依赖 `request.js` 注入幂等头。
7. 前端路由 `meta.role` 是菜单与访问控制的唯一依据；后端 `sys_role_menu` 只负责下发，不决定可见性。

## 七、改动前必读

- 是否触及红线文件（`TokenInterceptor` / `Result` / `BusinessException` / `GlobalExceptionHandler` 既有分支）。
- 是否改变了锁粒度或锁与事务顺序。
- 是否绕过插件直接写 `schedule_booking`。
- 是否忘记同步 `resource_type.plugin_code` 与插件 `pluginCode()`。
- 是否忘记给写接口补幂等路径与权限注解。
