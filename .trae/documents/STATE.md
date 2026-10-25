# 资源调度内核重构｜Builder 主控运行时状态

## 阶段进度

| 编号 | 名称 | 类型 | 优先级 | 状态 | 阻塞原因 | 开始时间 | 完成时间 |
|---|---|---|---|---|---|---|---|
| v1 | 会议室预约系统 P0-1~P2-1（10 阶段） | 历史基线 | — | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| 主线0 | 基线确认 + 数据模型 + 老数据迁移脚本 | 主线 | P0 | ✅ **已完成** | SQL 已落盘并验证：迁移/幂等/校验/编译均通过 | 2026-10-25 | 2026-10-25 |
| 主线1 | 插件接口 + ScheduleManager 调度管理器 | 主线 | P0 | ✅ **已完成** | 代码/单测/启动验证均通过 | 2026-10-25 | 2026-10-25 |
| 主线2 | TimeExclusivePlugin 迁移 | 主线 | P0 | ✅ **已完成** | 代码/单测/临时库冒烟/索引均通过，等待用户验收 | 2026-10-25 | 2026-10-25 |
| 主线3 | 审批/消息适配 + 会议室全量回归 | 主线 | P0 | 🔵 **待确认** | 方案已输出，等待用户确认 | 2026-10-25 | — |
| 主线4 | 工位扩展验证 | 主线 | P0 | ⏳ **待开始** | 依赖主线3 | — | — |
| 加分A | 两级可配置审批模板 | 可选加分 | P1 | ⛔ **未开启** | 需用户指令【开启加分A】 | — | — |
| 加分B | QuotaSharePlugin（GPU 配额） | 可选加分 | P1 | ⛔ **未开启** | 需用户指令【开启加分B】 | — | — |
| 加分C | 部门月度配额台账 | 可选加分 | P1 | ⛔ **未开启** | 需用户指令【开启加分C】 | — | — |
| 加分D | 前端自适应视图 | 可选加分 | P1 | ⛔ **未开启** | 需用户指令【开启加分D】 | — | — |

---

## 当前状态摘要

- **当前阶段**：主线3（审批/消息通知/可靠消息适配 + 全量回归）【待确认】
- **执行状态**：🔵 待确认 —— 方案已输出，未写任何代码
- **下一步**：等待用户确认（`同意方案，开始改代码`）。主线2 已完成，等待一并验收
- **主线串行约束**：主线0/1/2 已完成；主线3 未确认前禁止编码；主线未全部完成前，禁止启动任何加分分支

### 主线0 实施结果（2026-10-25 已完成）

- `docs/schema.sql` 已追加 4 张内核表；新库共 12 + 4 = 16 张表，`resource_type` 预置 MEETING_ROOM
- `docs/migrate_v2.sql` 已创建并执行：全量迁移 + 内置校验 + 幂等 + 回滚段
- 孤儿预约处理：为已物理删除的会议室补建占位实例（`status=0`、`deleted=1`、`attributes_json.orphan=true`）
- 实际库 `admin_system`：`resource_type=1`、`resource_instance=7`（5 真实 + 2 孤儿）、`schedule_booking=8`、`schedule_booking_item=8`
- 老表未改动：`meeting_room=5`、`meeting_booking=8`；校验段全部 OK；重复执行两次 count 不变
- 编译：`mvn clean package -DskipTests` exit 0
- 冒烟：登录、会议室列表、管理员/用户分页、未登录 401、越权 403 均符合预期；写路径沿用 v1 2026-10-24 全绿记录

### 主线1 实施结果（2026-10-25 已完成）

新增文件（现有 Java 文件零修改）：
- `plugin/ScheduleRulePlugin.java`
- `plugin/ScheduleContext.java`
- `plugin/PluginRegistry.java`
- `plugin/ScheduleManager.java`
- `service/ScheduleTxService.java`
- `service/impl/ScheduleTxServiceImpl.java`
- `src/test/.../plugin/ScheduleManagerTest.java`（5 项）
- `src/test/.../plugin/PluginRegistryTest.java`（4 项）
- `src/test/.../service/impl/ScheduleTxServiceImplTest.java`（5 项）

验证结果：
- 单测 14/14 通过
- `mvn -q package -DskipTests` exit 0
- 8081 实例启动成功：`PluginRegistry count=0`、`Started AdminSystemBackendApplication`，无 `NoSuchBeanDefinition` / `UnsatisfiedDependency`
- 8081 实例登录 200、会议室列表 200；验证后已关闭
- 红线文件（TokenInterceptor / Result / BusinessException / GlobalExceptionHandler）与老 Controller/Service 零改动
- 20 步写路径未重复执行，沿用 v1 2026-10-24 全绿记录

### 主线2 实施结果（2026-10-25 已完成）

新增文件：
- 实体：`entity/ScheduleBooking.java`、`entity/ScheduleBookingItem.java`、`entity/ResourceInstance.java`
- Mapper：`mapper/ResourceTypeMapper.java`、`mapper/ResourceInstanceMapper.java`、`mapper/ScheduleBookingMapper.java`、`mapper/ScheduleBookingItemMapper.java`、`resources/mapper/ScheduleBookingMapper.xml`
- 内核：`plugin/ScheduleActionEnum.java`、`plugin/support/BookingOutboxWriter.java`、`plugin/impl/TimeExclusivePlugin.java`
- 调度：`scheduler/BookingExpireScheduler.java`（cron 可配，默认 5 分钟；冲突 SQL 已做懒失效）
- SQL：`docs/mainline2_index.sql`（幂等索引，已在 `admin_system` 执行成功）
- 测试：`plugin/impl/TimeExclusivePluginTest.java`（6 项）、`plugin/support/BookingOutboxWriterTest.java`（3 项）

修改文件：
- `plugin/ScheduleRulePlugin.java`（新增默认钩子 `onRejected`）
- `plugin/ScheduleContext.java`（新增 action/instanceName/roomId/title/reason/username）
- `service/impl/ScheduleTxServiceImpl.java`（approve：PASS→onBooked / REJECT→onRejected / 非法状态 400）
- `service/MeetingBookingService.java`、`service/impl/MeetingBookingServiceImpl.java`（去 IService，改薄门面，查询走新表）
- `controller/MeetingBookingController.java`（锁 key 改 `schedule:room:{instanceId}` / `schedule:approve:{bookingId}`；submit 前解析 instanceId；锁在外事务在内骨架不变）
- `service/impl/MeetingRoomServiceImpl.java`（resource_instance 镜像写：新增/改/启停/软删；删除校验优先查 schedule_booking）
- `resources/application.yml`（新增 booking.expire.enabled/cron）
- `src/test/.../service/impl/ScheduleTxServiceImplTest.java`（REJECT 断言改为 validate + onRejected）

验证结果：
- 单测 24/24 通过（主线1 14 + 主线2 新增 10）
- `mvn clean package -DskipTests` exit 0
- 临时库 `admin_system_m2_test` + Redis DB1 + 8081 实例：36 项冒烟全绿
  - 覆盖：三账号登录、会议室列表、提交/重复提交/他人抢占、用户与管理员分页、审批通过/重复审批、取消/重复取消/越权取消、驳回缺原因拦截、驳回原因回显、驳回后时段释放、时间倒序/过去时间拦截、会议室新增/删除/有未完结预约删除拦截、禁用后不可预约、并发抢同一时段恰好 1 成功、批量审批、过期 PENDING 自动置取消
- 数据库侧核对：新增会议室镜像实例自动生成并在删除时软删；禁用/启用同步 status；outbox 每条预约均有记录（MQ 未启动时按预期退避回待发送）
- EXPLAIN：冲突查询命中 `idx_schedule_instance_time`（range）
- 红线文件（TokenInterceptor / Result / BusinessException / GlobalExceptionHandler）零改动
- 切换时点记录：**自本阶段起老表 `meeting_booking` 停写**，读写均走 `schedule_booking`；`meeting_room` 仅作为对外 `roomId` 主数据源
- 遗留观察（不阻塞验收）：用户分页 `ORDER BY create_time DESC` 在当前小数据量下走 filesort；如需优化可在主线3 追加 `(user_id, create_time)` 索引

### 本次初始化完成事项（未改代码）

1. 读取 README、后端包结构（annotation/aspect/config/controller/dto/entity/enums/interceptor/mapper/scheduler/service/util/vo，无 plugin 包）、前端 views 目录、docs/schema.sql（12 张表）、`MeetingBooking` 实体、`MeetingBookingController` 锁逻辑、`MeetingBookingServiceImpl` 冲突校验与 outbox 逻辑
2. 按新改造目标重写 ROADMAP.md：主线0-4 + 加分A-D，标注类型/优先级/状态/依赖
3. 按新主线重写 CHECKLIST.md：全局冒烟 + 主线0-4 独立验收清单 + 加分A-D 可选验收清单
4. 重写 STATE.md：主线0 标记【待确认】，加分A-D 标记【未开启】

---

## 基线已知问题清单（v2 改造需关注）

| 编号 | 类型 | 问题 | 严重程度 | 对应阶段 | 状态 |
|---|---|---|---|---|---|
| B-1 | 启动阻断 | Redisson eager 初始化，Redis 未启动则 BeanCreationException | 🔴 | 维持现状（先起 Redis） | — |
| B-2 | 架构 | 会议室逻辑与通用调度能力耦合在 MeetingBookingServiceImpl | 🟡 | 主线2 插件化 | 待处理 |
| B-3 | 扩展 | 无资源类型/实例抽象，新增工位需复制整套预约代码 | 🟡 | 主线0/1/4 | 待处理 |
| B-4 | 锁粒度 | submit 为会议室粒度，同会议室不同时段并发被串行化 | 🟡 | 主线2 迁移时保留（业务量小可接受） | — |
| B-5 | 数据 | 老表 meeting_room / meeting_booking 与新内核表并存期需双轨校验 | 🟡 | 主线0 迁移校验 + 主线3 切换 | 待处理 |
| B-6 | 日志 | 登录/操作日志 username 手动传参，无自动注入机制 | 🟢 低 | 不阻塞主线 | — |
| B-7 | 红线 | TokenInterceptor 稳定模块，禁止修改 | — | — | — |

---

## 环境与编译

- **编译必须 JDK 21**：`$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'; mvn -q compile`（系统默认 JDK 1.8 会编译失败）
- 启动顺序：Redis → MySQL（admin_system 库 + schema.sql）→ Nacos（可选）→ RocketMQ（可选）→ 后端 → 前端
- 本地并行启动第二个实例时，Nacos 客户端会共用 `C:\Users\zhang\logs\nacos` 日志文件并冲突；验证实例建议追加：`--spring.config.import=optional:file:./target/noop.yml --spring.cloud.nacos.discovery.enabled=false --spring.cloud.nacos.config.enabled=false`

## 编译自检记录

| 时间 | 命令 | 退出码 | 备注 |
|---|---|---|---|
| 2026-10-24 | v1 P2-1 收尾 `mvn -q compile` | 0 | 零错误零警告；本次初始化未改代码，未重新编译 |
| 2026-10-24 | v1 P1-5 后端 compile + 前端 build | 0 / 0 | 菜单接口 + 前端动态菜单通过 |
| 2026-10-24 | v1 P1-4 TraceId compile | 0 | 零错误零警告 |
| 2026-10-25 | 主线0 `mvn clean package -DskipTests` | 0 | JDK 21；本阶段仅 SQL/文档变更，无 Java 变更 |
| 2026-10-25 | 主线1 `mvn -q -Dtest=ScheduleManagerTest,ScheduleTxServiceImplTest,PluginRegistryTest test` | 0 | 14/14 通过 |
| 2026-10-25 | 主线1 `mvn -q package -DskipTests` | 0 | 8081 启动验证通过 |
| 2026-10-25 | 主线2 `mvn test` | 0 | 24/24 通过 |
| 2026-10-25 | 主线2 `mvn clean package -DskipTests` | 0 | 临时库 36 项冒烟全绿，8081 实例已验证并关闭 |
