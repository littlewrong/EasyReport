# JDBC Resource 初始化导入

适用于 MySQL、TiDB、StarRocks、金仓（`KINGBASE`）、达梦（`DM`）、SQL Server、PostgreSQL、Oracle 共八类源到 StarRocks 的初始化同步，按 StarRocks **3.1.5** 的 JDBC Resource 扫描方式接入。原直接 `ENGINE=MYSQL` 外部表方式已统一为 JDBC Resource；JDBC Catalog 保留原有支持范围。

## 上线及使用

1. 选择 er-sync 的配置数据库，执行 `sql/upgrade_20261111_jdbc_resource.sql`。脚本按需增加 `er_data_transfer.resource_name`，并把旧 `EXTERNAL_TABLE` 方法标记转为 `JDBC_RESOURCE`；不猜测资源名、不改启用开关，可重复执行。
2. 发布并重启后端，部署新的前端产物。
3. 在目标 StarRocks 预先配置可以读取源库的 JDBC Resource，并用真实的外表查询确认连接和驱动可用。
4. 新增或编辑数据传输任务：选择受支持的源、StarRocks 目标和初始化模式，开启数据湖导入，选择 **JDBC Resource**，填写实际 Resource 名称。

旧外部表任务会显示为 Resource 方式，但程序不会猜测 Resource 名称。原来启用了直接外部表的任务必须补填资源名才能继续执行；缺名时在清空目标前失败。已经选择 JDBC Catalog 的任务保持该方式，未开启数据湖导入的任务继续使用原 JDBC 批量搬运。

此方式不需要填写 Catalog 名称，也不在应用中填写驱动 JAR 地址。Resource 名称应与目标集群中已有名称完全一致；可使用 `SHOW RESOURCES` 核对。名称采用字母开头、字母/数字/下划线组成，最长 64 个字符。

## Resource 配置示例

以下 SQL 供管理员预先配置，任务执行器不会执行创建、修改或删除 Resource 的操作。地址、数据库、账号和密码均应替换为实际值。

```sql
CREATE EXTERNAL RESOURCE "jdbc_mysql_source"
PROPERTIES (
    "type" = "jdbc",
    "user" = "<source_user>",
    "password" = "<source_password>",
    "jdbc_uri" = "jdbc:mysql://<host>:3306/<database>?useSSL=false&serverTimezone=GMT%2B8",
    "driver_url" = "file:///opt/starrocks/jdbc/mysql-connector-j-8.0.33.jar",
    "driver_class" = "com.mysql.cj.jdbc.Driver"
);

CREATE EXTERNAL RESOURCE "jdbc_kingbase"
PROPERTIES (
    "type" = "jdbc",
    "user" = "<source_user>",
    "password" = "<source_password>",
    "jdbc_uri" = "jdbc:kingbase8://<host>:54321/<database>?useSSL=false",
    "driver_url" = "file:///opt/starrocks/jdbc/kingbase8-9.0.0.jar",
    "driver_class" = "com.kingbase8.Driver"
);

CREATE EXTERNAL RESOURCE "dm_resource"
PROPERTIES (
    "type" = "jdbc",
    "user" = "<source_user>",
    "password" = "<source_password>",
    "jdbc_uri" = "jdbc:dm://<host>:5236",
    "driver_url" = "file:///opt/starrocks/jdbc/DmJdbcDriver11.jar",
    "driver_class" = "dm.jdbc.driver.DmDriver"
);

CREATE EXTERNAL RESOURCE "jdbc_postgresql_source"
PROPERTIES (
    "type" = "jdbc",
    "user" = "<source_user>",
    "password" = "<source_password>",
    "jdbc_uri" = "jdbc:postgresql://<host>:5432/<database>",
    "driver_url" = "file:///opt/starrocks/jdbc/postgresql.jar",
    "driver_class" = "org.postgresql.Driver"
);

CREATE EXTERNAL RESOURCE "jdbc_sqlserver_source"
PROPERTIES (
    "type" = "jdbc",
    "user" = "<source_user>",
    "password" = "<source_password>",
    "jdbc_uri" = "jdbc:sqlserver://<host>:1433;databaseName=<database>;encrypt=true;trustServerCertificate=false",
    "driver_url" = "file:///opt/starrocks/jdbc/mssql-jdbc.jar",
    "driver_class" = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
);

CREATE EXTERNAL RESOURCE "jdbc_oracle_source"
PROPERTIES (
    "type" = "jdbc",
    "user" = "<source_user>",
    "password" = "<source_password>",
    "jdbc_uri" = "jdbc:oracle:thin:@//<host>:1521/<service_name>",
    "driver_url" = "file:///opt/starrocks/jdbc/ojdbc11.jar",
    "driver_class" = "oracle.jdbc.OracleDriver"
);
```

TiDB 与 StarRocks 源同样使用 MySQL JDBC Resource，URI 分别指向实际 TiDB SQL 端口或 StarRocks FE 查询端口。Resource 的地址、端口和数据库由用户按 StarRocks 节点的网络及部署配置；程序不比较它们是否与应用数据源相同。用户及日期时间、会话语义仍需通过校验；示例端口及参数应按实际部署调整。

FE 和 BE 必须能读取 Resource 的驱动地址。使用 `file://` 时，文件位置指向 StarRocks 节点上的文件，并非应用服务器上的 Maven 依赖。使用 Java 11 驱动时，BE 的 `JAVA_HOME` 也需指向兼容的 JDK；已有成功使用的驱动及 Resource 可以继续沿用。

## BLOB / 二进制字段写入文本列

MySQL / TiDB 的 `BINARY`、`VARBINARY`、`TINYBLOB`、`BLOB`、`MEDIUMBLOB`、`LONGBLOB` 映射为 StarRocks `STRING` / `VARCHAR` / `CHAR` 时，按 **UTF-8 原文**读取，例如发票二维码的 URL 原样保存，不转为 HEX，也不要求部署 MySQL 兼容驱动 JAR。Oracle 的 `BLOB`、`RAW`、`LONG RAW` 写入文本列同样适用。

普通 JDBC 全量及增量使用相同的按列读取规则。初始化任务选择 Resource 或 Catalog 时，含上述映射的整张表自动使用应用数据源连接进行 JDBC 批量同步，日志和进度会说明该选择；此表不使用 Resource / Catalog 连接。其它表继续原导入方式，不会因 Resource / Catalog 连接失败而自动切换。现有任务的导入方式和资源名称配置要求保留。

全量同步在清空目标前流式检查所有有效行的 UTF-8 编码及目标字节容量；配置时间戳字段时仅检查非 NULL 时间戳的行。SQL NULL 和空字节串分别保留为 NULL、空文本。每值受目标列容量及 1 MiB 上限约束，实际写入时再次检查，拒绝乱码、替换字符和静默截断。原生 `BINARY` / `VARBINARY` 目标继续保留二进制语义，不按文本解码；图片等非 UTF-8 二进制应使用二进制目标列。

应用 JDBC 路径无需额外节点驱动，但读取流量经过应用服务器，含 BLOB 的大表应按该路径估算同步耗时。混合表的 MySQL / TiDB `BIT` 保留无符号数值，`TIME` 保留负号、超过 24 小时的时长及微秒。

## MySQL / TiDB 兼容驱动 v2：BIT、原生二进制目标和 TIME

MySQL 的 `BIT(n)` 按无符号数字保留，自动建表使用以下 StarRocks 类型。`BIT(8)` 的 `255` 会保留为数字 `255`；`BIT(64)` 的上界为 `18446744073709551615`，需要 `LARGEINT`。

| 源类型 | StarRocks 目标类型 | 数值范围 |
| --- | --- | --- |
| `BIT(1)`（包括省略位宽的 `BIT`） | `BOOLEAN` | 0、1 |
| `BIT(2)` 至 `BIT(31)` | `INT` | 0 至 2^n−1 |
| `BIT(32)` 至 `BIT(63)` | `BIGINT` | 0 至 2^n−1 |
| `BIT(64)` | `LARGEINT` | 0 至 18446744073709551615 |

标准 [Connector/J 8.0.33](https://github.com/mysql/mysql-connector-j/blob/8.0.33/src/main/user-impl/java/com/mysql/cj/jdbc/result/ResultSetImpl.java) 对多位 `BIT` 的 `getObject` 返回字节数组；[StarRocks 3.1.5 JDBCScanner](https://github.com/StarRocks/starrocks/blob/3.1.5/java-extensions/jdbc-bridge/src/main/java/com/starrocks/jdbcbridge/JDBCScanner.java) 按元数据中的 Java 类分配数组，再读取 `getObject`。两者组合不能直接保证多位 BIT 的数值读取，单独修改目标列类型仍会在扫描时失败。仍使用 Resource 扫描的 MySQL / TiDB 表若包含多位 BIT，应使用兼容驱动，由它把这些值及元数据统一为可读取的无符号数值；连接 URL 继续使用真实的 `jdbc:mysql://...`。旧 BIT 专用 Resource 可保留 `cn.easyreport.sync.jdbc.MySqlBitCompatibleDriver`，原生二进制目标 / TIME 通道须使用下述 v2 驱动类。含 BLOB→文本映射的表按上一节使用应用 JDBC。

管理员按以下步骤单独配置：

1. 兼容驱动需由管理员另行提供；项目已移除独立驱动目录、构建脚本及 JAR 产物。以下配置仅适用于 StarRocks 节点已经具备对应兼容驱动的场景。
2. 将新 fat JAR 部署到所有相关 FE / BE 可读取的位置，例如 `/opt/starrocks/jdbc/ersync-mysql-resource-compat-8.0.33-v2.jar`。已有节点也需要部署该文件，按节点部署流程使驱动及连接池生效。
3. 预建一个独立 Resource，替换下面所有地址、数据库和账号占位符，保留业务所需的日期时间参数。确认真实外表读取可用。
4. 在任务中选择 JDBC Resource，填写新资源名 `jdbc_mysql_resource_compat_v2`，驱动类为 `cn.easyreport.sync.jdbc.MySqlResourceCompatibleDriver`。应用只读取已有 Resource 配置，不创建、修改或删除资源，也不修改源库。

```sql
CREATE EXTERNAL RESOURCE "jdbc_mysql_resource_compat_v2"
PROPERTIES (
    "type" = "jdbc",
    "user" = "<source_user>",
    "password" = "<source_password>",
    "jdbc_uri" = "jdbc:mysql://<host>:3306/<database>?useSSL=false&serverTimezone=GMT%2B8&connectionAttributes=ersync_resource:compat_v2",
    "driver_url" = "file:///opt/starrocks/jdbc/ersync-mysql-resource-compat-8.0.33-v2.jar",
    "driver_class" = "cn.easyreport.sync.jdbc.MySqlResourceCompatibleDriver"
);
```

StarRocks 3.1.5 的 JDBC 连接池以“用户＋完整 JDBC URL”为缓存键，未包含 Resource 名或驱动类。示例中的 `connectionAttributes=ersync_resource:compat_v2` 为新 Resource 提供独立 URL 标记，同时与旧标准驱动和 BIT v1 驱动连接池区分；仅更换 Resource 名而复用相同用户和完整 URL，仍可能取得旧驱动连接池。也可由管理员按节点部署流程刷新旧连接池。已有 Resource 不会自动切换驱动，已有目标表也不会因映射规则更新而自动扩宽；任务预检会检查实际读取及目标容量，在不兼容时于清空目标前失败。

v2 还为仍使用 Resource 扫描的 MySQL / TiDB 表提供以下通道：

- `BINARY`、`VARBINARY`、`TINYBLOB`、`BLOB`、`MEDIUMBLOB`、`LONGBLOB` 写入原生二进制目标时，以大写 HEX 文本传输，再由 `TO_BINARY` 还原字节。例如 `00 FF` 经 `00FF` 还原为原始两字节，空值及 NULL 保持原义。中间 HEX 长度超过 1 MiB 时在清空前拒绝。文本目标按上一节保存 UTF-8 原文。
- `TIME(0..6)` 保留为时间间隔文本，保留负号、超过 24 小时的时长及微秒，SQL NULL 保持 NULL。v2 从原始值解码，避开 Connector/J 时间字符串转换对负时长的影响；不转换为一天内的 `java.sql.Time`。该字段不能作为任务的日期时间水位字段。

上述原生二进制目标和 TIME 通道同样会通过真实 Resource 扫描及实际文本字节数预检。只有更新后端而仍使用旧 Resource / JAR，不能启用这些 v2 扫描通道；BLOB 写入文本目标不受此要求限制。

## 源库与字段

程序不校验 Resource 与应用数据源的服务器、端口、数据库是否一致，也不比较应用实际连接元数据返回的地址，允许内外网地址、映射端口、数据库名以及 Oracle SID/service 写法不同。Resource 由用户预先配置，仍需使用对应数据库的真实 JDBC 协议及驱动；账号和影响读取的日期时间、会话设置继续校验。创建临时外表后仍会通过实际 Resource 读取进行预检。

PostgreSQL、金仓的 JDBC URL 包含数据库名，任务的 Schema 单独限定源表；达梦的 `databaseName` 配置表示默认 Schema，不能当成 MySQL 数据库名拼接。SQL Server 任务的 Schema 字段仍表示应用源数据库，并按现有任务规则使用该数据库及 `dbo` Schema；Resource URL 的 `databaseName` 不要求与之相同。Oracle 区分服务名或 SID 与表所属 Schema。未填写 Schema 时，PostgreSQL 沿用任务提取器的 `public`，Oracle 沿用连接用户名对应的 Schema，避免受会话默认模式改变影响。生成的 JDBC 外表继续按任务源表规则显式限定数据库或 Schema 和表名。

支持可由 StarRocks 3.1.5 JDBC 扫描器直接读取的标量数值、普通字符串和日期时间。字段的 JDBC SQL 类型、Java 类型、数值精度及目标容量都需要通过预检。MySQL 协议来源的原生 JSON 使用经过容量校验的文本通道，并按目标类型验证转换；BLOB→文本映射使用上述应用 JDBC 路径，仍经 Resource 扫描的 MySQL / TiDB 原生二进制目标及 TIME 字段按上述 v2 规则接入。达梦的兼容范围见下节。其他未经验证的 LOB、二进制以及 PostgreSQL JSON/JSONB、数组、带时区类型等会拒绝导入。

已有目标整数或 DECIMAL 列比源整数类型的完整范围更窄时，程序对真实 Resource 外表的所有有效行聚合检查最小值和最大值，使用与导入相同的时间戳非 NULL 条件。例如源 `TINYINT UNSIGNED` 的实际 age 为 0–127，可以写入已有 `TINYINT`；出现 128–255 就在清空前失败。该规则也适用于多位 BIT、BigInteger 整数，DECIMAL 目标必须能精确容纳整数值；小数位和浮点转换规则保持不变。任务不自动修改已有目标表结构。预检并非源库快照，每批实际写入还带逐行范围断言，防止随后出现的超范围值被截断；发生此类变化时本批失败，进度不推进。

金仓的兼容模式可能改变 `DATE` 的 JDBC 类型。达梦的 `convertToTimestamp` 与 `dateToTimestamp` 含义不同：前者影响原生 Java 时间类型，后者影响 Oracle 兼容 DATE 的时间部分。本节下述达梦 SQL 投影在数据库端保留时间，不要求补加这些参数；不必仅为通过校验而改变业务数据库的兼容模式。

Oracle `DATE` 可能包含时分秒，必须由驱动以保留时间的类型返回。带时区的 Oracle 时间字段、SQL Server `datetimeoffset`、PostgreSQL `timestamptz` 均不按普通 DATETIME 导入。SQL Server 的 `timestamp`/`rowversion` 是二进制版本号，不能选作日期时间字段。

Oracle 自然时间窗口会在真实 Resource 上逐一预检。StarRocks 3.1.5 下推的日期字符串需要与 Oracle 会话 NLS 格式兼容；不兼容时会在清空目标前提示失败，由管理员核对已有 Resource 的会话及部署兼容性。任务不会自动修改 Oracle 会话或 Resource。

## 达梦：沿用当前 JDBC 驱动

达梦 Resource 继续使用 `dm.jdbc.driver.DmDriver` 和已有驱动 JAR。应用通过只读派生查询引用双引号限定的物理表和字段，并为扫描器生成普通字段别名，兼容保留字和大小写，不修改源表或 Resource。

| 达梦源字段 | Resource 读取与目标要求 |
| --- | --- |
| `BINARY`、`VARBINARY`、`RAW` | 源端 `RAWTOHEX` 转大写 HEX 文本，逐行与 JDBC 原始字节核对。目标文本保存 HEX；目标 `BINARY` / `VARBINARY` 经现有 `TO_BINARY` 通道还原字节。需通过 NULL、空值、容量及解码校验。 |
| `CLOB`、`NCLOB`、`TEXT` | 使用现有驱动的 `getString` 文本通道，逐行与字符流比较，不先截取或强转为短 `VARCHAR`。目标必须为足够容量的文本列。 |
| `DATE` | 达梦端先转 `TIMESTAMP`，再以固定格式传输年月日、时分秒；StarRocks 显式转 `DATETIME`。不再强制源和 Resource 配置 `dateToTimestamp=true`。 |
| `TIMESTAMP` / `DATETIME` | 使用保留小数秒的固定格式文本通道，再显式转 `DATETIME`，同时兼容源连接和 Resource 返回标准 `Timestamp` 或专用 `DmdbTimestamp` 的差异。 |

文本及 HEX 通道上限为每值 1 MiB，HEX 每字节占两个字符；还需满足实际目标字段容量。超限、读取内容不一致、时间戳精度超过微秒、带时区或年份超出 1 至 9999 等仍在清空目标前失败，不静默截断或改变纪元。`BLOB` / `IMAGE` 尚未纳入本次达梦二进制兼容范围。

新建 StarRocks 表时，达梦 `DATE` 映射为 `DATETIME`，保留 Oracle 兼容模式中可能存在的时分秒。已有目标表不会自动改列：若 `COL_DATE`、`BIRTHDAY`、`HIRE_DATE` 等仍为 `DATE`，请先调整为 `DATETIME` 后再运行，以通过无损写入校验。

## 执行与清理

### 数据传输停止与异常状态恢复

数据传输的“停止中”表示后台仍在取消 SQL 或清理资源，“已停止”在本轮执行退出后确认。进度窗口和列表“更多”菜单均提供“重试停止”，按钮只在本次请求期间等待，不会因为任务处于“停止中”而永久禁用；页面显示以后端返回的真实状态为准。

正常取消超过宽限时间仍未结束时，执行器会尝试终止本轮原始连接；只有确认连接关闭后才解除取消等待。被终止的会话不再复用，临时外表等清理使用独立新连接，避免迟到的取消请求影响清理。驱动终止失败时不会仅因超时而宣称任务已停止。

终止后的清理连接建立限时 5 秒；超时会记录清理错误并提示检查残留临时外表，迟到的连接会被关闭，不会继续执行导入或清理 SQL。

服务异常退出或重启后，历史任务可能遗留为“停止中”。确认后台执行及数据库同步 SQL 均已结束后，可在同样两个位置选择“恢复为已停止”。后端发现本实例仍持有执行线程或执行器时，会拒绝复位并再次请求取消；恢复 SQL 只允许把仍为“停止中”的任务改成“已停止”，不会覆盖新启动的任务，也不清空已有表级增量水位。此入口用于恢复遗留状态，操作会记入审计日志；多实例部署还须先确认其它实例已退出该任务。

### Resource 预检与临时表清理

任务读取并核对已有 Resource，检查源字段、目标字段和导入策略，创建带唯一名称的 JDBC 外部表，实际读取并验证导入语句后才清空目标数据。

SQL Server 的读取预检采用无 `LIMIT` 的聚合，兼容 StarRocks 3.1.5 的源 SQL 生成行为；预检会额外扫描有效数据，各自然时间窗口也会实际验证。大表应为预检预留时间，目标数据仍在全部预检通过后才清空。

达梦也采用无 `LIMIT` 的实际读取预检，避免 StarRocks 将预检的 `LIMIT 1` 下推给不接受该语法的源兼容模式。若仍出现 `SQLState=42000, errorCode=1064`，错误信息会区分外表创建和读取预检阶段；该错误码本身不足以确认是驱动、网络还是 SQL 语法问题。所有预检通过前仍不会清空目标表。

整批阈值和自然年/月/日窗口与其他数据湖导入方式一致；配置时间戳字段时跳过该字段为 SQL NULL 的记录，未配置时整表导入。失败、停止或正常结束都会尝试清理本次临时外表，不删除已有 Resource。Resource 不可用或配置发生变化时停止任务，不自动重建资源或改走其他导入方式。

相关官方资料：[StarRocks 外部表](https://docs.starrocks.io/zh/docs/data_source/External_table/)、[3.1.5 JDBC 扫描器](https://github.com/StarRocks/starrocks/blob/3.1.5/be/src/connector/jdbc_connector.cpp)、[Oracle JDBC 日期映射](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/JDBC-reference-information.html)、[达梦 JDBC 指南](https://eco.dameng.com/document/dm/zh-cn/pm/jdbc-rogramming-guide.html)。
