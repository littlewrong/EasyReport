# 项目介绍

## 简介

**EasyReport** 是一套企业级数据报表与数据集成中间件集合，集成了跨数据库数据同步、可视化报表设计和 BI 大屏三大核心能力。

官网地址：[www.easyreport.cn](http://www.easyreport.cn)

试用账号：admin admin123，请勿修改密码，会定期重置

GitHub 地址：[https://github.com/littlewrong/easyreport](https://github.com/littlewrong/easyreport)


发布包下载：[EasyReport 最新发布版](https://github.com/littlewrong/EasyReport/releases/download/v1.0.0/easyreport_v1.0.0.zip)

## 核心功能

### 报表设计

- **可视化报表设计器** - 基于 Web 的报表设计器，无需安装客户端，支持 Chrome、Firefox、Edge 等主流浏览器
- **中国式报表** - 通过单元格迭代方式，轻松实现复杂的中国式报表设计（交叉表、分组汇总、多级表头等）
- **多格式导出** - 支持 HTML 在线预览、Excel (.xlsx)、PDF 三种导出格式
- **多种数据源** - 支持直接数据库连接、Spring Bean、HTTP API 等多种数据源类型
- **HTTP API 数据源** - 无需中间库表，直接从 REST API 获取 JSON 数据填充报表，支持参数模板变量和嵌套路径提取，适用于微服务架构
- **表达式引擎** - 内置丰富的表达式和函数，支持条件属性、动态列、序号绑定等高级特性

### 数据同步

- **跨数据库同步** - 支持 MySQL、PostgreSQL、SQL Server、Oracle、TiDB、StarRocks 之间的数据同步
- **数据传输** - 支持全量初始化和增量同步两种模式，基于时间戳字段实现全量按天分片和增量数据捕获
- **合并同步** - 面向 StarRocks OLAP 数仓，将分库分表数据汇入统一目标库表，并保留来源标识
- **表结构同步** - 跨数据库 Schema 迁移，支持模糊匹配和精确匹配两种表选择方式
- **UPSERT 机制** - 针对不同数据库实现原生 UPSERT 语法，保证数据一致性
- **定时调度** - 基于 Quartz 的 Cron 表达式调度，实现自动定时同步

### BI 大屏

- **大屏项目管理** - 支持大屏新建、编辑、删除、发布/取消发布和封面维护
- **可视化大屏设计器** - 基于 GoView 的拖拽式设计器，支持组件、图层、画布、预览和发布
- **多类型数据源** - 支持 DB 连接器、HTTP 数据源、SQL 数据源、Python 数据源
- **版本快照** - HTTP / SQL / Python 数据源保存时自动生成版本，可按需恢复
- **统一权限体系** - 基于 FastAPI + JWT + RBAC，和 Vben 管理端动态菜单对接

### 系统管理

- 用户与角色权限管理
- 部门与组织架构管理
- 菜单与按钮级权限控制
- 系统监控（服务器状态、缓存、连接池）
- 操作日志与登录日志审计
- 定时任务管理
- Swagger API 文档

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 2.5、Spring Security、MyBatis |
| 报表引擎 | 基于 UReport2 深度定制，新增 HTTP 数据源 |
| 前端（报表设计器） | Webpack + Handsontable + CodeMirror + Chart.js + Bootstrap |
| 前端（管理端） | Vue.js / Vue 3 / Vben Admin |
| BI 大屏 | FastAPI + SQLAlchemy + GoView + Vben Admin + ECharts / VChart |
| 数据库 | MySQL 8.0（系统库），支持 PostgreSQL / Oracle / SQL Server 等 |
| 缓存 | Redis |
| 认证 | JWT |
| 任务调度 | Quartz |
| 连接池 | Druid |

## 发布包结构

```text
EasyReport-release/
├── er-sync/
│   ├── api/                    # JAR、配置文件和数据库脚本
│   └── dist/                   # 数据同步管理端静态文件
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
│   └── sql/                    # BI 数据库脚本
└── nginx/
    └── nginx.conf              # 整套产品同域部署参考配置
```

## 运行环境

| 模块 | 运行要求 |
|------|----------|
| ER-Sync | JDK 1.8+、MySQL 8.0+、Redis、Nginx |
| ER-Report | JDK 1.8+ |
| ER-BI | Windows x64 或 Linux AMD64、MySQL 5.7+ / 8.0+、Nginx |

## 快速开始

1. 获取 EasyReport 发布包并解压。
2. 根据需要部署一个或多个模块：
   - [ER-Sync 数据同步快速开始](doc.html#datasync-quickstart)
   - [ER-Report 报表设计快速开始](doc.html#report-quickstart)
   - [ER-BI BI 大屏快速开始](doc.html#bi-quickstart)
3. 使用发布包中的 `nginx/nginx.conf` 作为整套产品同域部署参考，并按实际目录、域名和证书位置修改。
4. 首次上线前修改所有初始密码、数据库密码、JWT 密钥和内部服务令牌，启用 HTTPS，并备份数据库与持久化目录。

三个模块可以独立部署。仅使用某个模块时，不需要启动其他模块。

## 默认服务地址

| 服务 | 默认地址 |
|------|----------|
| 数据同步 API | `http://127.0.0.1:8080` |
| 报表设计器 | `http://127.0.0.1:8083/easyreport/designer` |
| BI 主 API 健康检查 | `http://127.0.0.1:5320/api/status` |
| BI 数据源 API 健康检查 | `http://127.0.0.1:5321/api/status` |
| BI 管理端（经 Nginx） | `http://服务器地址/biui/` |
| BI 大屏设计器（经 Nginx） | `http://服务器地址/bidesigner/` |

## 数据源支持

| 数据库 / 来源 | 数据传输 | 表结构同步 | 报表数据源 | BI 数据源 |
|---------------|---------|-----------|-----------|-----------|
| MySQL | ✅ | ✅ | ✅ | ✅ |
| PostgreSQL | ✅ | ✅ | ✅ | ✅ |
| SQL Server | ✅ | ✅ | ✅ | ✅ |
| Oracle | ✅ | ✅ | ✅ | ✅ |
| TiDB | ✅ | ✅ | ✅ | ✅ |
| StarRocks | ✅ | ✅ | ✅ | ✅ |
| HTTP API | - | - | ✅ | ✅ |
| Python 脚本 | - | - | - | ✅ |

## 开源协议

EasyReport 自有代码采用 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可。您可以在遵守协议的前提下将其用于个人或商业用途，并可以使用、修改和分发；重新分发时须保留版权与许可证声明，并对修改过的文件作出明确说明。Apache License 2.0 不授予 EasyReport 名称、Logo 等商标的使用权。

本项目包含按各自许可证提供的第三方组件，包括 UReport2（Apache-2.0）、RuoYi、GoView、Vben Admin（MIT）以及 iText 5.5.13（AGPLv3 / 商业双许可证）。Apache License 2.0 仅适用于 EasyReport 自有代码，第三方组件继续适用其原许可证；使用和分发本项目时，须同时遵守相应的第三方许可条款。
