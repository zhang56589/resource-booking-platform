# 会议室预约系统｜Builder 主控验收清单

## 全局冒烟清单（任何阶段完成后必须回归）

### 前置：环境就绪
- [ ] Redis 启动（必须最先启动）
- [ ] MySQL 启动 + `admin_system` 库 + 6 张表（sys_user / meeting_room / meeting_booking / operation_log / sys_login_log / sys_operate_log）
- [ ] Nacos 启动（可选，不配服务名也能跑）
- [ ] RocketMQ 启动（可选，不配 name-server 也能跑）
- [ ] 后端 `java -jar adminsystem-0.0.1-SNAPSHOT.jar --server.port=8080` 启动成功
- [ ] 启动日志出现 `Started AdminSystemBackendApplication in X seconds`、`Tomcat initialized with port 8080`、`Redisson 3.37.0`

### 20 步核心冒烟接口

| # | 接口 | 请求 | 预期 | 验收标志 |
|---|---|---|---|---|
| 1 | 未登录调 protected | 不带 Authorization 调 submit | 401 | `{"code":401}` |
| 2 | 用户登录 | `POST /api/login` user1/123456 | 200 + token | `data.token` 非空 |
| 3 | 管理员登录 | `POST /api/login` admin/123456 | 200 + token + role=1 | `data.role=1` |
| 4 | 禁用用户登录 | DB enabled=0 后 user1 登录 | 403 | 提示"账号已被禁用" |
| 5 | 普通用户越权 | user1 token 调 admin/page | 403 | 提示"无权限" |
| 6 | 会议室列表 | `GET /api/room/list` | 200 + 数组 | 至少 1 条 room |
| 7 | 提交预约（正常） | user1 token + POST BookingDTO | 200 | DB 新增 PENDING |
| 8 | 提交预约（时间冲突） | 同会议室同时段 | 400 | "时间冲突" |
| 9 | 审批通过 | admin token approve status=1 | 200 | DB 状态→1 |
| 10 | 审批驳回 | status=2 + rejectReason | 200 | rejectReason 填入 |
| 11 | 驳回未填原因 | status=2 + rejectReason="" | 400 | "必须填写原因" |
| 12 | 重复审批 | 对已 PASS 预约再调 | 400 | "不可重复操作" |
| 13 | 取消本人预约 | DELETE `/cancel/{id}` | 200 | DB 状态→3 |
| 14 | 取消他人预约 | user2 token 调 user1 预约 | 400 | "只能取消本人" |
| 15 | 管理员重置密码 | PUT /api/user/resetPassword | 200 | DB sys_operate_log 新增 |
| 16 | 重置后登录 | 新密码 | 200 | 成功 |
| 17 | 并发预约冲突 | 两用户同时 POST 同会议室同时段 | 一个 200 / 一个 400 "正在被他人预约" | Redisson 锁串行化 |
| 18 | 删会议室（有未完结预约） | DELETE roomId | 400 | 拦截，DB 不变 |
| 19 | 删会议室（空） | DELETE | 200 | DB 删除 |
| 20 | 锁等待超时业务化 | 并发持锁超 innodb_lock_wait_timeout | 400 "系统繁忙" | 不是 500 |

---

## 阶段 P0-1｜锁粒度拆分优化 验收清单

### 变更：approve 锁 key 从 `booking:room:{roomId}` 改为 `booking:approve:{bookingId}`
- [ ] approve 锁粒度改为 bookingId（Controller 层 `redissonClient.getLock("booking:approve:" + dto.getBookingId())`）
- [ ] submit 保持会议室粒度 `booking:room:{roomId}` 不变
- [ ] DB FOR UPDATE 仍然锁 meeting_booking 行（approve 内已有 selectByIdForUpdate）
- [ ] 同一 bookingId 并发审批请求只能一个成功（Redisson 锁串行化）
- [ ] 不同 bookingId 但同会议室的审批请求可以并行（锁粒度拆分后不再互斥）
- [ ] 编译通过：`mvn -q compile`
- [ ] 全量冒烟回归：20 步全部通过
- [ ] 锁包事务顺序不变：Controller tryLock → Service @Transactional → finally unlock
- [ ] cancelBooking 不上分布式锁（维持现状）

---

## 阶段 P0-2｜本地消息表 RocketMQ 可靠消息最终一致性 验收清单

### 变更：新增 `sys_outbox` 表 + 定时任务 + 事务内写 outbox
- [ ] DDL 输出 `docs/schema.sql` 含 `sys_outbox` CREATE TABLE（id / msg_id / topic / payload / status / retry_count / next_retry_time / create_time / update_time）
- [ ] 新增 `SysOutbox` 实体 + `SysOutboxMapper` + `SysOutboxService`
- [ ] `OutboxScheduler` 定时任务（`@Scheduled(cron = "0/5 * * * * ?")` 每 5s 扫 status=0 或 重试次数未超标的记录，调 Producer）
- [ ] MeetingBookingServiceImpl submitBooking / approve 事务内：MQ 发送改为写 outbox 表（事务提交后自动落库，定时任务异步发 MQ）
- [ ] afterCommit 回调保留但改为调 outboxService.save(...)
- [ ] 手动发 outbox 场景：定时任务里 Producer 也用 `@Autowired(required=false)` null 判空
- [ ] 手动验证：把 RocketMQ broker 关掉 → 预约提交成功但 outbox 写入 status=0 → 启动 broker 后定时任务自动补发
- [ ] msg_id 唯一性：UUID 作为 msg_id，MQ 消费者侧可去重
- [ ] 重试上限：retry_count >= 3 时标记 status=2（死信），不再重试
- [ ] 编译通过
- [ ] 全量冒烟回归：20 步全部通过

---

## 阶段 P0-3｜RBAC 细粒度权限改造 验收清单

### ⚠️ 方案修正（与原计划差异）
- ~~TokenInterceptor role 改 String 编码~~ → **不改**（红线）
- ~~SysUser.role 从 Integer 改 String~~ → **保持 Integer**（0=employee, 1=admin）
- AOP 直接读 request.getAttribute("role") 的 Integer 值查映射表，与 TokenInterceptor 两层并行

### 变更：新增 sys_role / sys_permission / sys_role_permission + @RequiresPermission + PermissionAspect
- [x] DDL 输出 3 张新表：sys_role（id=TINYINT, code, name, description） / sys_permission（id, code, name） / sys_role_permission（role_id, permission_id）
- [x] 预置数据：sys_role 2 条（employee=0, admin=1）；sys_permission 9 条（booking:submit/cancel/approve/admin, room:list/write, user:page/write, log:page）；sys_role_permission 12 条（员工 3 + 管理员全 9）
- [x] 新增 `@RequiresPermission("booking:approve")` 注解（value + message）
- [x] 新增 `PermissionAspect` AOP 切面：`@Around("@annotation(rp)")` → 从 request.getAttribute("role") 取 Integer roleId → 查 sys_role_permission JOIN sys_permission → codes.contains(rp.value())
- [x] 新增 `SysRolePermissionMapper.xml`：`selectPermissionCodesByRoleId` 自定义 SQL
- [x] pom.xml 追加 `spring-boot-starter-aop` 依赖（提供 aspectjweaver）
- [x] MeetingBookingController 移除 `requireAdminId` inline 双重检查（@RequireAdmin 注解已覆盖管理员校验，Service 层 adminId 只用于操作日志）
- [ ] Controller 方法加 `@RequiresPermission` 注解（**渐进策略第一阶段：现有 @RequireAdmin 保持不动，新注解 + 切面基础设施就绪，第二阶段逐步替换**）
- [ ] 无权限用户（employee）调 `@RequiresPermission("booking:approve")` 接口 → 403
- [ ] 已有的 @RequireAdmin 接口（TokenInterceptor 处理）→ 行为不变
- [x] 编译通过：`mvn -q compile`（exit_code=0）
- [ ] 全量冒烟回归：20 步全部通过（role 校验逻辑不变，两套权限机制并行兼容）
- [ ] DB 验证：`SELECT COUNT(*) FROM sys_role` = 2, `sys_permission` = 9, `sys_role_permission` = 12

### 后续可优化（P1+）
- PermissionAspect 每次拦截查 DB → 可加 Redis 本地缓存（roleId → List<String> codes），TTL 10min

---

## 阶段 P0-4｜幂等拦截器 RequestId 防重复提交 验收清单

### 变更：新增 IdempotentInterceptor + Redis SETNX
- [ ] 新增 `IdempotentInterceptor.java`：从 Header 取 `X-Request-Id` → Redis SETNX `idempotent:{userId}:{requestId}`（TTL=60s）
- [ ] SETNX 返回 false → 返回 400 "请勿重复提交"
- [ ] WebConfig 注册拦截器，匹配路径：`/api/booking/submit`、`/api/booking/approve`、`/api/booking/cancel/**`、`/api/room/delete/**`、`/api/user/resetPassword`
- [ ] 其他只读接口不拦截（GET /api/room/list 等）
- [ ] 前端 60s 内带相同 X-Request-Id 重放 submit 请求 → 400 幂等拦截
- [ ] 前端不同 X-Request-Id 正常提交（幂等放行）
- [ ] Redis 不可达时拦截器降级（只打印 warn，不阻断请求主流程）
- [ ] 编译通过
- [ ] 全量冒烟回归：20 步全部通过

---

## 阶段 P1-1｜数据库索引优化 + 软删除改造 验收清单

### 变更：ALTER TABLE 加索引 + 加 deleted 字段 + MyBatis-Plus @TableLogic
- [ ] meeting_booking 加索引：`CREATE INDEX idx_booking_room_time ON meeting_booking(room_id, start_time, end_time)`、`CREATE INDEX idx_booking_user_status ON meeting_booking(user_id, res_status)`
- [ ] operation_log 加索引：`CREATE INDEX idx_oplog_time ON operation_log(create_time)`
- [ ] meeting_room / meeting_booking 加列：`deleted TINYINT(1) DEFAULT 0` + `@TableLogic`
- [ ] Entity 加 `private Integer deleted` + `@TableLogic`
- [ ] MyBatis XML 手写 JOIN 需确认自动追加 `AND deleted = 0`（如果手写 SQL 绕过 MP，需手动加）
- [ ] 软删除 DELETE 变 UPDATE：`UPDATE meeting_room SET deleted=1 WHERE id=?`
- [ ] 已删除会议室 selectById 返回 null
- [ ] 删除会议室前置校验（未完结预约）软删除仍生效（`delete=0 AND res_status IN (0,1)` 不影响业务逻辑）
- [ ] 编译通过
- [ ] 全量冒烟回归：20 步全部通过（物理删除行为不变，但软删除后 selectById 返回 null）

---

## 阶段 P1-2｜批量审批、会议室批量启停接口 验收清单

### 变更：新增 batch-approve / batch-enable / batch-disable / batch-delete
- [ ] `POST /api/booking/batch-approve`：入参 BookingBatchApproveDTO(bookingIds: List<Long>, status, rejectReason)
- [ ] `PUT /api/room/batch-enable`：入参 RoomBatchDTO(ids: List<Long>)
- [ ] `PUT /api/room/batch-disable`：同上
- [ ] `DELETE /api/room/batch-delete`：同上（批量删除复用单次前置校验：未完结预约拦截）
- [ ] 批量在一个事务里：每个元素循环调 Service 的单次方法（复用业务校验），任意一个失败整体回滚
- [ ] 批量空数组 → 400 "ids 不能为空"
- [ ] 批量内遇到第一个失败元素（如某个 bookingId 不存在）→ BusinessException → 事务回滚 → 400 返回
- [ ] 编译通过
- [ ] 全量冒烟回归：20 步全部通过
- [ ] 新增接口单独用例：① 批量 5 个 booking 同时审批通过 → 200 + DB 5 条 status=1；② 批量中混 1 个已审批 booking → 整体 400 + DB 不变

---

## 阶段 P1-3｜Nacos @RefreshScope 动态配置 验收清单

### 变更：配置抽离到 Nacos + @RefreshScope
- [ ] 新增 `bootstrap.yml`：`spring.cloud.nacos.config.server-addr` + `shared-configs[].dataId=admin-system-backend.yaml`
- [ ] `application.yml` 移除：jwt.secretKey / jwt.expireMinutes / rocketmq.*（保留 spring.datasource / spring.data.redis / spring.cloud.nacos.discovery）
- [ ] JwtProperties 加 `@RefreshScope`
- [ ] Nacos 控制台新建 dataId=admin-system-backend.yaml, group=DEFAULT_GROUP，填原 application.yml 里移除的配置
- [ ] Nacos 修改 jwt.expireMinutes 后，调用接口签发的新 token 生效（旧 token 过期时间不变 —— 预期行为）
- [ ] Nacos 修改 rocketmq.name-server 为空时 Producer Bean 不加载
- [ ] 无 Nacos 配置时应用也能启动（使用 application.yml 默认值）
- [ ] 编译通过
- [ ] 全量冒烟回归：20 步全部通过

---

## 阶段 P1-4｜MDC 全链路 TraceId 埋点 验收清单

### 变更：新增 TraceIdInterceptor + MDC + logback pattern
- [ ] 新增 `TraceIdInterceptor`（implements HandlerInterceptor）：preHandle 里生成 UUID traceId → `MDC.put("traceId", traceId)` → `response.setHeader("X-Trace-Id", traceId)`；afterCompletion 里 `MDC.clear()`
- [ ] WebConfig 注册 TraceIdInterceptor，order=-100（优先级高于 TokenInterceptor）
- [ ] logback-spring.xml 或 application.yml logging.pattern.console 加 `%X{traceId:-}`
- [ ] 入站请求 Header 自动带 X-Trace-Id（前端 request.js 加拦截器）
- [ ] 日志格式：`2026-10-24 17:00:00 [abc-123-def] INFO ...` 中括号里是 traceId
- [ ] 编译通过
- [ ] 全量冒烟回归：20 步全部通过
- [ ] 验证：两个并发预约请求日志中 traceId 不同，同一条业务链路日志 traceId 相同

---

## 阶段 P1-5｜前端动态菜单 + v-permission 验收清单

### 变更：后端新增 MenuController + 前端动态路由 + v-permission 指令
- [ ] 后端：SysMenu 实体 + SysMenuMapper + MenuController `GET /api/user/menu`（按 role 过滤菜单树）
- [ ] DDL：sys_menu(id, parent_id, name, path, component, icon, permission, order_num) + sys_role_menu(role_id, menu_id)
- [ ] 前端：`permission.js` 自定义指令 `v-permission`：根据用户权限数组判断按钮显隐
- [ ] 前端：`router/index.js` 改为 `initDynamicRoutes()` 登录后调 `/api/user/menu` 返回菜单树 → 调 `router.addRoute()` 动态注册
- [ ] 前端侧边栏由接口返回的菜单树动态渲染（原硬编码菜单全部移除）
- [ ] 管理员登录：菜单树含全部菜单 + booking:approve 按钮显隐
- [ ] 员工登录：菜单树只有"我的预约"+"会议室列表"，approve 按钮不显示
- [ ] 编译通过（前端 `npm run build`）
- [ ] 全量冒烟回归：后端 20 步 + 前端 3 步全部通过

---

## 阶段 P2-1｜收尾 README / 接口文档 / SQL 验收清单

### 变更：README 最终对齐 + SQL 脚本汇总 + Knife4j 文档校验
- [ ] README 最终通读一遍，无遗漏 P0-P1 任何阶段内容
- [ ] Knife4j `/doc.html` 所有接口能正常打开、返回正确的 Request Body Schema
- [ ] 输出 `docs/schema.sql`：所有 CREATE TABLE（6 张现有表 + 新增表）
- [ ] 输出 `docs/seed.sql`：sys_user / sys_role / sys_permission / sys_role_menu / sys_role_permission 的初始化数据
- [ ] 编译通过
- [ ] 全量冒烟回归：20 步全部通过
- [ ] git commit 规范提交：msg 格式 `feat(P2-1): README final align + SQL summary`
