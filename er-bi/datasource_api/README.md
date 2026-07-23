# ER-BI Datasource Service

独立承载 HTTP、SQL、Python 数据源的配置、版本、测试和执行。DB 连接器仍由
`../api` 主服务管理，本服务通过受保护的内部接口按需获取连接配置。

## 本地启动

先安装两个后端各自的依赖，然后分别启动：

```powershell
cd api
pip install -r requirements.txt
python run.py
```

```powershell
cd datasource_api
pip install -r requirements.txt
python run.py
```

默认端口：

- 主 API：`http://localhost:5320/api`
- 数据源 API：`http://localhost:5321/api`

本地默认读取当前目录下的 `config.ini`。也可以通过
`DATASOURCE_APP_CONFIG` 指向其他配置文件；环境变量优先于配置文件。

`ui/apps/web-ele/vite.config.ts` 和 `bi_designer/vite.config.ts` 使用以下固定前缀：

- `/biapi`：主服务和 DB 连接器。
- `/dsapi/http`：HTTP 数据源。
- `/dsapi/sql`：SQL 数据源。
- `/dsapi/python`：Python 数据源。

## 关键环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `5321` | 服务端口 |
| `MAIN_API_URL` | `http://127.0.0.1:5320/api` | 主 API 内部地址 |
| `INTERNAL_SERVICE_TOKEN` | 开发默认值 | 两个后端必须一致，生产必须修改 |
| `JWT_SECRET` | 开发默认值 | 必须与主 API 一致 |
| `HTTP_ALLOW_PRIVATE_NETWORKS` | `true` | Docker 默认覆盖为 `false` |
| `SQL_READ_ONLY` | `false` | Docker 默认覆盖为 `true` |
| `SQL_MAX_ROWS` | `10000` | SQL 最大返回行数 |
| `PYTHON_TIMEOUT_SECONDS` | `8` | Python 单次执行超时 |
| `PYTHON_MEMORY_LIMIT_MB` | `256` | Linux 子进程地址空间限制 |

## Docker

仓库根目录执行：

```powershell
docker compose up --build
```

Windows EXE 与 Linux 可执行程序的打包命令见
[`../packaging/README.md`](../packaging/README.md)。

生产环境使用 `../deploy/nginx-datasource-split.conf` 中的路由规则。三个路径前缀
统一转发到 `er-bi-datasource:5321`，运维层可以分别配置访问策略、限流和日志。
