# 会议室预约中台（Reservation Middle Platform）

> 前后端分离的企业级资源预约系统 · 简历项目  
> 前端工程：`meeting-vue`（下文称 frontend） · 后端工程：`admin_system_backend`（下文称 backend）

## 一、项目介绍

面向企业内部共享资源（会议室、工位、算力池等）的预约中台。系统把「资源」抽象为 **资源类型 + 资源实例 + 调度插件**，会议室只是时段独占插件（`TIME_EXCLUSIVE`）的一个实现；新增同规则资源只需写字典与实例数据，无需改动业务代码。

整体链路：

```
Vue3 + Element Plus
        │  /api（开发环境 Vite 代理）
        ▼
Spring Boot 3.3.4
  Controller（Redisson 持锁）
    → ScheduleManager（锁编排，无事务）
      → ScheduleTxService（@Transactional）
        → ScheduleRulePlugin（校验 / 落库）
          → MySQL（FOR UPDATE 兜底）
        → 事务提交 → 释放锁
  ├─ Redis + Redisson：两级分布式锁、幂等
  ├─ MySQL：业务数据 + 本地消息表
  ├─ RocketMQ（可选）：预约通知，Outbox 定时补发
  └─ Nacos（可选）：服务注册发现 + 配置中心
```

项目覆盖的工程能力：分布式锁、锁与事务边界、本地消息表最终一致性、定时任务补偿、Nacos 可选降级、幂等提交、链路 TraceId、RBAC 细粒度权限、逻辑删除与索引优化。

## 二、功能特性

### 员工端

- 登录 / 注册，JWT 鉴权，路由守卫与按钮级权限。
- 工作台：预约统计、状态占比图。
- 预约日历：月 / 日视图、状态色块、预约详情。
- 会议室列表：按工作日与时间规则提交预约，前端限流 + 后端兜底校验。
- 我的预约：多条件筛选、详情、取消（待审批 / 已通过且未开始）。

### 管理员端

- 会议室管理：表格 / 卡片双视图、启用禁用、批量启停、删除前预校验。
- 预约审批：条件筛选、单条 / 批量审批、驳回原因必填。
- 用户管理：分页、改角色、启禁用、重置密码。
- 操作日志 / 登录日志查询。
- 动态菜单：菜单树与权限码由后端按角色下发。

### 平台能力

- **资源调度内核**：`ScheduleRulePlugin` + `PluginRegistry`（`supports` 未命中时按 `resource_type.plugin_code` 二级路由）。
- **通用调度入口**：`POST /api/schedule/book`、`DELETE /api/schedule/cancel/{id}`，按资源类型编码路由插件。
- **两级锁粒度**：提交按资源实例加锁，审批按预约单加锁；锁在外、事务在内。
- **可靠消息**：本地消息表（Outbox Pattern）+ 定时任务补发，失败指数退避，超限进死信。
- **过期释放**：冲突校验对过期待审批懒失效 + 定时任务把过期记录置为取消。
- **幂等提交**：写请求携带 `X-Request-Id`，Redisson SETNX + TTL 去重。
- **全链路 TraceId**：入站继承或生成 `X-Trace-Id`，日志可按链路串联。
- **软删除与索引**：`@TableLogic` 逻辑删除，预约高频查询建组合索引。

## 三、整体技术栈

### 后端

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 21 | 运行时 |
| Spring Boot | 3.3.4 | 基础框架（parent） |
| Spring Cloud | 2023.0.3 | 微服务 BOM |
| Spring Cloud Alibaba | 2023.0.3.2 | Nacos 注册发现 / 配置中心 |
| MyBatis-Plus | 3.5.5 | ORM、分页、逻辑删除 |
| MySQL | 5.7+ | 业务库、行锁 |
| Redis + Redisson | Redis 5.x / Redisson 3.37.0 | 分布式锁、幂等 |
| RocketMQ | 2.3.1（可选） | 预约通知 |
| JJWT | 0.12.6 | Token 签发与校验 |
| BCrypt | Spring Security Crypto 6.3.4 | 密码加密 |
| springdoc-openapi | 2.6.0 | Swagger / OpenAPI 文档 |
| Hutool | 5.8.34 | 通用工具 |

### 前端

| 类别 | 选型 |
| --- | --- |
| 框架 | Vue 3（`<script setup>`） |
| 构建 | Vite 8 |
| UI | Element Plus 2.14（中文 locale） |
| 路由 | Vue Router 4（history 模式 + 全局守卫） |
| 请求 | Axios（统一封装，`baseURL=/api`） |
| 图表 | ECharts 6（按需引入，仅工作台） |
| 图标 | @element-plus/icons-vue |

## 四、快速启动

### 1. 环境准备

| 组件 | 版本要求 | 默认地址 | 是否必须 |
| --- | --- | --- | --- |
| JDK | 21 | — | 必须 |
| Maven | 3.8+ | — | 必须 |
| MySQL | 5.7+ | `127.0.0.1:3306` | 必须 |
| Redis | 5.x | `127.0.0.1:6379` | 必须（需先于后端启动） |
| Node.js | Vite 8 要求 20.19+ 或 22.12+ | — | 必须（前端） |
| Nacos | 2.x | `127.0.0.1:8848` | 可选 |
| RocketMQ | 5.x | `127.0.0.1:9876` | 可选 |

### 2. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS admin_system DEFAULT CHARSET utf8mb4;"
mysql -uroot -p admin_system < docs/schema.sql      # 16 张表 + 基础预置数据
mysql -uroot -p admin_system < docs/seed_v2.sql     # 可选：工位样板数据
```

老库升级与数据迁移：

| 脚本 | 用途 |
| --- | --- |
| `docs/schema.sql` | 全量建表与预置数据（新库 / 重建） |
| `docs/migrate_v2.sql` | 老表数据迁移到资源内核（可重复执行） |
| `docs/mainline2_index.sql` | 补充索引 |
| `docs/seed.sql` | 重置基础业务数据 |
| `docs/seed_v2.sql` | 新增工位资源样板 |

### 3. 启动后端

```bash
cd admin_system_backend
mvn clean package -DskipTests
java -jar target/adminsystem-0.0.1-SNAPSHOT.jar
# 默认 http://localhost:8080
```

多实例（需 Nacos）时使用不同端口启动同一 jar 即可，服务名相同自动组成集群。

### 4. 启动前端

```bash
cd meeting-vue
npm install
npm run dev        # http://localhost:5173
npm run build      # 产物 dist/
```

开发环境由 Vite 把 `/api` 代理到 `http://localhost:8080`，无需后端配置 CORS。

### 5. 默认账号与端口

| 项目 | 值 |
| --- | --- |
| 管理员 | `admin / 123456` |
| 员工 | `user1 / 123456`、`user2 / 123456` |
| 后端 | `8080` |
| 前端 | `5173` |
| 接口前缀 | `/api` |

### 6. 可选组件说明

- **不启动 Nacos**：`spring.config.import` 使用 `optional:` 前缀，配置中心缺失时使用本地默认值；服务注册失败只记录日志，不阻断启动。
- **不启动 RocketMQ**：不配置 `rocketmq.name-server`，消息发送组件不加载；预约主流程正常，Outbox 记录保持待发送，配置并重启后自动补发。

## 五、项目亮点

### 1. 两级分布式锁 + 锁包事务

- **提交 / 通用预约**：`schedule:room:{instanceId}`，资源实例粒度，同一实例的所有提交串行化，避免「实例 + 时段」组合锁漏判。
- **审批**：`schedule:approve:{bookingId}`，预约单粒度，不同预约可并行，提升审批吞吐。
- **顺序固定**：Controller `tryLock` → `ScheduleManager`（无事务）→ `ScheduleTxService`（`@Transactional`）→ 事务提交 → `finally` 解锁，避免并发请求读到未提交数据。
- **兜底**：`resource_instance` / `schedule_booking` 的 `FOR UPDATE` 行锁，在 Redis 故障时仍保证同一实例串行。
- **细节**：`leaseTime=-1` 启用看门狗自动续期；解锁前判断 `isHeldByCurrentThread()`；取消只有状态变更语义，仅用行锁。

### 2. 本地消息表（Outbox Pattern）

- 业务事务内写 `sys_outbox`，与业务数据同生共死，避免「DB 回滚但消息已发出」。
- 定时任务每 5s 扫描待发送记录，异步发送 MQ；成功置已发送，失败指数退避重试，超过上限标记死信。
- `msg_id` 为 UUID，消费端可去重；MQ 未部署时任务直接返回，消息保持待发送且不累加重试次数。

### 3. 定时任务补偿

- **Outbox 转发**：每 5s 轮询本地消息表，补齐 MQ 不可用期间的通知。
- **过期预约释放**：每 5 分钟把「待审批且已过开始时间」的记录置为取消；冲突校验 SQL 同时对过期记录懒失效。
- 两者均提供配置开关，可独立关闭。

### 4. Nacos 可选与降级

- 配置中心使用 `optional:nacos:admin-system-backend.yaml`，Nacos 不可达时以本地配置启动，适合单机演示与简历环境。
- 服务注册发现默认 `fail-fast=false`，注册失败只告警不阻断；多实例部署时相同服务名自动形成集群。
- JWT 配置支持 `@RefreshScope` 热刷新；RocketMQ 等启动期条件装配的配置修改后需重启。

### 5. 其他工程化能力

幂等拦截（`X-Request-Id` + Redisson SETNX）、MDC 全链路 `X-Trace-Id`、RBAC 细粒度权限（`@RequiresPermission` + AOP）、批量审批 / 批量启停、逻辑删除与组合索引、统一异常收敛。

## 六、工程结构

```
admin_system/
├─ README.md                      # 根文档：介绍、功能、技术栈、启动、亮点（公共内容）
├─ admin_system_backend/          # 后端工程
│  └─ README.md                   # 后端专属：Nacos、数据库、表结构、Knife4j、锁、MQ、定时任务
├─ meeting-vue/                   # 前端工程（frontend）
│  └─ README.md                   # 前端专属：Node、启动打包、代理、环境变量
├─ docs/                          # SQL 脚本（建表 / 迁移 / 索引 / 种子数据）
└─ .trae/
   ├─ rules/AGENTS.md             # 文档固定规则（永久生效）
   └─ documents/                  # ROADMAP / STATE / CHECKLIST 迭代记录
```

## 七、文档导航

| 文档 | 内容 |
| --- | --- |
| [backend/README.md](admin_system_backend/README.md) | Nacos、MySQL 与表结构、Knife4j、分布式锁实现、MQ / Outbox、定时任务 |
| [frontend/README.md](meeting-vue/README.md) | Node 版本、启动与打包、开发代理、环境变量 |
| [AGENTS.md](.trae/rules/AGENTS.md) | 文档编写固定规则（只写 md、不改代码） |
