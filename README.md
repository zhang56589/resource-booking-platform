# 会议室预约管理系统 README

## 项目简介

会议室预约管理系统，**前后端分离架构**，面向企业内部使用，完成分布式改造，支持多实例集群部署。
后端采用 Spring Boot 3.3.4 + Spring Cloud Alibaba，使用 **Redisson分布式锁（Controller层锁包事务） + MySQL FOR UPDATE行锁** 双重机制解决多实例并发预约冲突；基于Nacos实现服务注册发现与**配置中心热刷新（@RefreshScope）**；RocketMQ 预约通知采用**本地消息表（Outbox Pattern）+ 定时任务补发**实现可靠消息最终一致性；权限体系为 **RBAC 细粒度权限（@RequiresPermission + AOP）** 与 @RequireAdmin 双轨并行；写操作接口带**幂等拦截（X-Request-Id + Redisson SETNX）** 防重复提交；会议室/预约**软删除（@TableLogic）**；全链路 **MDC TraceId** 日志追踪；支持**批量审批/批量启停/批量删除**。
前端基于 Vue3 + Vite + Element Plus，提供工作台、预约日历、会议室管理、预约审批、用户管理、操作日志等完整页面，**侧边栏菜单由后端 `/api/user/menu` 动态下发**，按钮级权限由 **v-permission 指令** 控制，交互体验优化（侧边栏折叠、双视图展示、批量操作、二次确认、防重复提交）。

> 
> 后端工程：`admin_system_backend`
> 前端工程：`meeting-vue`

## 技术栈

### 后端

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| JDK | 21 |  |
| Spring Boot | 3.3.4 | parent工程 |
| Spring Cloud | 2023.0.3 | BOM依赖管理 |
| Spring Cloud Alibaba | 2023.0.3.2 | Nacos服务注册发现 |
| MyBatis-Plus | 3.5.5 | SpringBoot3专用starter |
| MySQL | 5.7+ | 业务数据库 |
| Redisson | 3.37.0 | redisson-spring-boot-starter，自动装配RedissonClient |
| RocketMQ | 2.3.1 | rocketmq-spring-boot-starter，可选组件 |
| Nacos | 2.x | 服务注册发现中心 |
| JJWT | 0.12.6 | JWT签名鉴权 |
| BCrypt | Spring Security 6.3.4 | 用户密码加密 |
| Hutool | 5.8.34 | JSON工具、通用工具类 |
| Knife4j / OpenAPI | 2.6.0 | springdoc-openapi，Swagger 文档 |

### 前端

| 类别 | 选型 |
| --- | --- |
| 框架 | Vue 3（`<script setup>` 组合式 API） |
| 构建 | Vite 8 |
| UI | Element Plus 2.14（中文 locale） |
| 图标 | @element-plus/icons-vue |
| 图表 | ECharts 6（按需引入，仅工作台使用） |
| 路由 | Vue Router 4（历史模式 + 懒加载 + 全局守卫） |
| 请求 | Axios（统一封装 `src/utils/request.js`） |

## 核心功能

### 后端能力

1. **用户权限管理**：JWT登录鉴权（`TokenInterceptor.java`）、RBAC角色控制，`UserRoleEnum`：0员工 / 1管理员；登录校验用户启用状态，禁用用户禁止登录。`JwtConfig` 提供单例 `SecretKey` Bean，全局只构建一次密钥；`parseToken` 捕获 JJWT `JwtException` 统一抛 `BusinessException(401, "token无效或已过期")`。拦截器 Header 规范：读取 `Authorization: Bearer xxx`；放行路径由 `WebConfig.excludePathPatterns` 统一管理（`/api/login`、`/api/register`、Knife4j 文档），拦截器内部不再维护白名单；全部 `System.out` 替换为 SLF4J（`log.debug` 正常流程 / `log.error` 异常）。`SysUserServiceImpl.login` 失败不再返回 null，改为抛 `BusinessException`：用户不存在 404 / 账号禁用 403 / 密码错误 400。
2. **登录日志**：`SysLoginLogService` 同步写入 `sys_login_log` 表；成功 / 失败都记录 username、IP、status（0成功 / 1失败）、remark、loginTime（MP `FieldFill.INSERT` 自动填充）；IP 优先读 `X-Forwarded-For`。
3. **会议室资源管理**：会议室CRUD操作；删除会议室前置校验：存在未完结预约（待审批/已通过）时禁止删除；支持启用/禁用会议室。
4. **预约完整状态流转**：提交 → 审批(PASS/REJECT) → 取消。状态枚举`BookingStatusEnum`：0待审批 / 1通过 / 2驳回 / 3取消。预约提交后端时间兜底校验：startTime 必须严格早于 endTime（含等于边界拦截）、startTime 不能早于当前时间 LocalDateTime.now()；校验失败抛 BusinessException 由 GlobalExceptionHandler 统一返回。
5. **分布式预约互斥**：Redisson分布式锁 + DB FOR UPDATE双重兜底，保证多实例并发预约不会出现时间重叠冲突。
6. **异步消息推送预约通知**：RocketMQ发送预约提交、审批通知；MQ为可选组件，未配置时自动跳过消息发送，不影响主业务流程。
7. **操作日志记录**：记录用户操作、请求IP、请求方法，支持管理员分页查询。
8. **业务异常统一收敛**：Service 层主动抛出 `BusinessException(code, msg)`，由 `GlobalExceptionHandler` 最靠前分支捕获并返回 `Result.fail(code, msg)`；单参构造器默认 code=400。原有参数校验、404、兜底 Exception 分支保持不变。
9. **RBAC 细粒度权限（P0-3）**：`sys_role` / `sys_permission` / `sys_role_permission` 三张表 + `@RequiresPermission("模块:动作")` 注解 + `PermissionAspect` AOP 切面（从 request 读 role → 联查权限码匹配），与 `@RequireAdmin`（TokenInterceptor）双轨并行；扩展新角色只需 INSERT 角色 + 映射，无需改代码。
10. **可靠消息最终一致性（P0-2）**：`sys_outbox` 本地消息表 + `OutboxScheduler` 定时任务（每 5s 扫描），事务内写 outbox（与业务同生共死），定时任务异步发 MQ，失败自动重试，retry_count >= 3 标记死信；msg_id 用 UUID，消费侧可去重。MQ broker 宕机不影响预约主流程，恢复后自动补发。
11. **幂等防重复提交（P0-4）**：`IdempotentInterceptor` + Redisson `RBucket.trySet`（SETNX+TTL 60s），key=`idempotent:{userId}:{requestId}`；`X-Request-Id` Header **可选**（不传则跳过降级），重复提交返回 400；Redis 不可达时 warn + 放行（幂等是增强不是安全屏障）；只拦截 15 条写操作路径，GET 不受影响。
12. **软删除 + 索引优化（P1-1）**：`meeting_room` / `meeting_booking` 加 `deleted` 列 + `@TableLogic`，删除变 UPDATE，杜绝物理删除外键 CASCADE 风险；`meeting_booking` 复合索引 `(room_id, start_time, end_time)` + `(user_id, res_status)`、`operation_log(create_time)` 支撑高频查询。
13. **批量接口（P1-2）**：`POST /api/booking/batch-approve`、`PUT /api/room/batch-enable|batch-disable`、`DELETE /api/room/batch-delete`；批量在 Service 循环内复用单次业务校验（含逐元素分布式锁），任一失败整体事务回滚，错误信息带失败元素 id。
14. **Nacos 动态配置（P1-3）**：`spring.config.import: optional:nacos:admin-system-backend.yaml`，jwt / rocketmq 配置可放配置中心，`JwtProperties` 加 `@RefreshScope` 热刷新；`optional:` 前缀保证 Nacos 宕机时用本地默认值正常启动。
15. **MDC 全链路 TraceId（P1-4）**：`TraceIdInterceptor`（order=-100，先于 TokenInterceptor）入站继承或生成 `X-Trace-Id` → MDC + 响应头；logback pattern 含 `[%X{traceId:-}]`，同一请求链路日志可用 traceId 串联（含 401 拒绝日志）。
16. **动态菜单 + 按钮权限（P1-5）**：`sys_menu` / `sys_role_menu` 两张表，`GET /api/user/menu` 按角色返回菜单树 + 权限码数组；前端登录后存 localStorage，侧边栏数据驱动渲染（接口失败兜底静态菜单），`v-permission` 指令控制按钮显隐。

### 前端页面能力

1. **登录/注册**：账号密码登录，JWT Token存储，路由守卫拦截未授权访问。
2. **工作台 Dashboard**：统计卡片、预约状态饼图，员工与管理员展示不同统计项。
3. **预约日历 RoomCalendar**：月视图/日视图，预约状态色块展示，点击查看预约详情。
4. **员工端**
   - 可用会议室列表，提交预约表单（时间校验、字数限制、防重复提交）
   - 我的预约：多条件筛选、预约详情、取消预约（待审批/已通过可取消）
5. **管理员端**
   - 会议室管理：表格/卡片双视图，批量启用/禁用，删除前端预校验+后端最终校验
   - 预约审批：多条件日期筛选、批量审批、预约详情查看
   - 用户管理：用户分页、修改角色、启用/禁用用户、重置密码
   - 操作日志：全量操作日志分页查询
6. **布局体验**：侧边栏可折叠、自动面包屑导航；危险操作二次确认，提交按钮loading防重复点击。

## 接口清单

> 
> 统一响应体：`Result{ code, msg, data }`，成功 `code=200`；分页 `data = Page{ records, total }`。
> 所有接口统一前缀 `/api`

### 认证模块 LoginController

| 请求方法 | URL | 入参 | 返回 data | 权限 |
| --- | --- | --- | --- | --- |
| POST | `/api/login` | `{ username, password }` | `{ token, userId, username, role }` | 公开 |
| POST | `/api/register` | `{ username, password, nickname }` | - | 公开 |

### 用户模块 UserController

| 请求方法 | URL | 入参 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/user/page` | `pageNum,pageSize` | 管理员 |
| PUT | `/api/user/role` | `{ id, role }`  `@Valid UserRoleDTO` | 管理员 |
| PUT | `/api/user/status` | `{ id, enabled }`  `@Valid UserStatusDTO` | 管理员 |
| PUT | `/api/user/resetPassword` | `{ id, password }`  `@Valid UserPasswordDTO`；成功后写入 `sys_operate_log` | 管理员 |

### 预约模块 MeetingBookingController

基础路径：`/api/booking`

| 请求方法 | URL | 入参 DTO | 核心逻辑 | 权限 |
| --- | --- | --- | --- | --- |
| POST | `/api/booking/submit` | BookingDTO (roomId, startTime, endTime, title, reason) | **Controller层抢Redisson锁（锁包事务）** → Service @Transactional 参数校验 → DB FOR UPDATE锁定会议室行 → 时间段冲突校验 → 插入预约记录 → MQ afterCommit异步通知 | 登录用户 |
| POST | `/api/booking/approve` | ApproveBookingDTO (bookingId, status, rejectReason) | **Controller层抢Redisson锁（锁包事务）** → Service @Transactional DB FOR UPDATE锁定会议室+预约行 → PENDING状态校验 → 通过时二次校验时间段冲突 → 更新预约状态 → MQ afterCommit异步通知 | 管理员 |
| DELETE | `/api/booking/cancel/{id}` | 路径参数id | DB行锁 → 预约归属校验 → 状态校验（待审批/已通过可取消） | 登录用户 |
| GET | `/api/booking/admin/page` | BookingQueryDTO (roomId, resStatus, startTime, endTime) | XML多条件分页查询预约VO | 管理员 |
| GET | `/api/booking/user/page` | 同上 + 拦截器自动注入userId | 查询当前登录用户的预约记录 | 登录用户 |
| GET | `/api/booking/admin/query` | 同adminPage | adminPage别名接口 | 管理员 |
| GET | `/api/booking/user/query` | 同userPage | userPage别名接口 | 登录用户 |
| POST | `/api/booking/batch-approve` | BookingBatchApproveDTO (bookingIds, status, rejectReason) | Service 循环内逐元素持 `booking:approve:{bookingId}` 锁 + 复用单次 approve 校验；任一失败整体事务回滚 | 管理员 |

### 菜单模块 MenuController（P1-5）

| 请求方法 | URL | 返回 data | 权限 |
| --- | --- | --- | --- |
| GET | `/api/user/menu` | `{ menus: 菜单树（按角色联查 sys_role_menu 过滤）, permissions: 权限码数组 }` | 登录用户 |

> **分布式锁边界**：Redisson锁由 `MeetingBookingController` 持有（Controller tryLock → Service @Transactional 业务 → finally unlock），事务提交在锁释放之前，避免并发请求读到未提交数据。`submit` / `approve` 走分布式锁；`cancelBooking` 只有状态更新、无时段冲突语义，不走分布式锁（DB FOR UPDATE 已足够）。Controller 锁常量 `LOCK_KEY_PREFIX = "booking:room:"` 会议室粒度串行化。

> **Service 异常收敛**：`submitBooking` / `approve` / `cancelBooking` 三个方法返回值为 `void`，所有业务校验失败（参数非空、时间兜底、会议室禁用、时间段冲突、预约归属校验、取消状态校验等）统一抛 `BusinessException` 由 `GlobalExceptionHandler` 兜底返回；锁抢不到、线程中断在 Controller 层 catch，返回友好提示。Controller 不持有业务失败分支，只做路由 + 锁生命周期管理。

> **MQ afterCommit + H2修复**：`runAfterCommit(Runnable)` 私有模板方法统一处理 `TransactionSynchronizationManager.registerSynchronization(afterCommit → runnable.run())`；username / applicantUsername 等 Lambda 内依赖 request.getAttribute 的值在注册前提前 final 提取（afterCommit 时 ThreadLocal 可能已被容器清理）。

### 会议室模块 MeetingRoomController

基础路径：`/api/room`

| 请求方法 | URL | 核心逻辑 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/room/page` | 会议室全量分页查询 | 管理员 |
| POST | `/api/room/add` | `@Valid RoomDTO` 入参 → Service `addRoom` 新增，默认 status=1 | 管理员 |
| PUT | `/api/room/update` | `@Valid RoomDTO` 入参 → Service `updateRoom`，LambdaUpdateWrapper 只更新非空字段 | 管理员 |
| DELETE | `/api/room/delete/{id}` | ⚠️ 前置校验下沉 Service：若 `meeting_booking` 存在该 roomId 且状态为 0/1 的记录，抛 BusinessException 禁止删除 | 管理员 |
| GET | `/api/room/list` | 查询 enabled=1 可用会议室（前端预约弹窗数据源） | 登录用户 |
| PUT | `/api/room/enable/{id}` | Service `enableRoom`，失败抛 BusinessException | 管理员 |
| PUT | `/api/room/disable/{id}` | Service `disableRoom`，失败抛 BusinessException | 管理员 |
| PUT | `/api/room/batch-enable` | RoomBatchDTO (ids)，循环复用单次校验，任一失败整体回滚 | 管理员 |
| PUT | `/api/room/batch-disable` | 同上 | 管理员 |
| DELETE | `/api/room/batch-delete` | 同上，复用未完结预约前置校验 | 管理员 |

### 操作日志 LogController

| 请求方法 | URL | 入参 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/log/page` | `pageNum,pageSize` | 管理员 |

## 分布式改造亮点

### 亮点一：Redisson分布式锁解决多实例预约并发冲突

**代码位置**：`MeetingBookingController.submit` / `approve`（Controller层抢锁）→ `MeetingBookingServiceImpl`（Service层只持有事务内DB逻辑，不再处理分布式锁）
**锁Key策略（P0-1 已拆分双粒度）**：
- `submit`：`booking:room:{roomId}` 会议室粒度锁（防同时段冲突必须串行化同会议室全部请求）
- `approve`：`booking:approve:{bookingId}` 预约单粒度锁（只需串行化同一 bookingId 的并发审批；不同 bookingId 可并行，不再互相阻塞）
- `batch-approve`（P1-2）：Service 循环内逐元素持 `booking:approve:{bookingId}` 锁（不同时持多锁，无死锁）

> 
> submit 为何不用会议室+时间段粒度：两个时间重叠但起止时间不同的预约会生成不同锁key，并发请求可同时获取锁，进入数据库校验后产生时间重叠冲突。会议室粒度锁将同一会议室所有预约请求串行执行，从源头规避漏判。

**锁事务顺序（锁包事务，防止读到未提交数据）**：

```
Controller tryLock → Service @Transactional 业务 → 事务提交 → Controller finally unlock
                                                     ↑ 事务在锁释放前已提交，并发请求不会读到未提交数据
```

tryLock代码（MeetingBookingController 常量定义）：

```
LOCK_KEY_PREFIX  = "booking:room:"     // 会议室粒度
LOCK_WAIT_SECONDS = 5L                 // 抢锁最大等待时间
LOCK_LEASE_TIME  = -1L                 // 启用Redisson watch dog 自动续期
```

- waitTime = 5s：抢锁最大等待时间，超时返回提示：该会议室正在被他人预约，请稍后重试
- leaseTime = -1L：启用Redisson看门狗自动续期，默认每10s续期至30s，防止业务执行时间超过锁超时时间导致锁提前释放

**DB FOR UPDATE兜底**：
获取分布式锁后，Service层对`meeting_room`和`meeting_booking`执行行锁查询。即使Redis故障导致锁丢失，数据库行锁依旧可以串行化同会议室事务，保障最终一致性。

**解锁保护**：

```
if (locked && rLock.isHeldByCurrentThread()) {
    rLock.unlock();
}
```

判断锁持有者，避免看门狗释放锁后业务线程解锁抛出`IllegalMonitorStateException`异常。

**cancelBooking 不上分布式锁**：取消预约只有状态更新语义，无"时段冲突"概念，DB FOR UPDATE行锁 + 业务前置校验（归属校验 + 状态校验 + 会议未开始校验）已足够。

### 亮点二：Nacos多实例水平扩容

配置文件 `application.yml`

```
spring:
  application:
    name: admin-system-backend
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        service: ${spring.application.name}   # 多实例使用相同服务名，组成集群
        ephemeral: true                       # 单机Nacos使用false；Nacos集群环境true
```

**多实例启动命令**

```
# 实例1
java -jar admin_system_backend.jar --server.port=8080
# 实例2，服务名相同，端口不同，自动注册至同一集群
java -jar admin_system_backend.jar --server.port=8081
```

Nacos完成服务注册发现，配合网关可基于服务名负载均衡转发请求到后端实例；Redisson锁保证跨实例下同一会议室预约串行执行。

### 亮点三：RocketMQ 可靠消息最终一致性（Outbox Pattern，可选组件）

**代码位置**：`sys_outbox` 表 + `OutboxScheduler` 定时任务（`@Scheduled(cron="0/5 * * * * ?")`）+ `BookingNotificationProducer`
**可选加载机制**：Producer Bean仅当配置`rocketmq.name-server`才会创建；OutboxScheduler 中 `@Autowired(required=false)` 注入，对象为null时跳过发送。

**P0-2 从 afterCommit 直发升级为本地消息表**：
原方案在事务 afterCommit 回调里直接调 Producer，Producer 异常被 try-catch 吞掉会导致"DB 已提交但通知永远没发"。现方案：
1. 业务事务内：预约/审批写库的同时 `sys_outbox` 插入 status=0 消息（同事务，同生共死）
2. 定时任务每 5s 扫描 status=0（或重试次数未超标）的记录，调 Producer 异步发送，成功置 status=1
3. 失败重试，`retry_count >= 3` 标记 status=2 死信不再重试；`msg_id` 为 UUID，消费侧可去重
4. MQ broker 宕机期间预约照常成功，broker 恢复后定时任务自动补发

```
业务事务 { INSERT meeting_booking + INSERT sys_outbox } → commit
                                                          ↓
OutboxScheduler 每5s 轮询 status=0 → Producer.asyncSend → 成功 status=1 / 失败 retry_count+1
```

```
// 仅配置name-server才加载该生产者
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
public class BookingNotificationProducer { ... }
```

| Topic | 触发时机 | 消息体字段 |
| --- | --- | --- |
| `booking-notify-submit` | 预约提交成功（**事务提交后**） | bookingId, username, roomName, startTime, endTime, operatorId, ts |
| `booking-notify-approve` | 审批完成（PASS / REJECT，**事务提交后**） | bookingId, username, result(PASS/REJECT), rejectReason, operatorId, ts |

**事务后发消息（P0-2 核心修复）**：
MQ发送必须在DB事务**提交之后**执行。如果在事务内直接发MQ，一旦后续DB操作失败回滚，MQ消息已发出会导致通知接收方收到"实际不存在"的预约通知。
修复方案：使用 `TransactionSynchronizationManager.registerSynchronization(TransactionSynchronization.afterCommit)` 回调，事务提交成功后才调用 Producer。封装为私有模板方法 `runAfterCommit(Runnable)`：

```java
private void runAfterCommit(Runnable runnable) {
    if (bookingNotificationProducer == null) return;  // MQ未启用直接跳过
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() { runnable.run(); }
            }
        );
    } else {
        runnable.run();  // 无事务兜底
    }
}
```

**H2 隐患修复：Lambda 内 username 提前 final**：
afterCommit 回调在事务提交后执行，此时请求 ThreadLocal 可能已被容器清理。如果在 Lambda 内部再调 `request.getAttribute("username")` 会拿到 null。修复：在注册 afterCommit **之前**把所有 Lambda 依赖的 request 属性（username、applicantUsername、startTime/endTime 字符串、roomName）提前提取到 final 局部变量。

**发送方式**：`RocketMQTemplate.asyncSend` 异步回调发送，超时1000ms；主线程不阻塞，MQ发送失败 `try-catch` 降级为 `log.warn`，不影响预约主业务流程。

启用配置 `application.yml`：

```
rocketmq:
  name-server: 127.0.0.1:9876     # 注释该行，Producer不会加载
  producer:
    group: booking-producer-group
```

## 核心难点与解决方案

| 难点 | 解决方案 | 代码位置 |
| --- | --- | --- |
| 多实例并发同一会议室时间段预约冲突 | Redisson会议室粒度锁（第一道）+ DB FOR UPDATE行锁（兜底）+ 锁内时间段冲突校验 | `MeetingBookingController` submit/approve 抢锁 → Service 事务内 |
| 分布式锁自动续期，防止业务超时锁提前释放 | Redisson leaseTime=-1 启用看门狗，每10s自动续期至30s | LOCK_LEASE_TIME = -1L |
| 锁和事务顺序倒置（事务包锁导致并发请求读到未提交数据） | Controller层 tryLock → Service @Transactional → finally unlock（锁包事务），事务在锁释放前提交 | MeetingBookingController.submit/approve |
| MQ在事务内发送导致DB回滚但通知已发出 | `TransactionSynchronizationManager.registerSynchronization(TransactionSynchronization.afterCommit)` 事务提交后发消息；封装为 `runAfterCommit(Runnable)` 模板方法 | MeetingBookingServiceImpl.runAfterCommit |
| afterCommit回调内 request ThreadLocal 可能已被容器清理 | 在注册 afterCommit **之前**把 Lambda 依赖的 request 属性（username、applicantUsername）提前 final 提取 | submitBooking / approve 内 final 变量 |
| MySQL 锁等待超时 innodb_lock_wait_timeout（默认50s） | GlobalExceptionHandler 新增 `handleTransactionSystemException`：递归找 rootCause → SQLException errorCode=1205 → 返回 400 "系统繁忙，请稍后重试" | GlobalExceptionHandler.getRootCause |
| 禁用用户 enabled=null 仍能登录 | `!USER_ENABLED_CODE.equals(user.getEnabled())` 替代 `!= null && != 1`；null 也能短路拒绝 | SysUserServiceImpl.login |
| Redis主从切换、锁丢失风险 | 分布式锁基础上增加DB FOR UPDATE作为最终串行保障；看门狗自动续期防止业务中途锁失效 | selectByIdForUpdate + isHeldByCurrentThread() |
| MQ可选加载，未部署MQ时不阻塞主流程 | Producer增加`@ConditionalOnProperty`；Service使用`@Autowired(required=false)`，null判断跳过发送 | BookingNotificationProducer + Service注入 |
| MyBatis自定义@Select SQL，字段映射异常 | @Results显式声明字段映射（status→resStatus、enabled→status） | `MeetingBookingMapper.selectByIdForUpdate` / `MeetingRoomMapper.selectByIdForUpdate` |
| 操作日志缺少username/method/ip | TokenInterceptor将用户名存入request；IpUtil统一解析反代链IP；saveOperateLog从RequestContextHolder自动获取 | IpUtil + TokenInterceptor + saveOperateLog |
| FOR UPDATE查询后booking.resStatus为null引发NPE | @Results显式映射status→resStatus | MeetingBookingMapper |
| IP解析重复3处（UserController / SysUserServiceImpl / MeetingBookingServiceImpl） | 抽为 `util/IpUtil.java` 统一反代链（X-Forwarded-For → Proxy-Client-IP → WL-Proxy-Client-IP → remoteAddr + 逗号截断） | IpUtil.getClientIp |
| 登录入参无校验，username/password 可 null 透传 | 新增 LoginDTO + `@Valid @NotBlank` 双字段校验 | LoginDTO + LoginController |
| 泛型 updateField 消除 3 段重复代码 | `SysUserServiceImpl.updateField(Long id, BiConsumer setter, T value)` 覆盖 updateRole / updateEnabled / resetPassword | SysUserServiceImpl |
| 分页查询 dto=null 4处重复判断 | 抽为私有方法 `safeQuery(BookingQueryDTO dto)` 统一 `dto == null ? new BookingQueryDTO() : dto` | MeetingBookingServiceImpl |
| 日志写入异常被 try-catch 吞掉但用 error 级别 | 降级为 `log.warn("xxx日志写入跳过(不影响主流程) targetId={}", targetId, e)` | UserController / SysUserServiceImpl |
| Result 返回体裸写 200/400/401 | Result 类新增 6 个公共常量：SUCCESS / BAD_REQUEST / UNAUTHORIZED / FORBIDDEN / NOT_FOUND / SERVER_ERROR | util/Result |
| 分布式锁与 @Transactional 事务隔离级别 | application.yml 显式声明 `hikari.transaction-isolation: TRANSACTION_REPEATABLE_READ`（MySQL默认值），配置严谨化 | application.yml |

## 数据库表 ↔ 实体字段对照

### meeting_booking ↔ MeetingBooking

| DB 列 | Java 字段 | 映射方式 |
| --- | --- | --- |
| `id` | `Long id` | 自动 |
| `room_id` | `Long roomId` | 驼峰命名自动转换 |
| `user_id` | `Long userId` | 驼峰命名自动转换 |
| `start_time` | `LocalDateTime startTime` | 驼峰命名自动转换 |
| `end_time` | `LocalDateTime endTime` | 驼峰命名自动转换 |
| **`status`** | **`Integer resStatus`** | **`@TableField("status")`** |
| `reject_reason` | `String rejectReason` | 驼峰命名自动转换 |
| `title` | `String title` | 自动 |
| `reason` | `String reason` | 自动 |
| `create_time` | `LocalDateTime createTime` | 驼峰 + `FieldFill.INSERT` |
| `update_time` | `LocalDateTime updateTime` | 驼峰 + `FieldFill.INSERT_UPDATE` |

### meeting_room ↔ MeetingRoom

| DB 列 | Java 字段 | 映射方式 |
| --- | --- | --- |
| **`enabled`** | **`Integer status`** | **`@TableField("enabled")`** |

### operation_log ↔ OperationLog

| DB 列 | Java 字段 | 映射方式 | 自动填充来源 |
| --- | --- | --- | --- |
| **`operation`** | **`String operateType`** | **`@TableField("operation")`** | 业务参数 |
| **`params`** | **`String content`** | **`@TableField("params")`** | 业务参数 |
| `username` | `String username` | 自动 | ✅ request.getAttribute("username") (JWT解析) |
| `method` | `String method` | 自动 | ✅ request.getMethod() |
| `ip` | `String ip` | 自动 | ✅ X-Forwarded-For / remoteAddr |
| `cost_time` | `Long costTime` | 驼峰 | 预留字段 |

### sys_operate_log ↔ SysOperateLog

| DB 列 | Java 字段 | 映射方式 | 自动填充来源 |
| --- | --- | --- | --- |
| **`operator_id`** | **`Long operatorId`** | **`@TableField("operator_id")`** | request.getAttribute("userId") |
| **`operator_name`** | **`String operatorName`** | **`@TableField("operator_name")`** | request.getAttribute("username") |
| **`target_id`** | **`Long targetId`** | **`@TableField("target_id")`** | 业务参数（被操作用户id） |
| **`operate_type`** | **`String operateType`** | **`@TableField("operate_type")`** | 业务参数（如 "RESET_PASSWORD"） |
| `ip` | `String ip` | 自动 | X-Forwarded-For / remoteAddr |
| **`operate_time`** | **`LocalDateTime operateTime`** | **`@TableField(value="operate_time", fill=FieldFill.INSERT)`** | ✅ MyMetaObjectHandler |

> 与通用 `operation_log` 表职责分离：`sys_operate_log` 专记录管理员关键操作（重置密码等），`operation_log` 记录预约提交、审批等业务操作。

### sys_user ↔ SysUser

| DB 列 | Java 字段 | 映射方式 |
| --- | --- | --- |
| **`real_name`** | **`String nickname`** | **`@TableField("real_name")`** |
| `password` | `String password` | `@JsonIgnore` + BCrypt加密存储 |

### 枚举定义（前后端统一）

#### BookingStatusEnum 预约状态

| code | 常量 | 含义 | 前端tag类型 |
| --- | --- | --- | --- |
| 0 | PENDING | 待审批 | warning（橙） |
| 1 | PASS | 已通过 | success（绿） |
| 2 | REJECT | 已驳回 | danger（红） |
| 3 | CANCEL | 已取消 | info（灰） |

#### 会议室状态（RoomStatusEnum，映射数据库enabled）

| 值 | 含义 |
| --- | --- |
| 0 | 不可用（禁用） |
| 1 | 可用（启用） |

#### 用户角色 UserRoleEnum

| 值 | 含义 |
| --- | --- |
| 0 | 员工 |
| 1 | 管理员 |

#### 用户启用状态 enabled

| 值 | 含义 |
| --- | --- |
| 1 | 正常 |
| 0 | 禁用 |

> 
> 时间格式：**`yyyy-MM-dd HH:mm:ss`**，前后端双向一致，请求参数与返回均为字符串。
> 后端筛选语义：预约整段落在查询范围内 `start_time >= ? AND end_time <= ?`。

### 主要返回VO字段

- **BookingVO**：`id, roomId, roomName, userId, username, startTime, endTime, resStatus, rejectReason, title, reason, createTime`
- **MeetingRoom**：`id, roomName, capacity, location, description, status, createTime`
- **SysUser**：`id, username, nickname, phone, role, enabled, createTime`
- **OperationLog**：`id, userId, username, operateType, content, method, ip, costTime, createTime`

## 项目目录

### 后端 admin_system_backend

```
admin_system_backend
└── src/main/java/com/example/adminsystem
    ├── AdminSystemBackendApplication.java   // 启动类（根目录，pom parent 3.3.4）
    ├── annotation                           // @RequireAdmin、@RequireRole
    ├── config                               // 配置类 9 个：GlobalExceptionHandler（含 handleTransactionSystemException 锁超时业务化 + getRootCause）、MyMetaObjectHandler（createTime INSERT、updateTime INSERT_UPDATE）、JwtConfig、JwtProperties、WebConfig（拦截器注册）、CorsConfig、JacksonTimeConfig、MyBatisPlusConfig（分页插件）、Knife4jConfig
    ├── controller                           // 接口控制器 5 个
    │   ├── LoginController                  // POST /api/login（LoginDTO @Valid）、POST /api/register（role→UserRoleEnum.EMPLOYEE）
    │   ├── MeetingBookingController         // 【锁包事务】RedissonClient 注入 + requireUserId/requireAdminId 私有方法；submit/approve 外层抢锁；cancelBooking 不上锁
    │   ├── MeetingRoomController
    │   ├── UserController                   // IpUtil.getClientIp 统一 IP 解析；saveOperateLog 异常 log.warn 降级
    │   └── LogController
    ├── dto                                  // 入参DTO（RoomDTO、BookingDTO（title @NotBlank）、ApproveBookingDTO、LoginDTO（username/password @NotBlank）、RegisterDTO、BookingQueryDTO、UserRoleDTO、UserStatusDTO、UserPasswordDTO）
    ├── entity                               // 数据库实体 6 个（MeetingBooking / MeetingRoom / OperationLog / SysUser / SysLoginLog / SysOperateLog）
    ├── enums                                // 枚举类（BookingStatusEnum 0/1/2/3、RoomStatusEnum 0/1、UserRoleEnum 0/1）
    ├── interceptor                          // TokenInterceptor（唯一稳定拦截器，不做擅自修改；解析 JWT → request.setAttribute userId/role/username）
    ├── mapper                               // MyBatis Mapper接口 + XML映射文件（MeetingBookingMapper.xml 含分页查询 VO 组装 + 时间重叠 lt/gt 条件）
    │   ├── MeetingBookingMapper             // @Results 显式映射 status→resStatus + selectByIdForUpdate FOR UPDATE
    │   └── MeetingRoomMapper                // @Results 显式映射 enabled→status + selectByIdForUpdate FOR UPDATE
    ├── service                              // 业务接口
    │   ├── MeetingBookingService            // 含 getRoomIdOfBooking(Long bookingId) —— Controller approve 抢锁前预查 roomId
    │   ├── BookingNotificationProducer      // 【可选组件】@ConditionalOnProperty(name="rocketmq.name-server")；Producer Bean 未配置 name-server 时不加载
    │   └── impl                             // 业务实现类
    │       ├── MeetingBookingServiceImpl    // Service 层只持有事务内 DB 逻辑（不再处理 Redisson 锁）；runAfterCommit(Runnable) 事务提交后发 MQ 模板方法；IpUtil + afterCommit final 修复 H2
    │       ├── MeetingRoomServiceImpl       // updateField 泛型私有方法；3 处 CRUD 末尾 log.info 成功日志
    │       └── SysUserServiceImpl           // LOGIN_STATUS_SUCCESS/FAIL + USER_ENABLED_CODE 常量；!equals 替代 !=null && !=1 防 NPE；IpUtil.getClientIp；saveLoginLog 异常 log.warn 降级
    ├── util                                 // 工具类（Result 统一响应 + 6 个状态码常量；BusinessException 业务异常；IpUtil 反代链 IP 解析；JwtUtil；PasswordUtil）
    └── vo                                   // 返回VO（BookingVO：id, roomId, roomName, userId, username, startTime, endTime, resStatus, rejectReason, title, reason, createTime）
```

### 前端 meeting-vue

```
meeting-vue/
├─ index.html                     # 应用入口 HTML
├─ vite.config.js                 # Vite 配置（@ 别名、/api 代理到 8080）
├─ package.json
└─ src/
    ├─ main.js                    # 应用入口：注册 Element Plus + 路由 + 全局样式
    ├─ App.vue                    # 根组件（仅 router-view）
    ├─ style.css                  # 全局样式 + 主题变量
    ├─ router/
    │  └─ index.js                # 路由表 + 登录/角色守卫
    ├─ utils/
    │  ├─ request.js              # Axios 封装：请求拦截带token、响应拦截统一处理401/403/业务错误
    │  └─ meeting.js              # 历史遗留文件，保留不使用
    ├─ api/                       # 按模块拆分的接口封装（唯一对外调用入口）
    │  ├─ auth.js                 # 登录 / 注册
    │  ├─ room.js                 # 会议室
    │  ├─ booking.js              # 预约
    │  ├─ user.js                 # 用户管理（管理员）
    │  └─ log.js                  # 操作日志（管理员）
    └─ views/
        ├─ Login.vue              # 登录页
        ├─ Register.vue           # 注册页
        ├─ Home.vue               # 主布局（侧边栏/面包屑/顶部用户信息）
        ├─ Dashboard.vue          # 工作台（统计卡 + 状态占比饼图）
        ├─ RoomCalendar.vue       # 预约日历（月/日视图 + 状态色块 + 详情弹窗）
        ├─ employee/
        │  ├─ RoomList.vue        # 员工：可用会议室列表 + 提交预约
        │  └─ MyBookings.vue      # 员工：我的预约
        └─ admin/
            ├─ RoomManage.vue     # 管理员：会议室管理
            ├─ BookingAdmin.vue   # 管理员：预约审批
            ├─ UserManage.vue     # 管理员：用户管理
            └─ LogList.vue        # 管理员：操作日志
```

## 路由清单

| 路由 | 组件 | 可见角色 | 说明 |
| --- | --- | --- | --- |
| `/login` | Login.vue | 公开 | 登录 |
| `/register` | Register.vue | 公开 | 注册 |
| `/home` | Home.vue | 登录用户 | 主布局父路由，redirect到角色首页 |
| `/home/dashboard` | Dashboard.vue | 员工 + 管理员 | 工作台 |
| `/home/calendar` | RoomCalendar.vue | 员工 + 管理员 | 预约日历 |
| `/home/rooms` | employee/RoomList.vue | 员工 | 可用会议室 + 提交预约 |
| `/home/my-bookings` | employee/MyBookings.vue | 员工 | 我的预约 |
| `/home/room-manage` | admin/RoomManage.vue | 管理员 | 会议室管理 |
| `/home/booking-approve` | admin/BookingAdmin.vue | 管理员 | 预约审批 |
| `/home/users` | admin/UserManage.vue | 管理员 | 用户管理 |
| `/home/logs` | admin/LogList.vue | 管理员 | 操作日志 |

> 
> 员工登录默认进入 `/home/rooms`，管理员默认进入 `/home/room-manage`。

## 部署说明

### 环境准备

| 组件 | 最低要求 | 默认地址 | 配置位置 | 说明 |
| --- | --- | --- | --- | --- |
| Redis | 5.x | 127.0.0.1:6379 | `spring.data.redis.*` | **必须最先启动**。RedissonClient Bean 默认 eager 初始化，容器启动阶段就发起 TCP 连接，连不上会导致 BeanCreationException → 容器整体启动中止（MeetingBookingController 依赖注入 RedissonClient）。无密码留空即可 |
| MySQL | 5.7+ | 127.0.0.1:3306 | `spring.datasource.*` | 创建 `admin_system` 库后执行 `docs/schema.sql`（12 张表全量 + 预置数据）；存量库升级执行 schema.sql 末尾增量迁移段；只重置数据用 `docs/seed.sql` |
| Nacos | 2.x | 127.0.0.1:8848 | `spring.cloud.nacos.discovery.server-addr` | 默认 `fail-fast=false`，连不上只 warn 不阻断。单机 Nacos `ephemeral=false`，集群 `ephemeral=true` |
| RocketMQ（可选） | 5.x | 127.0.0.1:9876 | `rocketmq.name-server`（注释则不加载） | 不配 name-server → BookingNotificationProducer 不加载 → 预约主流程正常、MQ 通知静默跳过 |
| Node.js | ≥18（推荐20+） | - | 前端工程 | - |

### 后端打包与启动

> ⚠️ **编译需 JDK 21**：项目使用 Java 16+ 语法特性，Windows 下若系统默认 JAVA_HOME 指向 JDK 1.8，需先 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'` 再执行 mvn。

```
# Maven打包，跳过单元测试（pom artifactId=adminsystem，版本0.0.1-SNAPSHOT）
mvn clean package -DskipTests
# 实例1
java -jar target/adminsystem-0.0.1-SNAPSHOT.jar --server.port=8080
# 实例2，相同服务名，注册到同一集群
java -jar target/adminsystem-0.0.1-SNAPSHOT.jar --server.port=8081
```

### 前端本地启动 & 构建

```
# 安装依赖
npm install
# 开发环境启动，访问 [http://localhost:5173](http://localhost:5173)
npm run dev
# 生产打包，产物输出dist
npm run build
# 本地预览打包产物
npm run preview
```

> 
> 开发环境Vite代理：`/api` 转发至 `[http://localhost:8080](http://localhost:8080)`，无需后端CORS。
> 生产部署：Nginx反向代理，将前端静态资源直接托管，`/api`转发到后端服务。

### 后端配置清单（application.yml）

| 配置项 | 开发环境 | 生产环境 |
| --- | --- | --- |
| `spring.cloud.nacos.discovery.server-addr` | `127.0.0.1:8848` | Nacos集群地址 |
| `spring.cloud.nacos.discovery.ephemeral` | `true`（单机Nacos改为false） | `true` |
| `spring.data.redis.host/port/password` | `127.0.0.1:6379` 空密码 | 生产Redis地址+密码 |
| `spring.datasource.url/username/password` | 本地MySQL | 生产MySQL实例 |
| `rocketmq.name-server` | 注释（不启用MQ） | RocketMQ NameServer地址 |
| `jwt.secret` | 固定测试字符串 | 生产环境替换为随机强密钥 |

## 已知限制（前后端对齐）

1. 员工无法做跨用户时段冲突预校验：员工端只有本人预约接口，无法查询他人占用，**冲突校验完全由后端分布式锁+数据库校验拦截**。
2. `/api/log/page` 仅管理员可访问，无用户过滤参数，无法实现员工查看本人操作记录。如需该功能需要后端新增接口参数。
3. 工作台会议室总数接口仅管理员可访问，员工工作台使用可用会议室替代。
4. 会议室卡片视图图片、设备标签为前端占位，数据库无对应字段，如需真实展示需要后端扩展实体。
5. ~~批量审批、批量启停会议室：前端循环调用单条接口~~ → **P1-2 已提供后端原生批量接口**（`/api/booking/batch-approve`、`/api/room/batch-*`），事务原子性保证；后端分页上限100。
6. 后端筛选语义：预约**整段落在查询时间范围内**，跨天跨边界预约可能不会被查询出来。

## 二次开发指引

### 后端

1. 新增接口统一在Controller编写，业务逻辑放入Service实现类。
2. 预约提交、审批等并发场景必须保留会议室粒度分布式锁+DB行锁兜底。
3. 状态统一使用枚举常量，禁止代码硬编码数字。
4. Mapper自定义SQL注意字段映射，避免NPE。
5. 业务层主动抛出 `BusinessException` 返回友好提示，禁止直接 `throw new RuntimeException` 或裸抛 500。
6. `JwtUtil.parseToken` 内部已统一捕获 `JwtException` 抛 `BusinessException(401, ...)`，拦截器/Controller 层不需要再自己 try-catch JJWT 异常。

### 前端

1. 新增接口调用一律在`src/api/`模块新增函数，组件内引入调用，禁止组件内直接写axios。
2. 字段、枚举值严格和后端DTO/VO对齐，禁止私自修改。
3. 全局样式放入`src/style.css`；组件样式使用`<style scoped>`。
4. 新增页面使用路由懒加载，配置`meta.role`做角色权限控制。
5. 请求拦截器 Header 必须发送 `Authorization: Bearer ${token}`，不要用 `token: xxx`。

## 注意事项

1. 生产环境务必修改`jwt.secret`密钥，避免安全风险。
2. MySQL使用`FOR UPDATE`行锁，需要保证事务隔离级别支持行锁，避免表锁。
3. Nacos集群部署时ephemeral配置调整为true；单机Nacos推荐false。
4. RocketMQ为可选组件，不配置name-server即可直接启动，不会影响预约业务。
5. 分布式锁仅用于预约提交、审批场景；其余接口无锁设计。
6. 所有状态使用枚举常量，禁止代码中直接写数字硬编码。
7. 前端生产环境使用Nginx反向代理，`/api`转发后端，保持前端baseURL不变。

## 冒烟验证清单

### 1. 后端打包与启动验证

```
# Maven打包（零错误零警告门槛）
mvn clean package -DskipTests
# 启动顺序：Redis → MySQL（docs/schema.sql 12张表已初始化）→ Nacos（可选）→ 后端
# 实例1
java -jar target/adminsystem-0.0.1-SNAPSHOT.jar --server.port=8080
# 实例2（相同服务名注册到Nacos集群）
java -jar target/adminsystem-0.0.1-SNAPSHOT.jar --server.port=8081
# 前端
npm install && npm run dev
```

**启动成功日志关键关键词**：
```
Started AdminSystemBackendApplication in X seconds
Tomcat initialized with port 8080
Redisson 3.37.0
```

### 2. 核心接口冒烟测试（按顺序执行）

| # | 测试项 | 操作 | 预期 |
|---|---|---|---|
| 1 | 未登录访问 protected 接口 | 不带 Authorization 调 `POST /api/booking/submit` | `{"code":401,"msg":"未登录，请携带token"}` |
| 2 | 登录（用户） | `POST /api/login` `{"username":"user1","password":"123456"}` | `{"code":200,"data":{"token":"eyJ..."}}` |
| 3 | 登录（管理员） | `POST /api/login` `{"username":"admin","password":"123456"}` | `{"code":200,"data":{"token":"eyJ..."}}` |
| 4 | 禁用用户登录 | DB `UPDATE sys_user SET enabled=0 WHERE username='user1'` 后再调 | `{"code":403,"msg":"账号已被禁用，禁止登录"}` |
| 5 | 普通用户访问管理员接口 | user1 token 调 `GET /api/booking/admin/page` | `{"code":403,...}` |
| 6 | 查询会议室列表 | `GET /api/room/list` | `{"code":200,"data":[...]}` |
| 7 | 提交预约（正常） | user1 token + POST BookingDTO | `{"code":200,"msg":"预约提交成功..."}` + DB 新增 PENDING |
| 8 | 提交预约（时间冲突） | 同会议室同时段再提交 | `{"code":400,"msg":"该会议室此时间段已被预约，时间冲突"}` |
| 9 | 审批通过 | admin token approve status=1 | `{"code":200}` + DB 状态改 1(PASS) |
| 10 | 审批驳回 | status=2 + rejectReason | `{"code":200}` + DB rejectReason 填入 |
| 11 | 驳回未填原因 | status=2 + rejectReason="" | `{"code":400,"msg":"驳回必须填写驳回原因"}` |
| 12 | 重复审批 | 对已 PASS 预约再调 | `{"code":400,"msg":"该预约已审批，不可重复操作"}` |
| 13 | 取消本人预约 | user1 token DELETE `/cancel/{id}` | `{"code":200}` + DB 状态改 3 |
| 14 | 取消他人预约 | user2 token 调 user1 预约 id | `{"code":400,"msg":"只能取消本人的预约"}` |
| 15 | 管理员重置密码 | admin PUT `/api/user/resetPassword` | `{"code":200}` + sys_operate_log 新增 |
| 16 | 重置后登录 | 新密码 | `{"code":200}` 成功 |
| 17 | 并发预约冲突模拟 | 两个用户同时 POST 同一会议室同一时段 | 一个 200，一个 400 "正在被他人预约" |
| 18 | 删会议室（有未完结预约） | admin DELETE roomId | `{"code":400,...}` 拦截 |
| 19 | 删会议室（空会议室） | admin DELETE | `{"code":200}` + DB 删除 |
| 20 | 锁等待超时业务化 | 并发持锁超过 innodb_lock_wait_timeout | `{"code":400,"msg":"系统繁忙，请稍后重试"}`（不是 500） |

### 3. 前端冒烟测试

1. 未登录访问受保护路由 → 跳转登录；员工访问管理员路由 → 被拦截。
2. 员工：登录 → 工作台统计与饼图 → 侧边栏折叠/面包屑 → 预约日历色块与详情 → 提交预约 → 我的预约取消。
3. 管理员：登录 → 工作台 → 预约审批（日期筛选 + 批量通过）→ 会议室管理（双视图 / 批量启停 / 删除预校验）→ 用户管理 → 操作日志。

---
