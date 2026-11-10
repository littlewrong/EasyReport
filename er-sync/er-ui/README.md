# ERSync · Vben 前端

基于仓库 `er-bi/ui` 的 Vben Admin 5.7.0，采用 Vue 3、TypeScript、Vite、Element Plus 和 Pinia。将 `er-sync/er-ui` 的同步业务及后台页面迁移到同一套框架，直接连接现有 `er-sync/er-api`。

## 本地启动

使用 Node.js 24 和 pnpm 11，先启动原同步后端（默认 `http://localhost:8080`），然后执行：

```powershell
cd D:\EasyReport_Dev\er-sync\er-newui
pnpm install
pnpm dev
```

Windows 已安装多个 Node 版本时，也可执行 `./start-dev.ps1`。它会在当前终端的可用 Node、nvm 安装目录和本机 Codex 运行时中选择兼容版本，临时设置当前进程的 PATH 并启动，不修改系统默认 Node；首次启动会安装依赖。`./start-dev.ps1 -Check` 仅检查运行环境。

访问 <http://localhost:15780/ersyncui/>，使用原系统账号。开发接口 `/ersyncapi` 由 Vite 代理到后端。自定义后端地址时，在 `apps/web-ele/.env.development.local` 配置：

```dotenv
VITE_API_TARGET=http://localhost:8080
```

端口配置在 `apps/web-ele/.env` 的 `VITE_PORT`。新应用缓存命名空间与 BI、旧同步前端隔离。

## 页面和接口

- 数据源：查询、新增、编辑、复制、连接测试、JDBC URL 生成。
- 数据同步、数据传输、合并同步：任务配置、预览、执行、停止、进度、日志、SQL 和表级同步水位。
- 首页：现有 dashboard 接口的统计、趋势、数据源分布和最近记录。
- 系统管理：用户、角色、菜单、部门、岗位、字典、参数、公告及个人中心。
- 监控和工具：在线用户、操作/登录日志、定时任务、服务器、缓存、接口文档、代码生成、表单构建。

`apps/web-ele/src/api/core` 适配 `/login`、`/captchaImage`、`/getInfo`、`/logout` 和 `/getRouters`。接口保留 RuoYi 的 `{ code: 200, data }` 或 `{ code: 200, rows, total }` 格式，登录读取顶层 `token`；不依赖 BI 的认证接口。

动态菜单直接使用现有后端菜单表，保留旧 URL、组件路径和按钮权限。`Layout`、`ParentView`、`InnerLink`、隐藏详情路由和菜单图标由 `menu-adapter.ts` 转换。旧业务 API、工具和共享组件的 Vue 3 适配集中在 `src/legacy`，业务页面位于 `src/views`。

业务和系统管理列表统一使用 `src/components/bi-table-page.vue`：`search` 插槽放查询条件，`toolbar` 放表格操作，默认插槽放主表格和抽屉，`pagination` 放分页，`sidebar` 可放部门树。查询卡片与表格卡片上下分离，共用 BI 的页面间距和主题；编辑表单使用标签置顶的右侧抽屉。

## 构建和检查

```powershell
pnpm check:type
pnpm test:unit
pnpm build:prod
pnpm build:stage
```

产物在 `apps/web-ele/dist`。生产环境默认访问路径 `/ersyncui/`、接口前缀 `/ersyncapi`；预发布接口前缀 `/stage-api`。部署时沿用旧前端的静态目录与 API 反向代理即可。生产接口地址也可在产物 `_app-config-*.js` 中修改。

启动开发服务后，可另开终端执行 `pnpm test:smoke`。该检查通过接口契约夹具验证登录、动态菜单、页面和四类新增弹窗，不连接数据库，也不执行真实同步任务。默认使用本机 Edge/Chrome；其他环境可安装 Playwright Chromium，或通过 `SMOKE_BROWSER` 指定浏览器路径。截图输出到忽略提交的 `test-results` 目录。

真实后端未启动时，只能完成构建、类型和契约界面验证；真实数据库连接、权限配置与同步执行仍需配合后端联调。
