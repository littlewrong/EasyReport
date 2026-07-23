# EasyReport

## 简介

**EasyReport** 是一套企业级数据报表与数据集成中间件集合，覆盖数据同步、报表设计和 BI 大屏三类场景。项目既可以作为独立产品部署，也可以按模块嵌入企业内部系统。

官网地址：[www.easyreport.cn](http://www.easyreport.cn)

项目地址：[https://github.com/littlewrong/easyreport](https://github.com/littlewrong/easyreport)

## 核心能力

### ER-Sync 数据同步

- **跨数据库同步**：支持 MySQL、PostgreSQL、SQL Server、Oracle、TiDB、StarRocks 之间的数据同步。
- **数据传输**：支持全量初始化、按天分片传输和基于时间戳字段的增量同步。
- **表结构同步**：支持跨数据库 Schema 迁移，提供模糊匹配和精确匹配两种表选择方式。
- **UPSERT 机制**：针对不同数据库实现原生 UPSERT / MERGE 语法，提升同步一致性。
- **定时调度与监控**：基于 Quartz 配置 Cron 任务，并提供同步趋势、执行日志和状态看板。

### ER-Report 报表设计

- **可视化报表设计器**：基于 Web 的报表设计器，支持 Chrome、Firefox、Edge 等主流浏览器。
- **中国式复杂报表**：通过单元格迭代方式实现交叉表、分组汇总、多级表头、动态列等场景。
- **多格式导出**：支持 HTML 在线预览、Excel、PDF 导出和打印。
- **多种数据源**：支持 JDBC、Spring Bean、HTTP API 等数据源类型。
- **HTTP API 数据源**：直接从 REST API 获取 JSON 数据填充报表，支持参数模板变量和嵌套路径提取。
- **表达式与函数引擎**：内置表达式、函数、条件属性、动态列序号绑定等高级能力。

### ER-BI BI 大屏

- **大屏项目管理**：提供大屏新建、编辑、删除、发布/取消发布和封面管理。
- **GoView 大屏设计器**：支持拖拽式画布、组件库、图层管理、预览、发布、导入导出和动画配置。
- **数据源管理**：提供 DB 连接器、HTTP 数据源、SQL 数据源、Python 数据源管理。
- **数据源测试与版本快照**：支持保存前测试、历史版本查看和版本恢复。
- **公开访问令牌**：SQL、HTTP、Python 数据源可重置访问令牌，用于大屏运行态安全访问。
- **统一权限体系**：基于 FastAPI + JWT + RBAC，为 Vben 管理端和 BI 设计器提供统一登录态。

## 技术栈

| 模块 | 技术 |
|------|------|
| ER-Sync 后端 | Spring Boot 2.5、Spring Security、MyBatis、Quartz、Druid |
| ER-Sync 前端 | Vue.js、Element UI |
| ER-Report | UReport2 深度定制、ANTLR、Handsontable、CodeMirror、Chart.js、Bootstrap |
| ER-BI 后端 | FastAPI、SQLAlchemy、MySQL、JWT、PyMySQL、psycopg、pymssql |
| ER-BI 管理端 | Vue 3、Vben Admin、Element Plus、Pinia、Vite |
| ER-BI 大屏设计器 | GoView、Vue 3、Naive UI、ECharts、VChart、Three.js、Monaco Editor |
| 官网文档 | 静态 HTML、Markdown、marked.js |

## 项目结构

```text
EasyReport
├── er-sync/                   # 数据同步管理平台
│   ├── er-api/                # 数据同步后端（基于 RuoYi，默认端口 8080）
│   └── er-ui/                 # 数据同步前端
├── er-report/                 # 报表设计中间件
│   ├── easyreport-core/       # 报表核心（解析、计算、导出）
│   ├── easyreport-console/    # 报表 Web 控制台与 REST API
│   ├── easyreport-js/         # 报表设计器前端
│   ├── easyreport-font/       # 字体渲染支持
│   └── easyreport-web/        # 报表设计器独立启动模块（默认端口 8083）
├── er-bi/                     # BI 大屏项目
│   ├── api/                   # FastAPI 后端（默认端口 5320）
│   ├── datasource_api/        # BI 数据源 API（默认端口 5321）
│   ├── ui/                    # Vben 管理端（默认端口 5777）
│   └── bi_designer/           # GoView 大屏设计器（默认端口 3020）
├── website/                   # 官网与在线文档
└── doc/                       # 项目资料与扩展说明
```

## 环境要求

| 模块 | 要求 |
|------|------|
| ER-Sync | JDK 1.8+、Maven 3.6+、MySQL 5.7+ / 8.0+、Redis、Node.js 12+ |
| ER-Report | JDK 1.8+、Maven 3.6+ |
| ER-BI API | Python 3.10+、MySQL 5.7+ / 8.0+ |
| ER-BI 管理端 | Node.js 22.18+ 或 24+、pnpm 11+ |
| ER-BI 大屏设计器 | Node.js 16.14+、pnpm |

## 快速开始

### 启动 ER-Sync 数据同步

1. 导入数据库脚本：

```bash
er-sync/er-api/sql/easyreport.sql
```

2. 修改数据库和 Redis 配置：

```text
er-sync/er-api/er-admin/src/main/resources/application-druid.yml
```

3. 启动后端：

```bash
cd er-sync/er-api
mvn clean package -DskipTests
java -jar er-admin/target/er-admin.jar
```

4. 启动前端：

```bash
cd er-sync/er-ui
npm install
npm run dev
```

管理端默认访问：`http://localhost:80`

### 启动 ER-Report 报表设计器

```bash
cd er-report
mvn clean package -DskipTests
java -jar easyreport-web/target/easyreport-web-*.jar
```

报表设计器默认访问：`http://localhost:8083/easyreport/designer`

### 启动 ER-BI BI 大屏

1. 启动主 API：

```bash
cd er-bi/api
pip install -r requirements.txt
python -m scripts.init_db
python run.py
```

API 默认访问：

```text
API 状态: http://localhost:5320/api/status
Swagger:  http://localhost:5320/api/docs
```

默认账号：

```text
admin / 123456
jack  / 123456
```

如果是在已有数据库上升级 BI 菜单和数据源表结构，可执行：

```bash
cd er-bi/api
python -m scripts.add_bi_designer_menus
```

2. 启动数据源 API：

```bash
cd er-bi/datasource_api
pip install -r requirements.txt
python run.py
```

数据源 API 默认访问：

```text
API 状态: http://localhost:5321/api/status
Swagger:  http://localhost:5321/api/docs
```

3. 启动 Vben 管理端：

```bash
cd er-bi/ui
pnpm install
pnpm dev
```

管理端默认访问：`http://localhost:5777`

4. 启动 GoView 大屏设计器：

```bash
cd er-bi/bi_designer
pnpm install
pnpm dev
```

设计器默认访问：`http://localhost:3020`

推荐使用流程：

```text
登录 BI 管理端 -> 配置 DB/HTTP/SQL/Python 数据源 -> 新建大屏 -> 打开设计器 -> 绑定数据 -> 预览并发布
```

## 数据源支持

| 数据库 / 来源 | 数据同步 | 表结构同步 | 报表数据源 | BI 数据源 |
|---------------|----------|------------|------------|-----------|
| MySQL | 支持 | 支持 | 支持 | 支持 |
| PostgreSQL | 支持 | 支持 | 支持 | 支持 |
| SQL Server | 支持 | 支持 | 支持 | 支持 |
| Oracle | 支持 | 支持 | 支持 | 支持 |
| TiDB | 支持 | 支持 | 支持 | 支持 |
| StarRocks | 支持 | 支持 | 支持 | 支持 |
| HTTP API | - | - | 支持 | 支持 |
| SQL 查询 | - | - | 支持 | 支持 |
| Python 脚本 | - | - | - | 支持 |

## 文档入口

- 官网首页：`website/index.html`
- 在线文档：`website/doc.html`
- ER-BI API 说明：`er-bi/api/README.md`
- ER-BI 数据源 API 说明：`er-bi/datasource_api/README.md`
- 报表功能说明：`er-report/说明文档/`
- 数据同步说明：`doc/`

## 开源协议

EasyReport 自有代码采用 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可。您可以在遵守协议的前提下将其用于个人或商业用途，并可以使用、修改和分发；重新分发时须保留版权与许可证声明，并对修改过的文件作出明确说明。

本项目包含按各自许可证提供的第三方组件，包括 UReport2（Apache-2.0）、RuoYi、GoView、Vben Admin（MIT）以及 iText 5.5.13（AGPLv3 / 商业双许可证）。Apache License 2.0 仅适用于 EasyReport 自有代码，第三方组件继续适用其原许可证；使用和分发本项目时，须同时遵守相应的第三方许可条款。
