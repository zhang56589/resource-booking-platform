# 会议室预约中台 · 后端（admin_system_backend）

> 本文件只写后端专属内容：Nacos、数据库与表结构、Knife4j、分布式锁、MQ / Outbox、定时任务。  
> 项目介绍、功能列表、整体技术栈、快速启动与项目亮点见 [根 README](../README.md)。

## 一、工程与配置概览

| 项 | 值 |
| --- | --- |
| 构建产物 | `target/adminsystem-0.0.1-SNAPSHOT.jar` |
| 默认端口 | `8080` |
| 接口前缀 | `/api` |
| 统一响应体 | `Result{ code, msg, data }`，成功 `code=200` |
| 分页上限 | 预约分页 `Math.min(pageSize, 100)`；会议室分页无上限 |
| 事务隔离级别 | `TRANSACTION_REPEATABLE_READ`（`spring.datasource.hikari.transaction-isolation`） |

核心包结构：

```
com.example.adminsystem
├─ annotation / aspect       # @RequireAdmin、@RequiresPermission + PermissionAspect
├─ config                    # WebConfig、Knife4jConfig、GlobalExceptionHandler、JwtProperties 等
├─ controller                # Login / MeetingBooking / MeetingRoom / Schedule / Menu / User / Log
├─ dto / entity / vo / enums
├─ interceptor               # TokenInterceptor（红线）、IdempotentInterceptor、TraceIdInterceptor
├─ mapper                    # MyBatis-Plus Mapper + XML
├─ plugin                    # ScheduleRulePlugin、PluginRegistry、ScheduleManager、TimeExclusivePlugin
├─ scheduler                 # OutboxScheduler（轮询转发）、BookingExpireScheduler（过期取消）
├─ plugin/support            # BookingOutboxWriter（事务内写本地消息表）
├─ service / service.impl    # ScheduleTxService（事务边界）、GeneralScheduleService 等
└─ util                      # Result、BusinessException、IpUtil、JwtUtil、PasswordUtil
```

## 二、Nacos（可选）

### 1. 服务注册发现

```yaml
spring:
  application:
    name: admin-system-backend
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        service: ${spring.application.name}
        ephemeral: true
```

- 多实例使用相同 `spring.application.name`、不同 `server.port`，即可在同一 Nacos 命名空间下组成集群。
- 单机 Nacos 建议 `ephemeral: false`，集群 Nacos 使用 `true`。
- 默认 `fail-fast=false`：Nacos 不可达时注册失败只记录日志，应用仍可完成启动（单机 / 简历演示场景）。

### 2. 配置中心

```yaml
spring:
  config:
    import:
      - optional:nacos:admin-system-backend.yaml?group=DEFAULT_GROUP
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
```

- `optional:` 前缀是**降级关键**：Nacos 不存在、不可达或没有对应 dataId 时，直接使用本地 `application.yml` 默认值启动，不会启动失败。
- 可在 Nacos 控制台维护 `dataId=admin-system-backend.yaml`、`group=DEFAULT_GROUP`，覆盖 `jwt`、`rocketmq` 等配置。
- `JwtProperties` 标注 `@RefreshScope`（`prefix = "jwt"`），修改 JWT 配置可热刷新。
- `rocketmq.name-server` 由 `@ConditionalOnProperty` 在启动期判断，修改后需重启应用才会装载 / 卸载消息组件。

### 3. 降级行为对照

| 场景 | 行为 |
| --- | --- |
| Nacos 未启动 | `optional:` 配置导入跳过；注册失败仅告警；应用正常启动 |
| Nacos 启动后 | 自动注册服务；从配置中心拉取 `admin-system-backend.yaml`（存在时覆盖本地） |
| 配置中心无该 dataId | 使用本地默认值，不报错 |
| 修改 RocketMQ 配置 | 需重启（条件装配在启动期完成） |

## 三、数据库

### 1. 连接配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/admin_system?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      transaction-isolation: TRANSACTION_REPEATABLE_READ
```

### 2. 脚本执行顺序

| 顺序 | 脚本 | 用途 | 是否必须 |
| --- | --- | --- | --- |
| 1 | `docs/schema.sql` | 建 16 张表 + 角色 / 权限 / 菜单 / 账号预置 | 必须（新库） |
| 2 | `docs/migrate_v2.sql` | 老表数据迁移到资源内核（可重复执行） | 老库升级 |
| 3 | `docs/mainline2_index.sql` | 补充索引 | 老库升级 |
| 4 | `docs/seed.sql` | 重置基础业务数据 | 可选 |
| 5 | `docs/seed_v2.sql` | 工位资源样板（`WORKSTATION`） | 可选 |

### 3. 逻辑删除与索引

- `meeting_room`、`meeting_booking`、`resource_type`、`resource_instance`、`schedule_booking`、`schedule_booking_item` 均有 `deleted` 列，实体使用 `@TableLogic`，删除变为 UPDATE。
- 预约高频查询索引：
  - `meeting_booking`：`(room_id, start_time, end_time)`
  - `schedule_booking`：`(resource_instance_id, start_time, end_time)`、`(user_id, status)`、`(resource_type_id, start_time)`，另有 `booking_no` 唯一索引
  - `schedule_booking_item`：`(booking_id)`
  - `sys_outbox`：`msg_id` 唯一索引、`(status, next_retry_time)` 扫描索引

## 四、表结构（16 张）

### 1. 基础业务

| 表 | 说明 | 关键字段 |
| --- | --- | --- |
| `sys_user` | 系统用户 | `username`（唯一）、`password`（BCrypt）、`real_name`、`phone`、`role`（0 员工 / 1 管理员）、`enabled` |
| `meeting_room` | 会议室（老业务表） | `room_name`、`capacity`、`location`、`description`、`enabled`（实体映射为 `status`）、`deleted` |
| `meeting_booking` | 会议室预约（老业务表，兼容保留） | `room_id`、`user_id`、`title`、`start_time`、`end_time`、`status`（0/1/2/3）、`reject_reason`、`reason`、`deleted` |
| `operation_log` | 操作日志 | `user_id`、`username`、`operation`、`method`、`params`、`ip`、`cost_time` |
| `sys_login_log` | 登录日志 | `username`、`ip`、`status`（0 成功 / 1 失败）、`remark`、`login_time` |
| `sys_operate_log` | 管理员关键操作日志 | `operator_id`、`operator_name`、`target_id`、`operate_type`、`ip`、`operate_time` |

### 2. 资源调度内核

| 表 | 说明 | 关键字段 |
| --- | --- | --- |
| `resource_type` | 资源类型字典 | `type_code`（唯一，如 `MEETING_ROOM` / `WORKSTATION`）、`type_name`、`plugin_code`（二级路由依据）、`approval_template_code`、`enabled`、`deleted` |
| `resource_instance` | 资源实例 | `type_id`、`instance_code`（类型内唯一）、`instance_name`、`capacity`、`location`、`attributes_json`（JSON 差异化属性）、`status`（0 禁用 / 1 启用）、`deleted` |
| `schedule_booking` | 统一预约主表 | `booking_no`（唯一）、`resource_type_id`、`resource_instance_id`、`user_id`、`start_time`、`end_time`、`quantity`、`status`（0/1/2/3，实体映射为 `resStatus`）、`plugin_code`、`source`（`LEGACY` / `API`）、`ext_json`、`deleted` |
| `schedule_booking_item` | 预约明细 | `booking_id`、`item_type`（`SLOT` / `SEAT` / `QUOTA`）、`item_key`、`quantity`、`unit`、`ext_json`、`deleted` |

### 3. 可靠消息

| 表 | 说明 | 关键字段 |
| --- | --- | --- |
| `sys_outbox` | 本地消息表 | `msg_id`（UUID，唯一）、`topic`、`tag`、`payload`、`status`（0 待发送 / 1 已发送 / 2 死信 / 3 处理中）、`retry_count`、`max_retry`、`next_retry_time` |

### 4. 权限与菜单

| 表 | 说明 | 关键字段 |
| --- | --- | --- |
| `sys_role` | 角色 | `id`（与 `sys_user.role` 对齐）、`code`（`employee` / `admin`）、`name` |
| `sys_permission` | 权限点 | `code`（`模块:动作`，如 `booking:approve`）、`name` |
| `sys_role_permission` | 角色-权限 | `role_id`、`permission_id` |
| `sys_menu` | 动态菜单 | `parent_id`、`name`、`path`、`component`、`icon`、`permission`、`order_num` |
| `sys_role_menu` | 角色-菜单 | `role_id`、`menu_id` |

### 5. 枚举对照

| 枚举 | 取值 |
| --- | --- |
| `BookingStatusEnum` | 0 待审批 / 1 已通过 / 2 已驳回 / 3 已取消 |
| `RoomStatusEnum` | 0 禁用 / 1 启用（DB 列 `enabled`） |
| `UserRoleEnum` | 0 员工 / 1 管理员 |
| `ScheduleActionEnum` | `BOOK` / `APPROVE` / `REJECT` / `CANCEL` |

## 五、Knife4j / OpenAPI

- 依赖：`springdoc-openapi-starter-webmvc-ui`、`springdoc-openapi-starter-webmvc-api`，版本 `2.6.0`。
- 配置类：`config/Knife4jConfig`，声明 `OpenAPI` 元信息（标题、版本、描述）。
- 访问地址：

| 用途 | 地址 |
| --- | --- |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |

- 文档路径已在 `WebConfig.excludePathPatterns` 中放行：`/swagger-ui/**`、`/v3/api-docs/**`，无需 Token。
- 其余接口默认经过 `TokenInterceptor`，调用时需携带 `token` / `Authorization: Bearer <token>`。

## 六、分布式锁

### 1. 锁 Key 与粒度

| 场景 | Key | 粒度 | 说明 |
| --- | --- | --- | --- |
| 预约提交 / 通用预约 | `schedule:room:{resourceInstanceId}` | 资源实例 | 同一实例全部提交串行化；按「实例+时段」组合会漏判冲突 |
| 审批 | `schedule:approve:{bookingId}` | 预约单 | 不同预约可并行，提高吞吐 |
| 批量审批 | 循环内逐元素持 `schedule:approve:{bookingId}` | 预约单 | 不同时持多把锁，避免死锁 |
| 取消 | 不加分布式锁 | — | 仅状态更新，无时段冲突语义；实例行锁 + 预约行锁足够 |

常量（`MeetingBookingController`）：

```java
LOCK_KEY_ROOM    = "schedule:room:";
LOCK_KEY_APPROVE = "schedule:approve:";
LOCK_WAIT_SECONDS = 5L;
LOCK_LEASE_TIME   = -1L;   // Redisson 看门狗自动续期
```

### 2. 锁与事务顺序

```
Controller.tryLock(5s, -1)
  → ScheduleManager（无 @Transactional，只做锁编排）
    → ScheduleTxService（@Transactional）
      → ScheduleRulePlugin.validate / onBooked
        → MySQL FOR UPDATE（resource_instance / schedule_booking）
    → 事务提交
  → finally unlock
```

固定为**锁在外、事务在内**：事务提交发生在锁释放之前，避免并发请求读到未提交数据。`ScheduleManager` 刻意不加事务注解，防止锁被事务代理包裹。

### 3. 实现细节

- 获取锁：`tryLock(5, -1, TimeUnit.SECONDS)`；超时返回友好提示，不阻塞线程。
- 看门狗：`leaseTime=-1` 时 Redisson 每 10s 续期至 30s，避免业务耗时超过租期导致锁提前释放。
- 解锁：`if (locked && rLock.isHeldByCurrentThread()) rLock.unlock();`，防止看门狗释放后抛 `IllegalMonitorStateException`。
- DB 兜底：插件对 `resource_instance` 与 `schedule_booking` 执行 `FOR UPDATE` 查询；Redis 故障时仍能保证同一实例串行。
- 锁等待超时：`innodb_lock_wait_timeout`（错误码 1205）由 `GlobalExceptionHandler` 转为 `400 系统繁忙，请稍后重试`，不返回 500。

## 七、RocketMQ 与 Outbox

### 1. 可选装配

- `RocketMQTemplate` 在未配置 `rocketmq.name-server` 时不加载。
- `OutboxScheduler` 通过 `@Autowired(required = false)` 注入，为空时任务**直接返回**，`sys_outbox` 保持 `status=0`、`retry_count=0`。
- 配置好 `name-server` 并重启后，5s 内自动补发积压消息。

### 2. 发送链路

```
业务事务 { 写业务表 + INSERT sys_outbox(status=0) } → commit
                                                        ↓
OutboxScheduler（每 5s）
  ① markProcessing(100)：status=0 且到 next_retry_time → status=3（处理中）
  ② selectProcessing()：取出 status=3
  ③ RocketMQTemplate.asyncSend(topic, payload)
       成功 → markSent（status=1）
       失败 → markFailed（retry_count+1，指数退避写 next_retry_time）
              retry_count >= max_retry(3) → status=2 死信
```

### 3. Topic

| Topic | 触发 | 消息体字段 |
| --- | --- | --- |
| `booking-notify-submit` | 预约提交 | `bookingId`、`username`、`roomName`、`startTime`、`endTime`、`operatorId`、`ts` |
| `booking-notify-approve` | 审批完成（PASS / REJECT） | `bookingId`、`applicantUsername`、`result`、`rejectReason`、`adminId`、`ts` |

### 4. 配置与已知缺口

```yaml
rocketmq:
  producer:
    group: booking-producer-group
#  name-server: 127.0.0.1:9876      # 取消注释以启用

booking:
  outbox:
    enabled: true                   # false 可整体停用转发
```

- `selectProcessing()` 取全表 `status=3` 记录，多实例同时轮询可能重复发送；按至少一次投递处理，依赖 `msg_id` 在消费端去重。
- `@ConditionalOnProperty` 在启动期判断，Broker 恢复后需重启应用才会重新装配消息组件。

## 八、定时任务

| 任务 | 类 | 触发 | 开关 | 职责 |
| --- | --- | --- | --- | --- |
| Outbox 转发 | `scheduler/OutboxScheduler` | `cron = "0/5 * * * * ?"`（每 5s） | `booking.outbox.enabled`（默认 true） | 抢占待发送消息、异步发送、失败退避重试、超限死信 |
| 过期预约取消 | `scheduler/BookingExpireScheduler` | `cron = "${booking.expire.cron:0 0/5 * * * ?}"` | `booking.expire.enabled`（默认 true） | 把「待审批且 `start_time < NOW()`」的预约置为已取消 |

```yaml
booking:
  expire:
    enabled: true
    cron: "0 0/5 * * * ?"
  outbox:
    enabled: true
```

补充说明：

- 冲突校验 SQL 同时把过期 `PENDING` 记录视为不占用（懒失效），即使定时任务延迟，也不会阻塞新预约。
- 两个任务均通过 `@ConditionalOnProperty` 控制装配，关闭后不影响主流程。
- 定时任务与业务事务解耦：Outbox 写入在业务事务内，发送在定时任务中，互不阻塞。

## 九、构建与验证

```bash
cd admin_system_backend
mvn clean test          # 单元测试
mvn clean package -DskipTests
java -jar target/adminsystem-0.0.1-SNAPSHOT.jar
```

启动顺序：Redis → MySQL →（可选）Nacos → 后端。Redis 为 Redisson 初始化依赖，需先于应用启动。
