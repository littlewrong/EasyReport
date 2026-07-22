# EasyReport

## 简介

**EasyReport** 是一套企业级数据报表与数据集成中间件集合，覆盖数据同步、报表设计和 BI 大屏三类场景。项目既可以作为独立产品部署，也可以按模块嵌入企业内部系统。

官网地址：[www.easyreport.cn](https://www.easyreport.cn)

试用账号：admin admin123，请勿修改密码，会定期重置

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

## 发布包结构

```text
EasyReport-release/
├── er-sync/
│   ├── api/                    # er-admin.jar、配置文件和数据库脚本
│   └── dist/                   # 已构建的管理端静态文件
├── er-report/
│   └── easyreport-web/
│       ├── target/             # 可直接运行的报表服务 JAR
│       ├── lib/                # EasyReport 运行库
│       └── src/main/resources/ # 配置示例
├── er-bi/
│   ├── api/
│   │   ├── windows-x64/        # Windows x64 可执行文件
│   │   └── linux-amd64/        # Linux AMD64 可执行文件
│   ├── ui/                     # BI 管理端静态文件
│   ├── bi_designer/            # 大屏设计器静态文件
│   └── sql/easyreport_bi.sql
└── nginx/nginx.conf            # 整套产品同域部署参考配置
```

## 运行要求

| 模块 | 运行要求 |
|------|----------|
| ER-Sync | JDK 1.8+、MySQL 8.0+、Redis、Nginx |
| ER-Report | JDK 1.8+ |
| ER-BI | Windows x64 或 Linux AMD64、MySQL 5.7+ / 8.0+、Nginx |

## 快速开始

获取 EasyReport 发布包并解压。下面是最短启动路径，完整配置与生产部署要求请查看官网文档。

### ER-Sync 数据同步

1. 创建 MySQL 8.0 系统库 `easyreport`，并将 `er-sync/api/easyreport.sql` 导入该数据库。
2. 修改 `er-sync/api/application-druid.yml` 中的数据库配置，以及 `application.yml` 中的 Redis、文件目录和令牌密钥。
3. 从 `api` 目录启动后端：

```bash
cd er-sync/api
java -jar er-admin.jar
```

4. 使用 Nginx 托管 `er-sync/dist`，并把 `/prod-api/` 转发到 `http://127.0.0.1:8080/`。

详细步骤：[数据同步快速开始](https://www.easyreport.cn/doc.html#datasync-quickstart)

### ER-Report 报表设计

创建报表模板和上传资源目录后启动发布包中的 JAR：

```bash
cd er-report/easyreport-web
java -Deasyreport.fileStoreDir=/data/easyreport/templates \
  -jar target/easyreport-web-2.0.6.jar \
  --tw.profile=/data/easyreport/uploads/ \
  --server.port=8083
```

设计器地址：`http://localhost:8083/easyreport/designer`

详细步骤：[报表设计快速开始](https://www.easyreport.cn/doc.html#report-quickstart)

### ER-BI BI 大屏

1. 创建系统库 `easyreport_bi`，并将 `er-bi/sql/easyreport_bi.sql` 导入该数据库。
2. 选择 `windows-x64` 或 `linux-amd64`，分别为 `er-bi-api` 和 `er-bi-datasource-api` 创建 `config.ini`，替换所有 `CHANGE_ME`。
3. 启动主 API（`5320`）和数据源 API（`5321`）。
4. 使用 Nginx 托管 `er-bi/ui` 和 `er-bi/bi_designer`，并按发布包示例配置 `/biapi/`、`/dsapi/http/`、`/dsapi/sql/` 与 `/dsapi/python/` 反向代理。
5. 访问 `/biui/#/auth/login`，使用 `admin / 123456` 首次登录并立即修改密码。

详细步骤：[BI 大屏快速开始](https://www.easyreport.cn/doc.html#bi-quickstart)

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
