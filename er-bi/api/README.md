# 易报表 BI 后端 API

基于 **FastAPI + SQLAlchemy + MySQL + JWT** 的主控制服务，提供系统管理、认证授权、动态菜单、BI 大屏项目和 DB 连接器管理。HTTP、SQL、Python 数据源已迁移到相邻的 `../datasource_api` 独立进程。

接口契约与 `ui/apps/web-ele` 前端对齐，统一挂载在 `/api` 前缀下。
生产环境通过 nginx 将主服务暴露为 `/biapi`，数据源服务按类型暴露为 `/dsapi/http`、`/dsapi/sql`、`/dsapi/python`。

## 当前能力

- JWT 鉴权
  - `accessToken` 通过请求头 `Authorization: Bearer <token>` 传递
  - `refreshToken` 写入 httpOnly Cookie，Cookie 名为 `jwt`
- 登录、刷新、退出
  - `/api/auth/login`
  - `/api/auth/refresh`
  - `/api/auth/logout`
- 权限码
  - `/api/auth/codes`
  - 按钮权限来自 `sys_menu.auth_code`
- 当前用户信息
  - `/api/user/info`
- 动态菜单
  - `/api/menu/all`
  - 用于 vben 后端权限模式
- 系统管理
  - `/api/system/user`
  - `/api/system/role`
  - `/api/system/menu`
- BI 大屏项目
  - `/api/bi/project/create`
  - `/api/bi/project/list`
  - `/api/bi/project/getData`
  - `/api/bi/project/save/data`
  - `/api/bi/project/edit`
  - `/api/bi/project/publish`
- BI 数据源
  - DB 连接器：`/api/bi/datasource/db-connectors`
  - HTTP/SQL/Python 数据源：由 `datasource_api`（默认端口 5321）提供

## 快速开始

```bash
cd api
pip install -r requirements.txt

# 数据库等运行配置见 config.ini

# 初始化数据库：手动执行仓库根目录 sql/easyreport_bi.sql

# 启动服务，默认端口 5320
python run.py
```

Windows EXE 与 Linux 可执行程序的打包命令见
[`../packaging/README.md`](../packaging/README.md)。

数据源功能还需要在另一个终端启动独立服务：

```bash
cd datasource_api
pip install -r requirements.txt
python run.py
```

服务启动后：

```text
API 状态: http://localhost:5320/api/status
Swagger:  http://localhost:5320/api/docs
OpenAPI:  http://localhost:5320/api/openapi.json
```

## 默认账号

| 用户名 | 密码   | 角色  | 说明       |
| ------ | ------ | ----- | ---------- |
| admin  | 123456 | super | 超级管理员 |
| jack   | 123456 | user  | 普通用户   |

`super` 角色自动拥有全部菜单和按钮权限。

> 以上为开发初始化账号。生产环境对外开放前必须修改 `admin` 密码，并处理或禁用
> 其他默认账号；未完成不得上线。

## 前端对接

前端开发环境文件：

```text
ui/apps/web-ele/.env.development
```

关键配置：

```ini
VITE_GLOB_API_URL=/biapi
VITE_GLOB_HTTP_DATASOURCE_API_URL=/dsapi/http
VITE_GLOB_SQL_DATASOURCE_API_URL=/dsapi/sql
VITE_GLOB_PYTHON_DATASOURCE_API_URL=/dsapi/python
VITE_NITRO_MOCK=false
```

前端使用以下固定前缀：

```text
/biapi -> http://localhost:5320/api
/dsapi/http -> HTTP数据源进程
/dsapi/sql -> SQL数据源进程
/dsapi/python -> Python数据源进程
```

登录账号：

```text
admin / 123456
```

## 配置项

运行配置文件：

```text
config.ini
```

模板文件：

```text
config.example.ini
```

也可以通过 `APP_CONFIG` 指定其它配置文件路径。环境变量优先级高于配置文件：

| 配置项 | 环境变量 | 示例/默认 | 说明 |
| ------ | -------- | --------- | ---- |
| `database.host` | `DB_HOST` | `localhost` | MySQL 主机 |
| `database.port` | `DB_PORT` | `3306` | MySQL 端口 |
| `database.user` | `DB_USER` | `root` | MySQL 用户名 |
| `database.password` | `DB_PASSWORD` | 空 | MySQL 密码 |
| `database.name` | `DB_NAME` | `easyreport_bi` | 数据库名 |
| `jwt.secret` | `JWT_SECRET` | `er-bi-jwt-secret-change-me-in-prod` | JWT 密钥，生产必须修改 |
| `jwt.algorithm` | `JWT_ALGORITHM` | `HS256` | JWT 签名算法 |
| `jwt.access_token_expire_minutes` | `ACCESS_TOKEN_EXPIRE_MINUTES` | `120` | accessToken 有效期，单位分钟 |
| `jwt.refresh_token_expire_days` | `REFRESH_TOKEN_EXPIRE_DAYS` | `7` | refreshToken 有效期，单位天 |
| `jwt.refresh_cookie_name` | `REFRESH_COOKIE_NAME` | `jwt` | refreshToken Cookie 名 |
| `server.host` | `HOST` | `0.0.0.0` | 服务监听地址 |
| `server.port` | `PORT` | `5320` | 服务端口 |
| `services.datasource_url` | `DATASOURCE_SERVICE_URL` | `http://127.0.0.1:5321/api` | 数据源执行服务内部地址 |
| `services.internal_token` | `INTERNAL_SERVICE_TOKEN` | 开发默认值 | 两个后端必须一致，生产必须修改 |
| `security.connector_secret_key` | `CONNECTOR_SECRET_KEY` | 空 | 配置后新保存的DB连接密码使用 Fernet 加密，生产必须设置并妥善备份 |

启用 `CONNECTOR_SECRET_KEY` 后，历史明文连接器需要在管理页面重新填写一次密码并保存，之后才会转换为加密存储。

当前前端默认未开启自动 refresh token。如果保持默认配置，`accessToken` 到期后下一次请求会要求重新登录。

## 目录结构

```text
api/
├── app/
│   ├── main.py                  # FastAPI 应用入口
│   ├── api/
│   │   ├── deps.py              # FastAPI Depends：DB、当前用户
│   │   └── v1/
│   │       ├── router.py        # v1 路由聚合
│   │       ├── auth.py          # 认证接口
│   │       ├── core.py          # 用户信息、动态菜单
│   │       ├── users.py         # 用户管理接口
│   │       ├── roles.py         # 角色管理接口
│   │       ├── menus.py         # 菜单管理接口
│   │       ├── bi_reports.py    # BI 大屏项目接口
│   │       ├── bi_datasources.py # DB连接器接口
│   │       └── internal.py       # 数据源服务内部接口
│   ├── core/
│   │   ├── config.py            # 配置
│   │   ├── exceptions.py        # 全局异常处理
│   │   ├── response.py          # 统一响应
│   │   └── security.py          # 密码哈希、JWT
│   ├── db/
│   │   ├── base.py              # SQLAlchemy Base 和模型聚合
│   │   ├── seed.py              # 种子数据
│   │   └── session.py           # engine、SessionLocal
│   ├── modules/
│   │   ├── auth/
│   │   │   ├── schemas.py
│   │   │   └── service.py
│   │   ├── system/
│   │   │   ├── models.py        # SysUser、SysRole、SysMenu
│   │   │   ├── schemas.py
│   │   │   ├── permissions.py
│   │   │   ├── menu_tree.py
│   │   │   ├── user_service.py
│   │   │   ├── role_service.py
│   │   │   └── menu_service.py
│   │   └── bi/                  # BI 业务模块
│   │       ├── models.py
│   │       ├── schemas.py
│   │       ├── connector_service.py   # DB连接器控制面
│   │       ├── dataset_service.py     # 预留数据集服务
│   │       ├── report_service.py      # 预留报表服务
│   │       └── dashboard_service.py   # 大屏项目服务
│   └── repositories/
│       ├── user_repo.py
│       ├── role_repo.py
│       ├── menu_repo.py
│       ├── datasource_repo.py   # DB连接器持久化
│       ├── project_repo.py      # BI 大屏项目持久化
│       └── report_repo.py       # 预留
├── run.py                       # 开发启动入口
└── requirements.txt
```

## 分层职责

请求处理链路：

```text
HTTP 请求
  -> app/api/v1/*.py
  -> app/modules/*/service.py
  -> app/repositories/*.py
  -> app/db/session.py
```

职责边界：

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| `api/v1` | HTTP 入参、依赖注入、调用 service、返回响应 | 复杂 SQL、业务规则 |
| `modules/*/service.py` | 业务规则、权限判断、事务编排 | HTTP 细节 |
| `repositories/*.py` | SQLAlchemy 查询、保存、删除、关联处理 | 业务判断 |
| `modules/*/schemas.py` | Pydantic 请求模型 | 数据库操作 |
| `modules/*/models.py` | SQLAlchemy 表模型 | 接口逻辑 |
| `core` | 配置、安全、异常、响应 | 具体业务 |
| `db` | 数据库连接、Base、种子数据 | 接口逻辑 |

开发约定：

- 新接口先放到 `app/api/v1`。
- 业务逻辑放到对应 `modules/*/service.py`。
- SQL 查询放到 `repositories`。
- 系统管理相关放 `modules/system`。
- BI 相关能力放 `modules/bi`，不要塞进系统管理模块。

## RBAC 数据模型

```text
sys_user ──< sys_user_role >── sys_role ──< sys_role_menu >── sys_menu
```

说明：

- `sys_user`：用户。
- `sys_role`：角色。
- `sys_menu`：菜单、路由、按钮权限。
- `sys_user_role`：用户和角色的多对多关系。
- `sys_role_menu`：角色和菜单/按钮权限的多对多关系。
- `sys_menu.type = button` 表示按钮权限。
- `sys_menu.auth_code` 是前端按钮级权限码。

## 数据库脚本

发布时请手动执行仓库根目录下的 SQL 脚本：

```text
sql/easyreport_bi.sql
```

该脚本包含：

- 仪表盘菜单
- BI 大屏设计菜单
- DB / HTTP / SQL / Python 数据源管理菜单
- 系统管理菜单
- 菜单/角色/用户管理按钮权限
- `super`、`admin`、`user` 三个角色
- `admin`、`jack` 两个用户

## 后续建议

- BI 模块已按大屏项目、DB 连接器、HTTP 数据源、SQL 数据源、Python 数据源细分；后续可继续补充数据集和报表服务。
- 生产环境必须覆盖 `JWT_SECRET` 和数据库密码。
