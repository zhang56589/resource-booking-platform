# 20 · 改动导航与自检

按任务类型定位改动文件。路径均相对 `admin_system_backend/src/main/java/com/example/adminsystem` 或工程根目录。

## 一、改动点速查

| 任务 | 主要文件 | 备注 |
| --- | --- | --- |
| 新增会议室接口 | `controller/MeetingRoomController`、`service/MeetingRoomService(+Impl)`、`dto/RoomDTO` | 管理端接口加 `@RequireAdmin` 或权限码 |
| 新增预约接口 | `controller/MeetingBookingController`、`service/impl/MeetingBookingServiceImpl`、`dto/ApproveBookingDTO` 等 | 必须经 `ScheduleManager`，不要在 Controller 写业务事务 |
| 新增通用调度能力 | `controller/ScheduleController`、`service/impl/GeneralScheduleServiceImpl`、`dto/ScheduleBookDTO` | 校验「实例归属资源类型」不可省 |
| 新增资源类型 | `docs/seed_v2.sql` 或新增 seed 片段（`resource_type` + `resource_instance`） | 同规则复用 `TIME_EXCLUSIVE`，无需改 Java |
| 新增调度规则插件 | `plugin/impl/*Plugin`（实现 `ScheduleRulePlugin`） | `pluginCode()` 唯一；`PluginRegistry` 通过 `ObjectProvider` 自动收集 |
| 调整锁 key / 粒度 | `plugin/impl/TimeExclusivePlugin.buildLockKey`、Controller 内 `LOCK_KEY_*` 常量 | 两处必须保持一致，锁仍在外层 |
| 新增定时任务 | `scheduler/*Scheduler` | 加 `@ConditionalOnProperty` 开关与 cron 配置项 |
| 新增 MQ 通知 | `plugin/support/BookingOutboxWriter`、`sys_outbox` | 事务内写 outbox，不要在业务代码直接发 MQ |
| 新增表 / 字段 | 实体 + `docs/schema.sql` + `docs/migrate_v2.sql` + Mapper | 存量字段不删改；加 `deleted` 用 `@TableLogic` |
| 新增菜单 / 权限 | `docs/schema.sql`（`sys_menu` / `sys_role_menu` / `sys_permission` / `sys_role_permission`） | 后端 `MenuController` 自动按角色下发 |
| 新增前端页面 | `meeting-vue/src/views/...`、`src/router/index.js`（`meta.role`） | 员工页 `role:0`，管理员页 `role:1` |
| 新增前端接口 | `meeting-vue/src/api/*.js` | 禁止组件内直接写 Axios |
| 按钮级权限 | `v-permission` 指令 + 后端权限码 | 权限码以 `sys_permission.code` 为准 |
| 幂等路径 | `config/WebConfig` 的 `IdempotentInterceptor` 注册块 | 新增写接口时补路径 |
| 全局异常 / 返回码 | `config/GlobalExceptionHandler`、`util/Result` | 红线：只允许新增分支，不改既有分支语义 |

## 二、关键类定位

| 关注点 | 类 |
| --- | --- |
| 鉴权与用户上下文 | `interceptor/TokenInterceptor`（红线）、`util/JwtUtil` |
| 调度内核 | `plugin/ScheduleManager`、`plugin/PluginRegistry`、`service/ScheduleTxService`、`plugin/impl/TimeExclusivePlugin` |
| 资源查询 | `mapper/ResourceTypeMapper`（`selectPluginCodeByTypeCode`、`selectTypeCodeById`）、`mapper/ResourceInstanceMapper` |
| 抢锁与行锁 | `MeetingBookingController`、`ScheduleController`、`TimeExclusivePlugin` |
| 本地消息 | `plugin/support/BookingOutboxWriter`、`scheduler/OutboxScheduler`、`service/SysOutboxService(+Impl)` |
| 过期释放 | `scheduler/BookingExpireScheduler`、`ScheduleBookingMapper` 冲突/过期 SQL |
| 幂等 / 链路 | `interceptor/IdempotentInterceptor`、`interceptor/TraceIdInterceptor` |
| 权限 AOP | `annotation/RequiresPermission`、`aspect/PermissionAspect` |
| 动态菜单 | `controller/MenuController`、`mapper/SysMenuMapper` |

## 三、常见坑（改前先看）

1. **字段映射**：`meeting_room.enabled ↔ status`、`meeting_booking.status ↔ resStatus`、`schedule_booking.status ↔ resStatus`；自定义 `@Select` 必须显式 `@Results`，否则 NPE。
2. **锁与事务**：`ScheduleManager` 不加 `@Transactional`；不要在 Controller 里开事务；解锁必须在 `finally` 且判断 `isHeldByCurrentThread()`。
3. **锁 key 一致**：Controller 外层 key 与插件 `buildLockKey` 必须相同才能可重入（提交 `schedule:room:{instanceId}`，审批 `schedule:approve:{bookingId}`）。
4. **插件路由**：`supports(typeCode)` 未命中时依赖 `resource_type.plugin_code`；新增类型必须保证字典 `plugin_code` 与插件 `pluginCode()` 完全一致。
5. **分页**：预约分页 `pageSize` 上限 100（`buildPage` 中 `Math.min`）；`/api/room/page` 无上限。前端取全量需按 100 循环。
6. **时间语义**：预约筛选是「整段落在范围内」（`start_time >= ? AND end_time <= ?`），日历查询需前后扩边界。
7. **幂等**：写请求 `X-Request-Id` 60s 内不可复用；手工联调重复调同一接口会返回 400「重复提交」。
8. **菜单可见性**：前端以路由 `meta.role` 过滤菜单；后端 `sys_role_menu` 错配时不会直接暴露入口，但权限码仍需正确。
9. **MQ 与条件装配**：`rocketmq.name-server`、`booking.outbox.enabled`、`booking.expire.enabled` 等均为启动期条件；修改后重启生效。
10. **ElInput 只读值**：Element Plus 2.x 用 `:model-value`，`:value` 会被 `setNativeInputValue()` 覆盖为空。
11. **前端菜单缓存**：菜单存 `localStorage.menus`，角色/菜单数据变更后需重新登录或刷新。

## 四、改后自检

```bash
# 后端：单测 + 打包（JDK 21）
cd admin_system_backend
mvn -q test
mvn clean package -DskipTests

# 前端：静态构建
cd meeting-vue
npm run build
```

冒烟最小集：

1. `admin / 123456` 登录，侧边栏无员工菜单，管理员页面可进。
2. 员工提交预约（带唯一 `X-Request-Id`）→ 管理员审批 → 通过后状态为 1。
3. 同实例同时段再提交 → 400 冲突；同一 `X-Request-Id` 重放 → 400 重复提交。
4. 通用入口：`POST /api/schedule/book`（`resourceTypeCode=WORKSTATION`）→ 成功；类型与实例错配 → 400。
5. 过期记录：`status=0 AND start_time < NOW()` 在 5 分钟内被置为取消，且冲突 SQL 不再计入。
6. 数据库核对：`schedule_booking.resource_type_id`、`plugin_code`、`schedule_booking_item.item_type=SLOT`；`sys_outbox` 记录与业务同事务产生。

改动范围检查：

```bash
git status --short
git diff -- admin_system_backend/src/main/java/com/example/adminsystem/interceptor/TokenInterceptor.java
git diff -- admin_system_backend/src/main/java/com/example/adminsystem/util/Result.java
git diff -- admin_system_backend/src/main/java/com/example/adminsystem/util/BusinessException.java
```

最后一个命令应无输出（红线文件零改动）。

## 五、文档任务

写 README / 架构说明时遵循 `AGENTS.md`：只输出 Markdown，不改 Java/Vue、接口、枚举、yml、SQL；公共内容放根 README，子文档只写专属内容。
