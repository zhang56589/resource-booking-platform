# 会议室预约中台 · 前端（meeting-vue / frontend）

> 本文件只写前端专属内容：Node 版本、启动与打包、开发代理、环境变量。  
> 项目介绍、功能列表、整体技术栈、快速启动与项目亮点见 [根 README](../README.md)。

## 一、Node 版本

| 项 | 要求 |
| --- | --- |
| Node.js | **20.19+ 或 22.12+**（Vite 8 要求），推荐 22 LTS |
| 包管理器 | npm（随 Node 安装），也可使用 pnpm / yarn |
| 依赖锁 | 首次安装后保留 `package-lock.json`，避免依赖漂移 |

检查版本：

```bash
node -v
npm -v
```

> 工程 `package.json` 未声明 `engines` 字段，版本要求来自 Vite 8 的运行时约束；Node 18 无法正常启动 Vite 8。

## 二、启动与打包

```bash
cd meeting-vue

# 1. 安装依赖
npm install

# 2. 开发模式（热更新）
npm run dev          # http://localhost:5173

# 3. 生产构建
npm run build        # 产物输出到 dist/

# 4. 本地预览构建产物
npm run preview
```

| 命令 | 说明 |
| --- | --- |
| `npm run dev` | 启动 Vite 开发服务器，默认端口 `5173` |
| `npm run build` | 构建到 `dist/`，已通过（2265 modules，exit 0） |
| `npm run preview` | 以静态服务预览 `dist/` |

开发前需保证后端已启动在 `http://localhost:8080`，否则登录与所有接口请求会失败。

构建产物部署：

- 将 `dist/` 下的静态文件交给 Nginx / 静态服务器托管。
- 前端路由为 history 模式，需要配置 `try_files $uri $uri/ /index.html;` 回退，避免刷新 404。

## 三、开发代理

前端统一以 `/api` 作为 Axios `baseURL`，开发环境由 Vite 代理到后端，避免跨域：

```js
// vite.config.js
server: {
  port: 5173,
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true
    }
  }
}
```

| 项 | 值 |
| --- | --- |
| 前端地址 | `http://localhost:5173` |
| 代理前缀 | `/api` |
| 代理目标 | `http://localhost:8080` |
| 路径重写 | 无（后端接口本身带 `/api` 前缀） |

生产环境不再经过 Vite，由 Nginx 等反向代理转发 `/api`，保持前端 `baseURL=/api` 不变：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

> 如果后端端口不是 `8080`，开发环境修改 `vite.config.js` 的 `proxy.target`，生产环境修改 Nginx 的 `proxy_pass`。

## 四、环境变量

当前工程**未使用任何自定义环境变量**，也没有 `.env` 文件：

| 配置项 | 当前取值 | 位置 | 修改方式 |
| --- | --- | --- | --- |
| 接口前缀 | `/api` | `src/utils/request.js`（`baseURL`） | 需同步调整后端前缀与代理规则 |
| 开发代理目标 | `http://localhost:8080` | `vite.config.js` | 直接修改 `proxy.target` |
| 开发端口 | `5173` | `vite.config.js` | 修改 `server.port` |
| 请求超时 | `10000ms` | `src/utils/request.js` | 修改 `timeout` |

说明：

- Vite 原生支持 `.env` / `.env.development` / `.env.production` 与 `import.meta.env.VITE_*`，但本项目目前**未接入**，不要把环境变量当作已有能力使用。
- 部署多环境时，推荐在 Nginx 层通过不同 `proxy_pass` 区分后端地址，前端构件无需重新打包。
- 登录态保存在 `localStorage`（`token`、`role`、`userId`、`username`、`menus`、`permissions`），不依赖环境变量。

## 五、常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动报 Node 版本错误 | Node 低于 Vite 8 要求 | 升级到 20.19+ 或 22.12+ |
| 接口 404 / 连接失败 | 后端未启动或端口不一致 | 确认后端 `8080`，核对 `proxy.target` |
| 刷新子路由 404（生产） | history 模式未配置回退 | Nginx 增加 `try_files ... /index.html` |
| 提示「重复提交」 | 写请求 60s 内复用了同一 `X-Request-Id` | 重新发起由前端自动生成新 ID；手工联调需更换唯一值 |
| 登录后侧边栏菜单缺失 | 后端菜单接口失败或角色映射缺失 | 检查 `/api/user/menu` 与 `sys_role_menu`；前端会回退角色静态菜单 |
