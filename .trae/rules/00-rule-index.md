# 00 · Trae 规则索引与优先级

本目录（`.trae/rules`）的 Markdown 会被工作区自动加载，作为编程体的长期上下文。任何改代码 / 写文档任务开始前，先按本索引确认适用规则。

## 一、规则文件地图

| 文件 | 作用 | 适用任务 |
| --- | --- | --- |
| `00-rule-index.md` | 规则索引、优先级、阅读顺序 | 所有任务 |
| `AGENTS.md` | 文档任务固定规则：三份 README、只写 md、不改代码 | 写 README / 文档 |
| `builder-master.md` | 增量迭代主控：阶段流转、方案先行、红线清单 | 功能迭代 / 重构 |
| `10-project-architecture.md` | 项目架构、调度内核、关键机制、编码约定 | 所有代码改动 |
| `20-code-change-map.md` | 任务 → 文件导航、常见坑、自检命令 | 定位改动点 |

## 二、阅读顺序

1. 先读本文件，确认任务类型（文档 / 代码 / 排查）。
2. **代码任务**：`10-project-architecture.md` → `20-code-change-map.md` → `builder-master.md`（按阶段流程输出方案并等待确认）。
3. **文档任务**：`AGENTS.md`，只输出 Markdown，不碰代码、配置、SQL。
4. 涉及接口、表结构、配置时，以代码与 `docs/schema.sql`、`application.yml` 实际内容为准，规则文档不作为唯一事实源。

## 三、优先级（冲突时从上到下）

1. 用户当前指令中的明确约束。
2. `builder-master.md` 的红线与阶段流程（禁止未确认先改代码）。
3. `AGENTS.md` 的文档任务限制（只写 md）。
4. `10-project-architecture.md` 的架构与编码约定。
5. `20-code-change-map.md` 的定位建议。
6. 历史文档（`.trae/documents`、README）仅作参考。

## 四、绝对红线（所有任务生效）

禁止修改以下稳定模块，除非用户明确要求并说明原因：

| 模块 | 路径 |
| --- | --- |
| Token 拦截器 | `admin_system_backend/.../interceptor/TokenInterceptor.java` |
| 统一返回体 | `admin_system_backend/.../util/Result.java` |
| 业务异常 | `admin_system_backend/.../util/BusinessException.java` |
| 全局异常处理既有分支 | `admin_system_backend/.../config/GlobalExceptionHandler.java` |

另：`MeetingBookingController` / `ScheduleManager` / `ScheduleTxService` 的「锁在外、事务在内」骨架不得颠倒。

## 五、任务开始前的三问

1. 这次改动属于哪个阶段？是否已有用户确认的方案？
2. 改动是否落在红线文件或跨层强耦合点？
3. 是否能用「新增文件 / 新增实现」替代「修改内核」？

答不上来时，先只读代码、输出方案，不写代码。
