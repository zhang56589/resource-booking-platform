# 资源调度内核重构｜Builder 主控路线图

> **改造目标**：会议室预约单体系统 → 轻量可扩展资源调度内核（插件化架构）
> **当前基线**：v1 会议室预约系统已交付（P0-1~P2-1 共 10 阶段全部完成），12 张表、6 个 Controller、Redisson/RocketMQ/Outbox/RBAC/TraceId 全套基础设施就绪
> **文档版本**：v2 路线图（2026-10-25 主线 0/1 已完成，主线 2 待启动）

---

## 一、基线快照（只读复用，不重构）

| 项 | 现状 | 复用方式 |
|---|---|---|
| 技术栈 | JDK 21 / Spring Boot 3.3.4 / MP 3.5.5 / Redisson 3.37.0 / MySQL 5.7+ | 原样复用 |
| 现有表（12 张） | sys_user、meeting_room、meeting_booking、operation_log、sys_login_log、sys_operate_log、sys_outbox、sys_role、sys_permission、sys_role_permission、sys_menu、sys_role_menu | 老表保留，主线 0 新增 4 张内核表 |
| 分布式锁 | Controller 层 tryLock → Service @Transactional → finally unlock；submit 锁 `booking:room:{roomId}`，approve 锁 `booking:approve:{bookingId}` | 迁移进插件（主线 2），锁包事务骨架不变 |
| 冲突校验 | `MeetingBookingServiceImpl.hasTimeConflict()`（lt/gt 双重区间判断）+ `selectByIdForUpdate` FOR UPDATE | 迁移进 TimeExclusivePlugin（主线 2） |
| 可靠消息 | `sys_outbox` + `OutboxScheduler`（5s 轮询、指数退避、死信） | 内核统一走 outbox（主线 3 适配） |
| 权限 | TokenInterceptor（@RequireAdmin）+ PermissionAspect（@RequiresPermission）双轨 | 原样复用，内核接口沿用注解 |
| 幂等 | IdempotentInterceptor（X-Request-Id + Redisson SETNX） | WebConfig 路径追加内核新接口 |
| 配置/日志 | Nacos `spring.config.import` 热刷新 + MDC TraceId | 原样复用 |
| 前端 | Vue3 + Element Plus，6 个业务页面 + 动态菜单 + v-permission | 主线阶段不改前端（加分 D 才动） |

---

## 二、绝对禁止修改清单（红线，全程自动拦截）

1. `TokenInterceptor.java`
2. `Result.java` / `BusinessException.java`
3. `GlobalExceptionHandler.java` 已有的锁超时、业务异常分支（只能新增方法，不改已有逻辑）
4. 现有对外接口签名：`/api/booking/*`、`/api/room/*`、`/api/user/*` 的 URL、入参 DTO、返回结构（主线 3 前必须保持兼容，允许 Controller 内部改为委托内核）
5. 老表 `meeting_room` / `meeting_booking` 在主线 4 全量回归通过前，禁止 DROP、禁止改字段
6. 不引入重型中间件（分库分表、分布式事务框架、流程引擎等）；加分 A 的审批模板用轻量配置表实现，不引入 Flowable/Activiti

---

## 三、主线任务（P0 必做，严格串行，不可打乱）

### 主线0｜基线确认 + 内核数据模型设计 + 老数据迁移脚本 ｜【已完成】

- **目标范围**：只做数据模型与迁移脚本，不写业务代码、不改现有 Service
- **数据模型（新增 4 张表）**

| 表 | 作用 | 关键字段 |
|---|---|---|
| `resource_type` | 资源类型字典（会议室 / 工位 / GPU 池） | `type_code`(MEETING_ROOM/WORKSTATION/GPU_POOL)、`type_name`、`plugin_code`(TIME_EXCLUSIVE/QUOTA_SHARE)、`approval_template_code`（加分A预留，先留空）、`enabled`、`deleted` |
| `resource_instance` | 资源实例（具体会议室 / 具体工位） | `type_id`、`instance_code`、`instance_name`、`capacity`、`location`、`attributes_json`（JSON：设备/楼层/GPU型号等差异化属性）、`status`(0禁用/1启用)、`deleted` |
| `schedule_booking` | 统一预约主表（替代 meeting_booking 的通用版） | `booking_no`、`resource_type_id`、`resource_instance_id`、`user_id`、`start_time`、`end_time`、`quantity`（配额类资源用，时段类固定 1）、`status`、`plugin_code`、`source`(LEGACY/API)、`ext_json`（插件扩展字段）、`deleted`、`create_time`、`update_time` |
| `schedule_booking_item` | 预约明细表（一次预约的多个子项） | `booking_id`、`item_type`(SLOT/SEAT/QUOTA)、`item_key`（工位号/时间段/配额维度）、`quantity`、`unit`、`ext_json`、`deleted` |

- **ER 关系**：`resource_type 1--N resource_instance`；`resource_instance 1--N schedule_booking`；`schedule_booking 1--N schedule_booking_item`；`sys_user 1--N schedule_booking`
- **索引设计**：`schedule_booking(resource_instance_id, start_time, end_time)`、`schedule_booking(user_id, status)`、`schedule_booking(resource_type_id, start_time)`、`schedule_booking_item(booking_id)`
- **老数据迁移脚本 `docs/migrate_v2.sql`**：
  1. `meeting_room` → `resource_instance`（type_id 取 MEETING_ROOM；room_name→instance_name；capacity/location 直映；description 写入 attributes_json）
  2. `meeting_booking` → `schedule_booking`（room_id→resource_instance_id；status 编码保持 0/1/2/3；plugin_code=TIME_EXCLUSIVE；source=LEGACY）
  3. 每条预约生成 1 条 `schedule_booking_item`（item_type=SLOT，item_key=实例 code）
  4. 校验段：迁移前后 count 比对、时间区间抽样比对
  5. 幂等：脚本可重复执行（先 DELETE 迁移标记数据 / 或 INSERT IGNORE by booking_no）
- **变更文件清单（本阶段）**：`docs/schema.sql`（追加 4 表 DDL，不删旧表）、新增 `docs/migrate_v2.sql`、`.trae/documents/` 三份文档
- **风险**：① 迁移数据量小（演示系统），风险低；② 状态编码映射错误导致审批历史失真 → 脚本内建校验段；③ 新旧表并存期双写缺失 → 主线 0-2 采用"老流程写老表 + 新表只读迁移验证"，主线 3 统一切新表
- **回滚**：`docs/migrate_v2.sql` 末尾提供 rollback 段（DROP 新 4 表），老表未动，业务零影响
- **依赖**：无
- **实施记录（2026-10-25 已完成）**：
  - `docs/schema.sql` 已追加 4 张内核表，新库共 12 + 4 = 16 张表；`resource_type` 预置 1 条 MEETING_ROOM / TIME_EXCLUSIVE
  - `docs/migrate_v2.sql` 已创建并执行：会议室→实例、预约→统一预约、每单 1 条 SLOT 明细；按业务唯一键判重，可重复执行
  - 孤儿数据处理：老预约引用了已物理删除的会议室时，补建 `status=0 / deleted=1` 占位实例（`attributes_json.orphan=true`），避免历史预约丢失
  - 实际库 `admin_system` 结果：`resource_type=1`、`resource_instance=7`（5 真实 + 2 孤儿）、`schedule_booking=8`、`schedule_booking_item=8`
  - 老表未改动：`meeting_room=5`、`meeting_booking=8`；校验段全部 OK，重复执行两次 count 不变
  - 编译自检：`mvn clean package -DskipTests` exit 0
  - 冒烟：登录、会议室列表、管理员/用户分页、未登录 401、越权 403 均符合预期；写路径沿用 v1 2026-10-24 全绿记录

### 主线1｜内核抽象层：ScheduleRulePlugin 插件接口 + ScheduleManager 调度管理器 ｜【已完成】

- **目标范围**：只建抽象骨架 + 空实现接线，不迁移任何业务逻辑（业务仍在老 Service）
- **接口设计**
  - `ScheduleRulePlugin`：`pluginCode()` / `supports(typeCode)` / `buildLockKey(ctx)` / `validate(ctx)`（冲突或配额校验）/ `onBooked(ctx)`（写 outbox、扣减等）/ `onCancelled(ctx)`（归还等）
  - `ScheduleContext`：resourceType、resourceInstance、userId、startTime、endTime、quantity、bookingId、adminId、extParams
  - `PluginRegistry`：`Map<pluginCode, ScheduleRulePlugin>`，Spring 自动注入所有实现
  - `ScheduleManager`：`book(ctx)` / `approve(ctx)` / `cancel(ctx)`；职责 = 解析插件 → tryLock → 调用独立 Bean `ScheduleTxService`（@Transactional）→ finally unlock；Manager 自身无 `@Transactional`，保证锁在事务外
- **变更文件清单（新增）**：`plugin/ScheduleRulePlugin.java`、`plugin/ScheduleContext.java`、`plugin/PluginRegistry.java`、`plugin/ScheduleManager.java`、`service/ScheduleTxService.java`（+Impl）
- **风险**：① 抽象过度导致后续插件放不下 → 上下文用 extParams/extJson 兜底扩展；② 自调用 AOP 失效 → 事务放独立 Bean；③ 不影响老接口
- **回滚**：新增文件全部为独立包，删除即回滚
- **依赖**：主线 0
- **实施记录（2026-10-25 已完成）**：
  - 新增 `plugin` 包 4 个文件 + `ScheduleTxService` 接口/实现；未改任何现有 Java 文件
  - `PluginRegistry` 使用 `ObjectProvider` 注入，零插件实现时应用可正常启动
  - `ScheduleManager` 负责锁编排，`ScheduleTxServiceImpl` 负责事务与插件钩子；锁在事务外
  - 单测 14/14：`ScheduleManagerTest` 5 项、`ScheduleTxServiceImplTest` 5 项、`PluginRegistryTest` 4 项
  - `mvn -q package -DskipTests` exit 0；8081 启动日志 `PluginRegistry count=0`、`Started AdminSystemBackendApplication`，无 `NoSuchBeanDefinition` / `UnsatisfiedDependency`
  - 8081 实例登录 200、会议室列表 200；验证后已关闭
  - 老接口零改动；20 步写路径沿用 v1 2026-10-24 全绿记录

### 主线2｜TimeExclusivePlugin：迁移时间冲突校验 + 分布式锁 + 过期释放 ｜【待开始】

- **目标范围**：把老会议室逻辑完整搬进插件，老 Service 改为委托；对外接口不变
- **迁移内容**
  1. 锁粒度：submit `schedule:room:{instanceId}`、approve `schedule:approve:{bookingId}`（沿用 v1 双粒度结论）
  2. 冲突校验：`hasTimeConflict` 逻辑迁入插件，SQL 查 `schedule_booking`（deleted=0 AND status IN (0,1) AND start_time < :end AND end_time > :start）
  3. FOR UPDATE：审批/取消仍锁 resource_instance + booking 行
  4. 过期释放：`start_time < now` 的未审批记录不参与冲突（懒失效）+ 定时任务将过期 PENDING 置为取消（可配置关闭）
  5. 状态机：0待审批 / 1通过 / 2驳回 / 3取消（编码不变）
- **变更文件清单（修改）**：`MeetingBookingServiceImpl`（改为委托 ScheduleManager，方法签名不变）、`MeetingBookingController`（锁 key 改由插件提供，锁包事务位置不变）、新增 `plugin/timeexclusive/TimeExclusivePlugin.java`、`resources/mapper/ScheduleBookingMapper.xml`
- **风险**：① 双写期数据不一致 → 主线 2 起老表停写、新表统一切换，切换前跑迁移脚本；② 冲突 SQL 走错索引 → 强制命中 `(resource_instance_id, start_time, end_time)`；③ 锁 key 变化导致灰度期间互斥失效 → 单实例部署，无灰度
- **回滚**：git 还原 Service/Controller，插件包删除，数据仍在老表（老表只读未删）
- **依赖**：主线 1

### 主线3｜审批 / 消息通知 / 可靠消息链路适配 + 会议室全业务回归 ｜【待开始】

- **目标范围**：审批、outbox、MQ 通知全部走内核，v1 会议室 20 步冒烟 + 前端 3 步全绿
- **适配内容**：approve/cancel/batch-approve 委托 ScheduleManager；outbox payload 保持 v1 结构（topic/tag 不变，bookingId→scheduleId）；`GET /api/booking/*` 查询接口改查 `schedule_booking`（VO 字段保持不变）
- **变更文件清单**：`MeetingBookingController`、`MeetingBookingServiceImpl`、`MeetingBookingMapper.xml`、`SysOutbox` payload 构造
- **风险**：① VO 字段变化破坏前端 → 查询 SQL 别名对齐原 VO；② 历史数据 status 语义差异 → 迁移脚本校验
- **回滚**：还原 3 个文件 + 查询改回老表
- **依赖**：主线 2

### 主线4｜扩展能力验证：工位资源复用同一时段独占插件 ｜【待开始】

- **目标范围**：只做后端验证，不做前端页面
- **验证内容**：插入 `resource_type(WORKSTATION, TIME_EXCLUSIVE)` + 2 个工位实例 → 通过内核统一接口（`POST /api/schedule/book`）完成提交/冲突/审批/取消全流程 → 证明零代码改动复用插件
- **变更文件清单**：新增 `controller/ScheduleController.java`（通用预约入口：`POST /api/schedule/book`、`DELETE /api/schedule/cancel/{id}`）、`WebConfig` 幂等路径追加 1 条、`docs/seed_v2.sql`（工位演示数据）
- **风险**：① 通用接口权限模型 → 沿用登录用户 + @RequiresPermission("booking:submit")；② 与老接口重复 → 老接口保留（旨在兼容），新接口独立路径
- **回滚**：删除 ScheduleController + seed 数据
- **依赖**：主线 3

---

## 四、加分分支（P1 可选，必须用户手动下达【开启加分A/B/C/D】才启动，不阻塞主线）

### 加分A｜两级可配置审批模板，按资源类型绑定 ｜【可选加分｜未开启】
- 表：`approval_template`（模板编码/名称/层级数）、`approval_template_node`（模板ID/层级/角色/审批条件）
- 流程：提交 → 一级审批通过 → 二级审批通过 → 终态；`resource_type.approval_template_code` 绑定
- 影响：审批模板服务 + ScheduleManager 审批节点分流 + 前端审批页多级展示

### 加分B｜QuotaSharePlugin 配额共享插件（GPU 算力池） ｜【可选加分｜未开启】
- 表：`quota_pool`（pool_code、total、used、version 乐观锁）、`quota_usage`（booking_id、pool_code、quantity、状态）
- 逻辑：锁 `schedule:quota:{poolCode}` → 校验余量 → 扣减（version CAS）→ 取消归还 → outbox 通知
- 影响：新增插件 + 资源类型 GPU_POOL + 配额台账事务

### 加分C｜部门月度配额台账 ｜【可选加分｜未开启】
- 表：`dept_quota_ledger`（dept_id、month、quota_limit、used、version）；月度重置定时任务
- 影响：台账服务 + 每月 1 日 00:05 重置任务 + 超额拦截接入 QuotaSharePlugin

### 加分D｜前端自适应视图：GPU 配额余量页 + 动态表单渲染 ｜【可选加分｜未开启】
- 依据 `resource_instance.attributes_json` schema 渲染动态表单；新增配额余量卡片页
- 影响：前端 views/resource/* 新页面 + 表单渲染组件；后端补 attributes schema 返回

---

## 五、阶段依赖图

```
主线0 数据模型+迁移
   ↓
主线1 内核抽象（接口+管理器）
   ↓
主线2 TimeExclusivePlugin（会议室逻辑迁移）
   ↓
主线3 审批/可靠消息适配 + 全量回归
   ↓
主线4 工位扩展验证（零代码复用证明）

加分A ──┐
加分B ──┼── 均依赖主线4（内核稳定后可选叠加，互不阻塞）
加分C ──┤
加分D ──┘
```

---

## 六、进度总览

| 编号 | 任务 | 类型 | 优先级 | 状态 |
|---|---|---|---|---|
| 主线0 | 基线确认 + 数据模型 + 迁移脚本 | 主线 | P0 | 【已完成】 |
| 主线1 | 插件接口 + ScheduleManager | 主线 | P0 | 【已完成】 |
| 主线2 | TimeExclusivePlugin 迁移 | 主线 | P0 | 【待开始】 |
| 主线3 | 审批/消息适配 + 全量回归 | 主线 | P0 | 【待开始】 |
| 主线4 | 工位扩展验证 | 主线 | P0 | 【待开始】 |
| 加分A | 两级审批模板 | 可选加分 | P1 | 【可选加分｜未开启】 |
| 加分B | QuotaSharePlugin | 可选加分 | P1 | 【可选加分｜未开启】 |
| 加分C | 部门月度配额台账 | 可选加分 | P1 | 【可选加分｜未开启】 |
| 加分D | 前端自适应视图 | 可选加分 | P1 | 【可选加分｜未开启】 |
