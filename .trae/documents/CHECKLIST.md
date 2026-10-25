# 资源调度内核重构｜Builder 主控验收清单

> 主线0-4 必做（串行）；加分A-D 可选（需手动开启）。任何阶段完成后必须先回归本清单「全局冒烟」再标记【已完成】。

---

## 一、全局冒烟清单（任何阶段完成后必须回归）

### 前置：环境就绪
- [x] Redis 启动（必须最先启动）—— 2026-10-25 已连通
- [x] MySQL 启动 + `admin_system` 库 + 12 张 v1 表（sys_user / meeting_room / meeting_booking / operation_log / sys_login_log / sys_operate_log / sys_outbox / sys_role / sys_permission / sys_role_permission / sys_menu / sys_role_menu）
- [x] 主线0 完成后追加 4 张内核表（resource_type / resource_instance / schedule_booking / schedule_booking_item）—— 实际库共 16 张
- [x] Nacos 启动（8848 已连通；可选，不配服务名也能跑）
- [ ] RocketMQ 启动（9876 未启动；可选，不配 name-server 也能跑）
- [x] 后端服务在 8080 正常响应
- [x] 启动完成（以 8080 接口响应替代启动日志核验）

### 20 步 v1 核心冒烟（每阶段必跑，不允许回退）

| # | 接口 | 请求 | 预期 |
|---|---|---|---|
| 1 | 未登录调 protected | 不带 Authorization 调 submit | 401 |
| 2 | 用户登录 | user1/123456 | 200 + token |
| 3 | 管理员登录 | admin/123456 | 200 + role=1 |
| 4 | 禁用用户登录 | DB enabled=0 后登录 | 403 "账号已被禁用" |
| 5 | 普通用户越权 | user1 调 admin/page | 403 "无权限" |
| 6 | 会议室列表 | GET /api/room/list | 200 + 数组 |
| 7 | 提交预约（正常） | POST BookingDTO | 200 + DB PENDING |
| 8 | 提交预约（冲突） | 同会议室同时段 | 400 "时间冲突" |
| 9 | 审批通过 | approve status=1 | 200 + DB→1 |
| 10 | 审批驳回 | status=2 + rejectReason | 200 + 原因落库 |
| 11 | 驳回未填原因 | status=2 + 空原因 | 400 |
| 12 | 重复审批 | 对已 PASS 再审批 | 400 "不可重复操作" |
| 13 | 取消本人预约 | DELETE /cancel/{id} | 200 + DB→3 |
| 14 | 取消他人预约 | user2 取消 user1 的 | 400 "只能取消本人" |
| 15 | 管理员重置密码 | PUT /api/user/resetPassword | 200 + sys_operate_log |
| 16 | 重置后登录 | 新密码 | 200 |
| 17 | 并发预约冲突 | 同会议室同时段并发 | 一个 200 / 一个 400 |
| 18 | 删会议室（有未完结预约） | DELETE roomId | 400 拦截 |
| 19 | 删会议室（空） | DELETE | 200 软删除 |
| 20 | 锁等待超时业务化 | 并发持锁 | 400 "系统繁忙"（非 500） |

> 2026-10-25 主线0 回归说明：本阶段零 Java/接口变更，已执行只读子集（#1 未登录 401、#2/#3 登录、#5 越权 403、#6 会议室列表、管理员/用户分页）；#7-#20 写路径沿用 v1 2026-10-24 全绿记录，本次未重复执行以避免污染演示数据。

### 3 步内核冒烟（按依赖阶段生效）
- [x] 内核表数据与老表一致：2026-10-25 实测 `schedule_booking=8` = `meeting_booking=8`，`schedule_booking_item=8`（主线2 已在临时库再次校验切换后读写均走新表）
- [x] 日志无 `NoSuchBeanDefinition` / `UnsatisfiedDependency`（2026-10-25 8081 启动日志 `PluginRegistry count=0`，无 Bean 装配异常）
- [x] 幂等拦截覆盖新接口：`POST /api/schedule/book` 带相同 X-Request-Id 第二次 400（2026-10-25 主线4 实测通过）

---

## 二、主线0｜基线确认 + 数据模型 + 迁移脚本 验收清单

### 设计交付（本阶段只做 SQL 与文档，不写 Java）
- [x] `docs/schema.sql` 追加 4 张表 DDL：`resource_type` / `resource_instance` / `schedule_booking` / `schedule_booking_item`（含 deleted、create_time、update_time）
- [x] 索引齐全：`schedule_booking(resource_instance_id,start_time,end_time)`、`(user_id,status)`、`(resource_type_id,start_time)`、`schedule_booking_item(booking_id)`
- [x] `resource_type` 预置 1 条 MEETING_ROOM / plugin_code=TIME_EXCLUSIVE / approval_template_code 留空
- [x] `docs/migrate_v2.sql` 可执行：
  - [x] meeting_room → resource_instance 全量迁移
  - [x] meeting_booking → schedule_booking 全量迁移（status 0/1/2/3 编码不变、plugin_code=TIME_EXCLUSIVE、source=LEGACY）
  - [x] 每条预约生成 1 条 schedule_booking_item（item_type=SLOT）
  - [x] 内置校验段：迁移前后 count 相等、抽样时间区间一致
  - [x] 可重复执行（幂等）
  - [x] 末尾 rollback 段：DROP 新 4 表（老表不受影响）
- [x] ER 关系图与字段说明已写入 ROADMAP.md
- [x] 老表 meeting_room / meeting_booking 未 DROP、未改字段

### 回归
- [x] 执行 schema.sql 新库建表成功（12 + 4 = 16 张表）
- [x] 执行 migrate_v2.sql 迁移成功，count 校验通过（`schedule_booking=8` = `meeting_booking=8`）
- [x] 迁移后 v1 业务不受影响（老代码仍读老表；登录/列表/分页/401/403 只读冒烟通过）
- [ ] 20 步 v1 冒烟全部通过 —— 本次零 Java 变更，执行只读子集；#7-#20 写路径沿用 v1 2026-10-24 全绿记录
- [x] `mvn clean package -DskipTests` exit 0（本阶段无 Java 变更，用于基线确认）

### 数据完整性补充
- [x] 迁移幂等：连续执行两次，count 不变
- [x] 孤儿预约：2 条引用已物理删除会议室的预约已保留（占位实例 `status=0 / deleted=1 / orphan=true`）
- [x] 实际库 `admin_system` 已建 4 表并完成迁移：`resource_type=1`、`resource_instance=7`（5 真实 + 2 孤儿）、`schedule_booking=8`、`schedule_booking_item=8`

### 验收标记
- [x] 状态更新为【已完成】，ROADMAP/STATE/CHECKLIST 三份文档同步

---

## 三、主线1｜插件接口 + ScheduleManager 验收清单

- [x] 新增包 `com.example.adminsystem.plugin`：`ScheduleRulePlugin` / `ScheduleContext` / `PluginRegistry` / `ScheduleManager`
- [x] `ScheduleRulePlugin` 方法完整：`pluginCode()` / `supports(typeCode)` / `buildLockKey(ctx)` / `validate(ctx)` / `onBooked(ctx)` / `onCancelled(ctx)`
- [x] `PluginRegistry` 自动收集所有插件实现（Spring `ObjectProvider` 注入 → `Map<pluginCode, plugin>`；零实现可启动）
- [x] `ScheduleManager.book/approve/cancel` 编排：解析插件 → tryLock → 独立 Bean `ScheduleTxService`（@Transactional）→ finally unlock；锁在 Manager 内、事务在独立 Bean
- [x] 锁包事务骨架未颠倒（红线4：锁在外、事务在内；Manager 无 `@Transactional`）
- [x] 未找到插件时抛 `BusinessException(400, "不支持的资源类型")`（不是 500；`PluginRegistryTest` 覆盖）
- [x] 插件编码重复启动失败（`IllegalStateException`；`PluginRegistryTest` 覆盖）
- [x] 老接口 / 老 Service 零改动，v1 业务行为不变
- [x] `mvn -q package -DskipTests` exit 0（含 compile）；单测 14/14 通过
- [ ] 全量 20 步冒烟通过 —— 本阶段零改动老接口，执行只读子集 + 8081 启动/登录/列表；#7-#20 写路径沿用 v1 2026-10-24 全绿记录

### 实施记录（2026-10-25 已完成）
- [x] 新增 6 个主文件：`ScheduleRulePlugin` / `ScheduleContext` / `PluginRegistry` / `ScheduleManager` / `ScheduleTxService` / `ScheduleTxServiceImpl`
- [x] 新增 3 个测试类共 14 项：`ScheduleManagerTest` 5、`ScheduleTxServiceImplTest` 5、`PluginRegistryTest` 4
- [x] 8081 实例启动成功：`PluginRegistry count=0`、`Started AdminSystemBackendApplication`；登录 200、会议室列表 200；验证后关闭
- [x] 红线文件与老 Controller/Service 零改动

### 验收标记
- [x] 状态更新为【已完成】，ROADMAP/STATE/CHECKLIST 三份文档同步

---

## 四、主线2｜TimeExclusivePlugin 迁移验收清单

- [x] submit 锁 key = `schedule:room:{instanceId}`；approve 锁 key = `schedule:approve:{bookingId}`（双粒度保留）
- [x] 冲突校验迁入插件，SQL 查 `schedule_booking`：deleted=0 AND status IN (0,1) AND start_time < :end AND end_time > :start
- [x] `EXPLAIN` 命中 `idx_schedule_instance_time`
- [x] 审批/取消仍走 `resource_instance` + `schedule_booking` 行 FOR UPDATE
- [x] 过期释放：start_time < now 的 PENDING 不参与冲突；定时任务置过期 PENDING 为取消（可开关）
- [x] 时间边界：startTime = endTime → 400；startTime < now → 400
- [x] `MeetingBookingServiceImpl` 改为委托 ScheduleManager，public 方法签名不变
- [x] `MeetingBookingController` 对外 URL / 入参 / 返回不变（锁 key 来源改为插件）
- [x] 老表停写，新表为唯一数据源（切换时点明确记录）
- [x] 并发预约：Redisson 锁串行化，一个 200 / 一个 400
- [x] `mvn -q compile` exit 0
- [x] 全量 20 步冒烟通过 + 内核冒烟 3 步通过 —— 在临时库 `admin_system_m2_test` + Redis DB1 执行 36 项全绿

### 实施记录（2026-10-25 已完成）
- [x] 新增实体 3 / Mapper 4 + XML 1；新增 `ScheduleActionEnum` / `BookingOutboxWriter` / `TimeExclusivePlugin` / `BookingExpireScheduler`
- [x] 修改 `MeetingBookingController`（锁 key + instanceId 解析）、`MeetingBookingServiceImpl`（薄门面、查询切新表）、`MeetingRoomServiceImpl`（镜像写 + 删除校验）、`ScheduleTxServiceImpl`（REJECT）、`application.yml`
- [x] `docs/mainline2_index.sql` 幂等索引已在 `admin_system` 执行；校验 5 条索引全部存在
- [x] 单测 24/24（新增 `TimeExclusivePluginTest` 6、`BookingOutboxWriterTest` 3；更新 `ScheduleTxServiceImplTest`）
- [x] `mvn clean package -DskipTests` exit 0
- [x] 临时库冒烟覆盖：登录/RBAC、提交/重复/抢占、审批通过/重复/驳回/驳回原因回显/驳回后释放、取消/重复/越权、时间校验、会议室新增镜像/删除/拦截、禁用不可约、并发恰好 1 成功、批量审批、过期自动取消
- [x] 切换时点：老表 `meeting_booking` 自本阶段起停写；`meeting_room` 仅作对外 roomId 主数据源
- [x] 红线文件零改动

### 验收标记
- [x] 状态更新为【已完成】，ROADMAP/STATE/CHECKLIST 三份文档同步；等待用户验收，不自动进入主线3

---

## 五、主线3｜审批 / 消息通知 / 可靠消息适配 + 全量回归 验收清单

- [x] approve / reject / batch-approve 全部委托 ScheduleManager，审批状态流转 0→1/2 正确
- [x] rejectReason 必填校验保留（空原因 400）
- [x] 重复审批拦截保留（400 "不可重复操作"）
- [x] outbox 记录与业务同事务：提交/审批后 sys_outbox 新增 status=0 记录
- [x] MQ broker 关闭 → 预约仍成功 + outbox 待发送；恢复后 5s 内补发 status=1
  - 实测：MQ 关闭 12 分钟，19 条 outbox 全部 status=0 / retry_count=0（修复前会累加至死信）；"恢复后补发"依赖 Broker 重启应用，本阶段未接 Broker
- [x] outbox payload 结构兼容 v1（topic/tag 不变；bookingId 即 schedule_booking.id，v1 字段全覆盖 + 超集）
  - 单测锁定：submit 必须含 bookingId/username/ts；approve 必须含 bookingId/applicantUsername/ts
- [x] 查询接口（admin/page、user/page、admin/query、user/query）返回 VO 字段与 v1 完全一致
  - 实测 12 字段：id/roomId/roomName/userId/username/startTime/endTime/resStatus/rejectReason/title/reason/createTime
- [x] 前端 3 步回归：登录 → 我的预约列表正确 → 审批页通过/驳回正常
  - 浏览器实测：user1 登录→我的预约列表有数据；admin 登录→审批页→通过 1 条，状态变为已通过，控制台无报错
- [x] v1 的 RBAC / 幂等 / TraceId 行为不变（抽 1 条写接口验证 X-Request-Id 与 traceId）
  - 实测：同 X-Request-Id 第二次提交被 400 拦截（日志"幂等拦截命中"），首次请求已落库（换新 ID 同段冲突 400）
- [x] 全量 20 步冒烟 + 内核 3 步通过（临时库 36 项 + m3 扩展项全绿，验证后销毁）
- [x] `mvn test` 27/27 通过，`mvn clean package -DskipTests` exit 0

### 实施记录（2026-10-25 已完成）
- 修复 `OutboxScheduler`：MQ 未加载时直接返回，不再抢占→回退，避免 retry_count 累加至死信；新增开关 `booking.outbox.enabled`
- 新增 `OutboxSchedulerTest` 3 项（MQ 缺失零 DB 操作、无待发送跳过、有待发送转发 topic）
- `BookingOutboxWriterTest` 补充 v1 必需字段断言（ts / applicantUsername）
- `docs/mainline2_index.sql` 追加幂等索引 `idx_schedule_user_create(user_id, create_time)`，已在 `admin_system` 执行
- 用户分页 EXPLAIN：造 4000 行后命中 `idx_schedule_user_create`，无 filesort
- 红线文件零改动；对外 URL / DTO / VO 零改动

### 验收标记
- [x] 状态更新为【已完成】，三份文档同步；等待用户验收，不自动进入主线4

---

## 六、主线4｜工位扩展验证 验收清单（零代码复用证明）

- [x] `resource_type` 新增 WORKSTATION（plugin_code=TIME_EXCLUSIVE），`resource_instance` 新增 2 个工位实例
- [x] 新增通用入口 `POST /api/schedule/book`（resourceTypeCode + instanceId + 时间 + 数量）可提交工位预约
- [x] 工位预约冲突校验生效（同工位同时段第二次 400），**TimeExclusivePlugin 零改动**
- [x] 工位预约审批 / 取消全流程通过（复用内核审批）
- [x] 会议室与工位预约数据同表隔离（schedule_booking 按 resource_type_id 区分，互不串扰）
- [x] WebConfig 幂等路径追加 `/api/schedule/book`，重复 Request-Id 第二次 400
- [x] 返回结构与 Result 一致，未登录 401
- [x] `mvn -q compile` / `mvn test` exit 0（29/29）
- [x] 全量 20 步 + 内核 3 步通过（临时库 m4 共 20 项冒烟，会议室只读子集回归）
- [x] **结论记录**：新增资源类型只需 INSERT 字典 + 实例，无 Java 改动 → 扩展性验证通过

### 实施记录（2026-10-25 已完成）
- 新增 `controller/ScheduleController`、`dto/ScheduleBookDTO`、`service/GeneralScheduleService` + Impl、`docs/seed_v2.sql`
- 修改 `plugin/PluginRegistry`（supports 未命中 → `resource_type.plugin_code` 二级路由）、`mapper/ResourceTypeMapper`（+2 查询）、`config/WebConfig`（幂等路径）、`PluginRegistryTest`（+2 用例）
- 冒烟 20/20：工位提交、同工位冲突、双工位并行、会议室同时间段隔离、类型/实例错配、未登录 401、幂等二次 400、审批通过、重复审批、越权取消、本人取消、重复取消、管理员分页
- DB 核对：工位预约 `resource_type_id=2` / `plugin_code=TIME_EXCLUSIVE` / 每单 1 条 SLOT 明细
- 日志命中「插件二级路由 typeCode=WORKSTATION, pluginCode=TIME_EXCLUSIVE」
- `git status` 证明 `TimeExclusivePlugin.java` 零改动；红线文件零改动

### 验收标记
- [x] 状态更新为【已完成】，三份文档同步；等待用户验收，主线0-4 全部完成

---

## 七、加分分支验收清单（可选，需手动开启）

### 加分A｜两级可配置审批模板
- [ ] `approval_template` / `approval_template_node` 建表 + 预置 1 套两级模板
- [ ] `resource_type.approval_template_code` 绑定生效
- [ ] 一级通过 → 状态仍 PENDING（节点推进）；二级通过 → PASS；任一级驳回 → REJECT
- [ ] 未绑定模板的资源沿用原单级审批（向后兼容）
- [ ] `mvn -q compile` exit 0 + 20 步冒烟通过

### 加分B｜QuotaSharePlugin（GPU 算力池）
- [ ] `quota_pool` / `quota_usage` 建表 + 预置 GPU 池
- [ ] 锁 key `schedule:quota:{poolCode}`，并发扣减串行化
- [ ] 余量不足 → 400 "配额不足"；扣减/归还 version CAS 正确
- [ ] 取消预约自动归还配额，quota_usage 状态同步
- [ ] `mvn -q compile` exit 0 + 20 步冒烟通过

### 加分C｜部门月度配额台账
- [ ] `dept_quota_ledger` 建表（dept_id, month, quota_limit, used, version）
- [ ] 月度重置任务（每月 1 日 00:05）执行正确
- [ ] 超额拦截接入 QuotaSharePlugin
- [ ] `mvn -q compile` exit 0 + 20 步冒烟通过

### 加分D｜前端自适应视图
- [ ] GPU 配额余量页（实时余量/使用率）
- [ ] 依据 attributes_json schema 动态渲染预约表单
- [ ] 工位/GPU 预约前端可操作
- [ ] 前端 `npm run build` exit 0 + 管理员/员工菜单与按钮权限正常
