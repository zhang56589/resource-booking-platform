# 会议室预约系统｜Builder 主控运行时状态

## 阶段进度

| 阶段 | 名称 | 优先级 | 状态 | 阻塞原因 | 开始时间 | 完成时间 |
|---|---|---|---|---|---|---|
| 0 | 基线调研 | — | ✅ **已完成** | — | 2026-10-24 | 2026-10-24 |
| P0-1 | 锁粒度拆分优化 | 高 | ⏳ **待开始** | — | — | — |
| P0-2 | 本地消息表 RocketMQ 可靠消息 | 高 | ⏳ **待开始** | 依赖 P0-1 | — | — |
| P0-3 | RBAC 细粒度权限改造 | 高 | ⏳ **待开始** | — | — | — |
| P0-4 | 幂等拦截器 RequestId 防重复提交 | 高 | ⏳ **待开始** | — | — | — |
| P1-1 | 数据库索引 + 软删除 | 中 | ⏳ **待开始** | — | — | — |
| P1-2 | 批量审批、批量启停接口 | 中 | ⏳ **待开始** | — | — | — |
| P1-3 | Nacos @RefreshScope 动态配置 | 中 | ⏳ **待开始** | — | — | — |
| P1-4 | MDC 全链路 TraceId 埋点 | 中 | ⏳ **待开始** | — | — | — |
| P1-5 | 前端动态菜单 + v-permission | 中 | ⏳ **待开始** | 依赖 P0-3 | — | — |
| P2-1 | 收尾 README / 接口文档 / SQL | 低 | ⏳ **待开始** | 依赖全部 P0 + P1 | — | — |

---

## 当前状态摘要

- **当前阶段**：阶段 0（基线调研）**已完成**
- **下一待执行阶段**：P0-1 锁粒度拆分优化（高优）
- **最近一次变更**：README 代码对齐（锁位置 / MQ afterCommit / 目录树 / 部署说明），无代码变更
- **上一阶段摘要**：阶段 0 完整代码审计（编译 ✅ / Spring 启动日志捕获 🔴 Redis 阻断 / 30 项 Bean 装配静态审计 ✅ / Controller 返回统一 ✅ / 锁逻辑 ✅ / 枚举 ✅ / MQ afterCommit ✅）

---

## 基线已知问题清单（本轮已标记，后续阶段规划时融入）

| 编号 | 类型 | 问题 | 严重程度 | 对应后续阶段 |
|---|---|---|---|---|
| B-1 | 启动阻断 | Redisson eager 初始化 + Redis 未启动 → BeanCreationException | 🔴 | 维持现状（先起 Redis） |
| B-2 | 隐患 | MQ afterCommit Producer 异常静默吞掉 → 预约成功但通知永远没发 | 🟡 | P0-2 本地消息表 |
| B-3 | 隐患 | approve 锁粒度为 booking:room:{roomId}（会议室粒度），理论上只需要串行化同一个 bookingId | 🟡 | P0-1 锁粒度拆分 |
| B-4 | 隐患 | submit 锁粒度为 booking:room:{roomId}（会议室粒度），同会议室不同时段并发被串行化 | 🟡 | 维持现状（业务量小，并发不高） |
| B-5 | 缺口 | cancelBooking 无幂等保护（虽然 update 影响 0 行但逻辑上可加） | 🟡 | P0-4 幂等拦截器 |
| B-6 | 缺口 | 数据库无软删除（物理删会议室外键约束导致 CASCADE 风险） | 🟡 | P1-1 索引+软删除 |
| B-7 | 缺口 | 登录日志 sys_login_log / 操作日志 sys_operate_log 缺少 username / operator_name 自动填充（已用 IpUtil + TokenInterceptor，但 SysUserServiceImpl.login 里 saveLoginLog 的 username 是手动传参，无自动注入 username 机制） | 🟢 低 | P2-1 收尾对齐 |
| B-8 | 红线 | TokenInterceptor 稳定模块，禁止修改 | — | — |

---

## 编译自检记录

| 时间 | 命令 | 退出码 | 备注 |
|---|---|---|---|
| 2026-10-24 | `mvn -q compile` | 0 | 零错误零警告 |
| 2026-10-24 | `mvn -q -DskipTests package` | 0 | jar 生成 adminsystem-0.0.1-SNAPSHOT.jar |
| 2026-10-24 | `mvn spring-boot:run` | 1 | Redisson eager 初始化阻断（Redis 未启动），非代码问题 |
