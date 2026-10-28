# 数据传输

## 概述

数据传输功能实现源库到目标库的行级数据同步，支持全量初始化和增量同步两种模式。两种模式都可以结合时间戳字段控制同步范围。普通 JDBC 初始化按天分片；目标为 StarRocks 时，可开启“数据湖导入”，选择外部表或 JDBC Catalog，根据源表有效行数和任务配置选择整批或按年、月、日导入。已配置时间戳字段时，初始化跳过该字段为 NULL 的记录。

## 同步模式

### 全量同步（初始化）

将目标表清空后，把源表数据重新导入目标表。普通 JDBC 方式配置时间戳字段后，系统会先读取该字段的最小值和最大值，再按天生成时间窗口逐段查询并批量写入目标表。

适用于：
- 首次数据同步
- 大数据量表初始化
- 需要完全重建目标表数据的场景

这种方式不是一次性扫描并传输整张表，而是用时间戳字段做分片边界。它会牺牲部分小表场景的一次性全表扫描性能，换取大表同步时更稳定的内存、连接和事务控制。

#### 数据湖初始化到 StarRocks

目标数据源为 **StarRocks**、同步模式为**初始化同步**时，支持的源库可以开启“数据湖导入”。新增任务默认关闭；开启后的默认方式为 **JDBC Catalog**，可按源类型选择：

| 导入方式 | 源数据源 | 准备条件 |
|------|------|------|
| 外部表 | MySQL、TiDB | 应用为本次源表创建独占的 `ENGINE=MYSQL` 临时外部表，任务结束后清理；无需预建 Catalog |
| JDBC Catalog | MySQL、TiDB、PostgreSQL、Oracle、SQL Server、StarRocks | 管理员提前创建连接相应源库的 JDBC Catalog，并填写名称；目标 StarRocks 需支持相应源类型 |

两种方式共用“有效数据少于指定万条数时整批导入，否则按年、月或日导入”的策略，默认 **50 万条、年**；均隐藏并保留原 JDBC 批次大小。关闭数据湖导入后恢复普通 JDBC。MySQL / TiDB 的多库环境如果在 Catalog 元数据访问上耗时较多，可以选择按指定表建立映射的外部表；准确计数和源库扫描本身仍可能耗时。

已保存任务优先使用明确的导入方式 `direct_import_method`（`EXTERNAL_TABLE` / `JDBC_CATALOG`）。旧任务未保存方式时，按 Catalog 名称推断：非空为 JDBC Catalog，空值为外部表。编辑、复制和升级保持原方式，不强制旧外部表任务改用 Catalog。切换方式会保留已填写的 Catalog 名称，但只有选中 JDBC Catalog 时才使用和校验它。切换到仅支持 Catalog 的源时，界面保留开启状态，改选 JDBC Catalog 并提示确认名称；切到不支持数据湖导入的目标或增量模式时关闭开关。

#### JDBC Catalog 导入

开启“数据湖导入”并选择 **JDBC Catalog** 后，填写必填的 **Catalog 名称**，例如 `src_mysql`。Catalog 由管理员提前在目标 StarRocks 集群中创建；应用使用现有 Catalog，不自动创建或删除 Catalog、Resource、外部表。选择外部表时无需填写 Catalog 名称。

1. 在 StarRocks 中确认源表可访问，例如 `SELECT * FROM src_mysql.erp_base.rf_user LIMIT 10`。Catalog 必须连接任务所选的同一源数据库，所有读取节点都需具备访问源库和 JDBC 驱动的条件。
2. 新增或编辑同步任务，开启“数据湖导入”，选择 JDBC Catalog 并填写 Catalog 名称，按需要调整共用的整批阈值和时间窗口。
3. 应用直接通过源 JDBC 连接统计有效行数、读取时间戳范围，不通过 Catalog 扫描全表计数。配置时间戳字段时，计数和导入均过滤 NULL；无时间戳字段时整表导入。
4. 清空目标表前，检查 Catalog 源表可读性及目标 INSERT 执行计划。预检失败时任务报错，保留原目标数据；不会因为 Catalog 名称错误自动改走其他初始化路径。
5. 使用显式列名执行 `INSERT INTO 目标内部表 (...) SELECT ... FROM Catalog.源库或Schema.源表`。有效数据少于阈值时一次导入，达到阈值（包含相等）时按所选自然时间窗口串行导入。停止、提交和整表成功后保存断点的行为沿用现有逻辑。

MySQL / TiDB 的三段表名例如 `src_mysql.erp_base.rf_user`；PostgreSQL 通常为 `src_pg.public.rf_user`；Oracle 使用实际用户 Schema；SQL Server Catalog 的 JDBC URL 固定数据库，三段表名中间通常是 `dbo`，例如 `src_sqlserver.dbo.rf_user`。StarRocks 源使用 MySQL JDBC 驱动连接源集群的 FE 查询端口（通常为 9030），三段表名例如 `src_sr.erp_base.rf_user`。不同源连接仍需配置对应 Catalog，不能让一个 Catalog 自动连接其他服务器或数据库类型。

EasyReport 与 StarRocks 可以通过不同网络入口访问同一源库：应用的源 JDBC 使用外网地址，Catalog 的 `jdbc_uri` 使用内网地址，主机名和端口均可不同，支持域名别名、代理和 NAT 端口映射。应用不会要求这两条连接的地址字符串一致；需由配置者确认它们实际连接同一套源数据。目标 StarRocks 的 9030 连接地址也独立于 Catalog 的源库连接地址。

本版仍会在清空目标前核对 Catalog 类型及固定数据库，支持标准单节点 JDBC 地址；源端实际 JDBC 连接也需与应用保存的源连接配置一致。Catalog 与任务源连接可以使用不同账号和独立密码，由使用者保证两端账号对所需表及行具有读取权限和一致的数据可见范围；程序仍会执行 Catalog 实际读取、字段映射和插入计划预检，但不会完整比对两账号可见的全部数据。SQL Server 沿用当前任务的 `dbo` Schema 约定；复杂实例名、Oracle TNS、负载均衡 JDBC 地址尚不在本版核对范围内。连接中的会话、角色和时区覆盖参数仍需要一致；无法确认一致时提前报错。用于分窗的时间戳字段需为日期或普通日期时间类型，不把 SQL Server `timestamp/rowversion`、数值时间戳或带时区时间类型当作普通日期分窗。

MySQL / TiDB 的 TIMESTAMP 校验会读取源会话、全局及系统时区。`SYSTEM` 会解析为 `@@system_time_zone`，例如 `Asia/Shanghai`，不会因 Catalog 未显式填写 `serverTimezone` 就直接拒绝。系统会在源端 MIN/MAX 得到的完整有效日期范围内核对时区规则：现代数据中的 `GMT+8` 与 `Asia/Shanghai` 可自动通过，历史夏令时造成的实际差异仍在清空目标前报错。未知缩写不猜测；普通 DATE/DATETIME 和有效数据为零的表不增加此时区查询。

Catalog 文本列写入目标 JSON 列时，沿用安全的 JSON 解析表达式与标量校验，保留数字、布尔值、JSON `null` 和 SQL NULL 的区别；Catalog 原生 JSON 列则直接导入。源端 COUNT 会同时聚合相关 JSON 文本的 UTF-8 最大字节数，超过 Catalog 文本容量或 JSON 表达式容量时在清空目标前报错，不截断。Oracle / SQL Server 对该长度使用保守字节上界。JSON 文本的表达式上限为 1,048,574 字节，还可能受 Catalog 字段声明容量进一步限制。

应用提供 MySQL、TiDB、PostgreSQL、Oracle、SQL Server 和 StarRocks 的源端统计适配，但目标 StarRocks 也必须支持相应 JDBC Catalog。MySQL / PostgreSQL 从 3.0 开始支持；Oracle / SQL Server 在 3.2.9、3.3.1 对应版本线引入，因此现有 **3.1.5 环境不能直接使用 Oracle / SQL Server JDBC Catalog**。StarRocks 源通过 MySQL 协议访问，字段是否可导入还需以目标 Catalog 的实际映射及读取预检为准。特殊字段类型和日期精度受 StarRocks 自身映射能力限制，不能仅凭 Catalog 创建成功判断整表可导入。参见 [StarRocks JDBC Catalog 文档](https://docs.starrocks.io/docs/data_source/catalog/jdbc_catalog/)。

StarRocks 源会在清空目标前读取实际表结构。ARRAY、MAP、STRUCT、HLL、BITMAP、PERCENTILE 和 VARBINARY 等不适合通过 MySQL JDBC Catalog 无损转换的字段会提前报错。原生 JSON 如果经 Catalog 映射为文本再写入目标 JSON，仍使用现有安全解析，长度统计在源 StarRocks 通过 `JSON_STRING` 计算实际文本字节数。应先使用普通数值、文本、日期字段的代表性表验证跨集群导入，源与目标不能指向同一张物理表。

StarRocks 源库表名与目标库表名相同时，还会核对集群身份，防止两个 FE 地址或内外网别名实际指向同一集群后清空源表。直接匹配到同地址的源表会提前拒绝；地址不同时，通过两端 `SHOW FRONTENDS` 的 `ClusterId` 确认不是同一集群。这种同名导入需要两端账号具备读取该元数据的权限；无法确认时保留目标数据并报错，也可以改用不同目标库名或表名。不同库表映射不增加此元数据权限要求。参见 [SHOW FRONTENDS 权限与 ClusterId](https://docs.starrocks.io/docs/sql-reference/sql-statements/cluster-management/nodes_processes/SHOW_FRONTENDS/)。

Oracle 的 `DATE` 本身包含时分秒；部分版本的 JDBC Catalog 却把它映射为 StarRocks `DATE`，无法在后续 `INSERT SELECT` 中恢复已经丢失的时间。应用会读取源字段元数据并对比 Catalog 的实际 `DESCRIBE` 结果：该列需要写入 DATETIME、文本列，或用作任务时间戳时，拒绝这种映射；非任务时间戳列且目标明确为 DATE 时，允许按日期导入。

支持 `oracle.temporal.to-datetime` 的 StarRocks 版本（已核查 4.1.0、4.1.6）可以在 Oracle Catalog 中配置 `"oracle.temporal.to-datetime"="true"`，使普通 DATE / TIMESTAMP 映射为 DATETIME。此参数并非所有支持 Oracle Catalog 的旧版本都具备；配置后仍应通过 `DESCRIBE Catalog.Schema.表` 确认实际结果，而不能仅检查属性文本。普通 TIMESTAMP 如果在 Catalog 中为 VARCHAR，整批导入目标 DATE / DATETIME 时使用显式转换，非 NULL 值转换失败会终止导入；该文本列作为任务时间戳且达到分窗阈值时提前报错，避免日期条件无法正确下推。带时区字段不支持作为任务时间戳，也不允许直接写入普通日期时间列；日期精度仍受目标类型限制。

Oracle 使用原生日期时间字段分窗时，还会在清空目标前实际执行第一个自然时间窗口的 `SELECT ... LIMIT 1`，检查日期谓词下推与 Oracle NLS 日期格式是否兼容。该预检只验证首个窗口和少量记录，不代表已经验证全部字段值、所有窗口或真实库的完整导入；运行中若发生转换或读取错误仍会停止任务。类型与版本依据见 [Oracle 3.2.9 映射源码](https://github.com/StarRocks/starrocks/blob/3.2.9/fe/fe-core/src/main/java/com/starrocks/connector/jdbc/OracleSchemaResolver.java)、[Oracle 4.1.0 映射源码](https://github.com/StarRocks/starrocks/blob/4.1.0/fe/fe-core/src/main/java/com/starrocks/connector/jdbc/OracleSchemaResolver.java) 和 [4.1.6 日期谓词下推源码](https://github.com/StarRocks/starrocks/blob/4.1.6/fe/fe-core/src/main/java/com/starrocks/planner/JDBCScanNode.java)。

Catalog 名称保存在 EasyReport 配置库的 `er_data_transfer.catalog_name`。导入方式保存在可空的 `direct_import_method`，本次新增 **`upgrade_20261023_data_transfer_import_method.sql`**。已有 Catalog 版本在配置库执行此新脚本，再更新前后端；已有外部表版本若缺少 `catalog_name`，需先执行 `upgrade_20261023_data_transfer_catalog.sql`。更早安装还需按缺失情况补齐外部表开关和策略脚本。新安装使用最新 `easyreport.sql`，其中包含删表重建语句，不能用于已有生产库升级。脚本只在 EasyReport 配置库执行，无需在源库或 StarRocks 执行。

新升级脚本只增加可空的导入方式字段，不批量改写旧任务；首次编辑或复制保存时固化推断出的方式。正常升级不执行回滚脚本，也不删除已有 StarRocks Catalog。升级保留旧任务的开关、Catalog 名称和策略；未明确保存方式的旧任务仍按上面的兼容规则执行。明确选择 Catalog 的任务预检或执行失败时会报错，不会自动切换成外部表或普通 JDBC。

#### MySQL / TiDB 外部表导入

源数据源为 **MySQL 或 TiDB**、目标为 **StarRocks**、同步模式为**初始化同步**时，开启“数据湖导入”并选择 **外部表**，沿用已在 StarRocks 3.1.3 使用的原生方式。未保存明确方式、且未填写 Catalog 名称的旧任务也继续走此路径：

1. 从 MySQL / TiDB 读取实际源表的字段结构、时间范围，并直接查询源表准确行数。配置时间戳字段时，使用 `SELECT COUNT(*) FROM 源库.源表 WHERE 时间戳字段 IS NOT NULL` 统计需要初始化的有效行数；未配置时间戳字段时统计整表行数。在 StarRocks 的 `easyreport_import_tmp` 专用库中创建本次运行独占的 `ENGINE=MYSQL` 外部表。计数和预检都在清空目标表前完成，计数失败时保留原目标数据。
2. 校验外部表可读后，按原初始化语义清空目标表。
3. 使用显式列名，由 StarRocks 直接读取源库。任务可配置整批导入阈值（单位万条，正整数，默认 **50**）和时间窗口（**年 / 月 / 日**，默认**年**）。有效数据少于阈值时，执行一次 `INSERT INTO 目标表 (...) SELECT ... FROM 外部表 WHERE 时间戳字段 IS NOT NULL`；达到阈值（包含相等）时，按选定的自然时间窗口串行执行 `INSERT INTO ... SELECT ... WHERE 时间戳字段 >= 开始时间 AND 时间戳字段 < 结束时间`。年从 1 月 1 日开始，月从当月 1 日开始，日从零点开始，所有窗口都是左闭右开区间，支持跨年、不同月长和闰日。配置了时间戳字段的任务不初始化 NULL 时间戳记录，不再补导 NULL；时间戳全为空或有效记录为零时，不执行 INSERT，也不生成时间戳断点。未配置时间戳字段时仍按整表导入，不进行时间分窗。
4. 完成或失败后删除本次外部表，专用库保留供后续任务使用。

使用外部表时，数据无需经过 EasyReport 逐行搬运，也不受“批次大小”的 3000 行等设置限制。开启后，界面隐藏“批次大小”，显示可编辑的导入策略；关闭后恢复原批次大小，已保存的阈值和窗口也会保留。外部表不支持当前源配置而回退 JDBC 时，继续使用原批次大小和按天读取方式，不会自动尝试 Catalog。多库、多表仍按现有配置逐表执行；增量同步继续使用原有方式。

任务开关保存在 EasyReport 配置库的 `er_data_transfer.external_table_enabled` 字段（默认 `0`）。整批阈值和窗口分别保存在 `external_table_threshold_wan` 和 `external_table_window_unit`，旧任务缺省按 50 万条、按年处理。编辑和复制任务会保留各自的策略；运行状态、进度等更新不会覆盖策略。

外部表开关和导入策略分别由 `upgrade_20261022_data_transfer_external_table.sql`、`upgrade_20261023_data_transfer_external_policy.sql` 提供。已具备这些字段的环境无需为策略重复升级；双模式所需 Catalog 名称和导入方式字段按前文升级。正常升级不执行相应回滚脚本。

临时外部表使用 **EasyReport 源数据源中保存的主机和端口**，不使用 Catalog 的内网连接地址；运行前需要 StarRocks 各读取节点能访问该 MySQL / TiDB 地址。外部表无需 BE 的 Java 环境和 JDBC 驱动。StarRocks 账号需具备专用库/外部表的创建、读取和删除权限，以及目标表的写入、清空权限；源账号需具备读取源表和元数据的权限。字段类型必须可映射为 StarRocks 3.1.3 支持的类型。BIT、二进制、TIME、精度超过 38 的 DECIMAL 等类型，以及无法继承的 TLS、独立会话设置或时区设置，会在接触目标表前回退原 JDBC 方式，并在进度信息中说明原因。外部表建表、可读校验或目标 INSERT 预规划失败时会报错，保留尚未清空的目标数据。日期时间精度受目标字段类型限制。

MySQL / TiDB 的 JSON 字段不再直接触发 JDBC 回退。临时外表以 `VARCHAR(1048576)` 承接 JSON 原文：目标为 JSON 列时显式解析并保留对象、数组、字符串、数字、布尔值及 JSON `null` 的类型；目标为 VARCHAR / STRING 列时写入 JSON 文本。SQL NULL 与 JSON `null` 分开处理，时间戳为 NULL 的记录仍不参与初始化。目标列类型和转换函数在清空目标表前校验；不兼容的目标类型直接报错。

JSON 表的源端计数会同时聚合 JSON 原文按 UTF-8 编码的最大字节长度，结果只返回计数和长度，不通过外表扫描来计数。受 StarRocks 3.1.3 字符串表达式上限影响，写 JSON 列的单个原文最大为 1,048,574 字节，写文本列最大为 1,048,576 字节且不能超过目标声明长度。超过外表承接能力时在清空目标表前回退 JDBC；超过目标文本列实际容量时直接报错，保留目标数据，不截断 JSON。导入期间转换失败会终止该次 INSERT，不把解析失败默默写成 NULL。该统计需要在源库读取 JSON 列。目标类型从 `DESCRIBE` 获取，不依赖 3.1.3 的 MySQL 协议对 JSON 列的字符串类型报告。转换针对 3.1.3 的标量解析行为采用数组包装后提取，见 [JSON 解析源码](https://github.com/StarRocks/starrocks/blob/3.1.3/be/src/util/json.cpp) 和 [JSON 函数源码](https://github.com/StarRocks/starrocks/blob/3.1.3/be/src/exprs/json_functions.cpp)。

每个任务的专用 StarRocks 连接会启用严格导入，并将会话 `query_timeout` 至少设为 3600 秒；如果原值更大则保留。该设置只作用于当前连接，不修改服务器全局配置。单个导入窗口超时仍会失败，目标 JDBC 连接的自定义 socket 超时也可能先触发。

准确计数和分窗导入都会读取生产源库；COUNT(*) 在大表上也可能耗时。年窗口减少导入次数，但单次扫描和写入量较大；月、日窗口单次数据量更小，执行次数更多。上线前应检查外部扫描的执行计划，确认日期范围已下推，并检查 MySQL / TiDB 实际执行计划是否使用合适的索引。建议先选择一张代表性表测试，对比耗时、源库负载和导入行数，并检查 NULL、特殊字符、日期边界及数值范围。StarRocks 3.1.3 原生 MYSQL 扫描器对部分特殊值存在解析边界，例如可空字符串的字面 `\N` 会被识别为 NULL，不能仅以行数判断所有字段内容都正确。

应用不会打印包含源密码的外表建表语句，驱动错误也会移除原始 SQL 和异常链；但 StarRocks 3.1.3 原生 MYSQL 扫描器在源库连接失败时可能将连接信息写入 BE 日志。建议沿用专用只读源账号，并限制 StarRocks 元数据及服务端日志的访问权限。此行为见 [3.1.3 MYSQL 扫描器源码](https://github.com/StarRocks/starrocks/blob/3.1.3/be/src/exec/mysql_scanner.cpp)。

进度仍在整张表成功后保存。点击停止后先进入“停止中”，执行器向当前 JDBC 计数、查询或导入提交取消请求，并在数据读取、写入批次和时间窗口中检查停止，随后清理本次外部表及关闭连接；后台执行结束后才显示“已停止”。停止中的任务不能重新启动，未完成的当前表不会被标记完成或推进断点。数据库取消响应、网络异常或驱动取消阻塞可能延长“停止中”，界面会继续轮询，不会提前宣称结束。

停止或失败前已经提交的数据不会整体回滚；重新执行全量初始化会清空目标表后重新导入。过滤 NULL 时间戳后没有有效记录时，也保留该清空目标表的初始化语义。如果进程被强制结束，或清理操作本身失败，可能留下本次运行的外部表；确认任务已经停止后可按日志中的独占表名清理。清理外部表只移除 StarRocks 中的外部映射。

### 增量同步

基于时间戳字段，仅同步自上次同步以来新增或修改的数据。适用于：
- 持续性的数据同步
- 数据量较大的表
- 减少网络和数据库开销

> **注意**: 建议源表配置一个非空时间戳字段（如 `update_time`）。普通 JDBC 初始化用它按天分片，外部表有效记录达到任务阈值时用它按所选年、月、日分片，增量同步用它判断新增或修改的数据。已配置时间戳字段时，普通 JDBC、外部表及其 JDBC 回退路径均跳过 NULL 时间戳数据；时间戳范围为空时不再回退全表导入。未配置时间戳字段仍执行全表导入。

## 创建传输任务

1. 进入 **数据传输** 菜单
2. 点击 **新增** 按钮
3. 配置以下参数：

| 参数 | 说明 |
|------|------|
| 任务名称 | 自定义名称 |
| 源数据源 | 选择数据来源 |
| 源表 | 源数据库中的表名 |
| 目标数据源 | 选择数据目标 |
| 目标表 | 目标数据库中的表名 |
| 同步模式 | 全量同步 / 增量同步 |
| 数据湖导入 | 默认关闭，支持的源数据库 → StarRocks 初始化任务可手动开启 |
| 导入方式 | 新任务默认 JDBC Catalog；MySQL / TiDB 可选外部表，其余支持的源仅可选 JDBC Catalog；编辑和复制保留原方式 |
| Catalog 名称 | 仅选择 JDBC Catalog 时显示并必填，如 `src_mysql`；外部表不使用此名称 |
| 导入策略 | 两种方式开启时均显示：有效数据少于指定万条数时整批导入，否则按年、月或日导入；默认 50 万条、年 |
| 时间戳字段 | 普通 JDBC 初始化按天分片、外部表大表初始化按所选时间窗口分片，以及增量同步判断变化的依据字段 |

## 操作控制

数据传输支持精细化的操作控制：

- **同步插入 (INSERT)**: 同步新增的数据行
- **同步更新 (UPDATE)**: 同步修改过的数据行
- **同步删除 (DELETE)**: 同步已删除的数据行
- **删除保护窗口**: 设置保护期（年）进行性能保护

## 批次配置

通过 **批次大小** 参数控制每次同步的数据量：

- 默认值：1000 行/批次
- 较大的批次可提升吞吐量，但占用更多内存
- 较小的批次更稳定，适合低配环境

数据湖导入的外部表和 JDBC Catalog 两种方式均不使用这个批次设置，界面会显示导入策略并隐藏批次大小。关闭数据湖导入后恢复原值；外部表 JDBC 回退同样使用保留的批次大小。

## UPSERT 机制

系统针对不同数据库实现了 UPSERT（INSERT OR UPDATE）逻辑：

| 数据库 | 实现方式 |
|--------|---------|
| MySQL | `INSERT ... ON DUPLICATE KEY UPDATE` |
| PostgreSQL | `INSERT ... ON CONFLICT DO UPDATE` |
| SQL Server | `MERGE INTO ... WITH (HOLDLOCK)` |

## 定时调度

配置 Cron 表达式实现自动定时同步：

```
# 每天凌晨 2 点执行
0 0 2 * * ?

# 每 30 分钟执行一次
0 */30 * * * ?

# 每天 8:00 和 20:00 执行
0 0 8,20 * * ?
```
