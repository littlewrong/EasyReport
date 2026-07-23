-- Generated from test-data/mysql/mysql_test_data.sql for SQL Server.
-- ============================================================
-- 1. test_user - 用户表（常规业务表，覆盖主流字段类型）
-- ============================================================
IF OBJECT_ID(N'dbo.test_user', N'U') IS NOT NULL DROP TABLE [dbo].[test_user];
CREATE TABLE [dbo].[test_user] (
    [id] BIGINT IDENTITY(1,1) NOT NULL,
    [username] NVARCHAR(50) NOT NULL,
    [password] NVARCHAR(128) NOT NULL,
    [email] NVARCHAR(100),
    [phone] NCHAR(11),
    [age] SMALLINT DEFAULT 0,
    [gender] SMALLINT DEFAULT 0,
    [balance] DECIMAL(12,2) DEFAULT 0.00,
    [score] FLOAT DEFAULT 0,
    [rating] FLOAT DEFAULT 0.0,
    [is_active] BIT DEFAULT 1,
    [avatar] NVARCHAR(255),
    [birthday] DATE,
    [login_time] DATETIME2,
    [create_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [update_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [remark] NVARCHAR(MAX),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id]),
    CONSTRAINT [test_user_uk_username] UNIQUE ([username])
);
CREATE INDEX [test_user_idx_email] ON [dbo].[test_user] ([email]);
CREATE INDEX [test_user_idx_phone] ON [dbo].[test_user] ([phone]);
CREATE INDEX [test_user_idx_create_time] ON [dbo].[test_user] ([create_time]);

INSERT INTO [dbo].[test_user] ([username], [password], [email], [phone], [age], [gender], [balance], [score], [rating], [is_active], [avatar], [birthday], [login_time], [remark]) VALUES
(N'alice', N'e10adc3949ba59abbe56e057f20f883e', N'alice@test.com', N'13800001001', 28, 2, 15000.50, 88.5, 4.8, 1, N'/avatar/alice.jpg', N'1998-03-15', N'2026-03-01 09:30:00', N'管理员用户'),
(N'bob', N'e10adc3949ba59abbe56e057f20f883e', N'bob@test.com', N'13800001002', 35, 1, 8200.00, 72.0, 4.2, 1, N'/avatar/bob.jpg', N'1991-07-22', N'2026-03-02 14:20:00', N'普通用户'),
(N'charlie', N'e10adc3949ba59abbe56e057f20f883e', N'charlie@test.com', N'13800001003', 42, 1, 320.75, 95.3, 4.9, 1, NULL, N'1984-11-08', N'2026-02-28 18:00:00', NULL),
(N'diana', N'e10adc3949ba59abbe56e057f20f883e', N'diana@test.com', NULL, 22, 2, 0.00, 10.0, 3.0, 0, N'/avatar/diana.jpg', N'2004-01-30', NULL, N'已停用账户'),
(N'eric', N'e10adc3949ba59abbe56e057f20f883e', NULL, N'13800001005', 30, 1, 99999.99, 100.0, 5.0, 1, N'/avatar/eric.jpg', N'1996-06-18', N'2026-03-05 08:00:00', N'测试包含''单引号和"双引号"的备注'),
(N'fiona', N'e10adc3949ba59abbe56e057f20f883e', N'fiona@test.com', N'13800001006', 0, 0, 500.00, 0.0, 0.0, 1, NULL, NULL, NULL, N''),
(N'grace', N'e10adc3949ba59abbe56e057f20f883e', N'grace@test.com', N'13800001007', 55, 2, 128000.00, 88.8, 4.5, 1, N'/avatar/grace.jpg', N'1971-12-25', N'2026-03-06 10:00:00', N'高净值用户'),
(N'henry', N'e10adc3949ba59abbe56e057f20f883e', N'henry@test.com', N'13800001008', 18, 1, 50.00, 5.5, 1.0, 1, NULL, N'2008-09-01', N'2026-01-01 00:00:00', N'新注册用户'),
(N'ivy', N'e10adc3949ba59abbe56e057f20f883e', N'ivy@test.com', N'13800001009', 33, 2, 7777.77, 66.6, 3.8, 1, N'/avatar/ivy.jpg', N'1993-04-10', N'2026-03-04 16:45:00', N'测试中文备注：这是一段中文说明\n包含换行符'),
(N'jack', N'e10adc3949ba59abbe56e057f20f883e', N'jack@test.com', N'13800001010', 45, 1, 25000.00, 55.0, 4.0, 0, NULL, N'1981-08-20', N'2025-12-31 23:59:59', N'停用用户');


-- ============================================================
-- 2. test_order - 订单表（大数值、复合索引、NULL较多）
-- ============================================================
IF OBJECT_ID(N'dbo.test_order', N'U') IS NOT NULL DROP TABLE [dbo].[test_order];
CREATE TABLE [dbo].[test_order] (
    [order_id] BIGINT IDENTITY(1,1) NOT NULL,
    [order_no] NVARCHAR(32) NOT NULL,
    [user_id] BIGINT NOT NULL,
    [product_name] NVARCHAR(200) NOT NULL,
    [quantity] INT NOT NULL DEFAULT 1,
    [unit_price] DECIMAL(10,2) NOT NULL,
    [total_amount] DECIMAL(12,2) NOT NULL,
    [discount] DECIMAL(5,4) DEFAULT 1.0000,
    [status] SMALLINT NOT NULL DEFAULT 0,
    [pay_time] DATETIME2,
    [ship_time] DATETIME2,
    [finish_time] DATETIME2,
    [address] NVARCHAR(500),
    [create_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [update_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([order_id]),
    CONSTRAINT [test_order_uk_order_no] UNIQUE ([order_no])
);
CREATE INDEX [test_order_idx_user_id] ON [dbo].[test_order] ([user_id]);
CREATE INDEX [test_order_idx_status_create] ON [dbo].[test_order] ([status], [create_time]);
CREATE INDEX [test_order_idx_pay_time] ON [dbo].[test_order] ([pay_time]);

INSERT INTO [dbo].[test_order] ([order_no], [user_id], [product_name], [quantity], [unit_price], [total_amount], [discount], [status], [pay_time], [ship_time], [finish_time], [address]) VALUES
(N'ORD20260301001', 1, N'机械键盘 Cherry MX', 1, 899.00, 899.00, 1.0000, 3, N'2026-03-01 10:00:00', N'2026-03-02 08:00:00', N'2026-03-05 14:00:00', N'北京市朝阳区xxx路1号'),
(N'ORD20260301002', 1, N'27寸4K显示器', 1, 2999.00, 2999.00, 0.9500, 3, N'2026-03-01 10:05:00', N'2026-03-02 08:00:00', N'2026-03-06 10:00:00', N'北京市朝阳区xxx路1号'),
(N'ORD20260301003', 2, N'USB-C扩展坞', 2, 199.00, 398.00, 1.0000, 2, N'2026-03-01 11:00:00', N'2026-03-03 09:00:00', NULL, N'上海市浦东新区yyy路2号'),
(N'ORD20260301004', 3, N'无线鼠标', 1, 149.00, 149.00, 0.8000, 1, N'2026-03-01 14:30:00', NULL, NULL, N'广州市天河区zzz路3号'),
(N'ORD20260302001', 4, N'MacBook Pro 14寸 M3', 1, 14999.00, 14999.00, 1.0000, 0, NULL, NULL, NULL, NULL),
(N'ORD20260302002', 5, N'AirPods Pro 2', 1, 1799.00, 1799.00, 0.9000, 4, NULL, NULL, NULL, N'深圳市南山区aaa路5号'),
(N'ORD20260302003', 1, N'数据线Type-C 2m', 5, 29.90, 149.50, 1.0000, 3, N'2026-03-02 09:00:00', N'2026-03-02 14:00:00', N'2026-03-04 16:00:00', N'北京市朝阳区xxx路1号'),
(N'ORD20260303001', 6, N'办公椅人体工学', 1, 3599.00, 3599.00, 0.8500, 2, N'2026-03-03 08:00:00', N'2026-03-04 10:00:00', NULL, N'杭州市西湖区bbb路6号'),
(N'ORD20260303002', 7, N'机械硬盘 4TB', 2, 599.00, 1198.00, 0.9800, 1, N'2026-03-03 16:00:00', NULL, NULL, N'成都市锦江区ccc路7号'),
(N'ORD20260303003', 2, N'显示器支架', 1, 259.00, 259.00, 1.0000, 0, NULL, NULL, NULL, NULL),
(N'ORD20260304001', 8, N'蓝牙键盘', 1, 399.00, 399.00, 1.0000, 3, N'2026-03-04 07:30:00', N'2026-03-04 15:00:00', N'2026-03-06 09:00:00', N'武汉市武昌区ddd路8号'),
(N'ORD20260304002', 9, N'笔记本内存条 DDR5 16GB', 2, 450.00, 900.00, 0.9500, 2, N'2026-03-04 11:00:00', N'2026-03-05 08:00:00', NULL, N'南京市鼓楼区eee路9号'),
(N'ORD20260305001', 10, N'固态硬盘 NVMe 2TB', 1, 1299.00, 1299.00, 0.9000, 1, N'2026-03-05 13:00:00', NULL, NULL, N'西安市雁塔区fff路10号'),
(N'ORD20260305002', 3, N'散热器 360水冷', 1, 799.00, 799.00, 1.0000, 4, NULL, NULL, NULL, N'广州市天河区zzz路3号'),
(N'ORD20260306001', 5, N'电竞显示器 27寸 240Hz', 1, 4299.00, 4299.00, 0.9200, 0, NULL, NULL, NULL, N'深圳市南山区aaa路5号');


-- ============================================================
-- 3. test_numeric - 数值类型全覆盖表
-- ============================================================
IF OBJECT_ID(N'dbo.test_numeric', N'U') IS NOT NULL DROP TABLE [dbo].[test_numeric];
CREATE TABLE [dbo].[test_numeric] (
    [id] BIGINT IDENTITY(1,1) NOT NULL,
    [col_tinyint] SMALLINT,
    [col_tinyint_u] SMALLINT,
    [col_smallint] SMALLINT,
    [col_mediumint] INT,
    [col_int] INT,
    [col_int_u] BIGINT,
    [col_bigint] BIGINT,
    [col_float] FLOAT,
    [col_float_p] FLOAT,
    [col_double] FLOAT,
    [col_decimal_5_2] DECIMAL(5,2),
    [col_decimal_18_6] DECIMAL(18,6),
    [col_decimal_38_10] DECIMAL(38,10),
    [col_bit] BIT,
    [col_bit8] VARBINARY(8),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id])
);

SET IDENTITY_INSERT [dbo].[test_numeric] ON;
INSERT INTO [dbo].[test_numeric] ([id], [col_tinyint], [col_tinyint_u], [col_smallint], [col_mediumint], [col_int], [col_int_u], [col_bigint], [col_float], [col_float_p], [col_double], [col_decimal_5_2], [col_decimal_18_6], [col_decimal_38_10], [col_bit], [col_bit8], [time_stamp]) VALUES
(1, 127, 255, 32767, 8388607, 2147483647, 4294967295, 9223372036854775807, 3.14, 1234567.890, 3.141592653589793, 999.99, 123456789012.345678, 12345678901234567890.1234567890, 1, 0xFF, SYSDATETIME()),
(2, -128, 0, -32768, -8388608, -2147483648, 0, -9223372036854775808, -3.14, -1234567.890, -3.141592653589793, -999.99, -123456789012.345678, -12345678901234567890.1234567890, 0, 0x00, SYSDATETIME()),
(3, 0, 128, 0, 0, 0, 2147483648, 0, 0.0, 0.000, 0.0, 0.00, 0.000000, 0.0000000000, 0, 0xAA, SYSDATETIME()),
(4, 1, 1, 1, 1, 1, 1, 1, 1.0, 1.000, 1.0, 1.00, 1.000000, 1.0000000000, 1, 0x01, SYSDATETIME()),
(5, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, SYSDATETIME());
SET IDENTITY_INSERT [dbo].[test_numeric] OFF;


-- ============================================================
-- 4. test_string - 字符串和文本类型全覆盖表
-- ============================================================
IF OBJECT_ID(N'dbo.test_string', N'U') IS NOT NULL DROP TABLE [dbo].[test_string];
CREATE TABLE [dbo].[test_string] (
    [id] BIGINT IDENTITY(1,1) NOT NULL,
    [col_char_1] NCHAR(1),
    [col_char_50] NCHAR(50),
    [col_varchar_10] NVARCHAR(10),
    [col_varchar_255] NVARCHAR(255),
    [col_varchar_2000] NVARCHAR(2000),
    [col_tinytext] NVARCHAR(255),
    [col_text] NVARCHAR(MAX),
    [col_mediumtext] NVARCHAR(MAX),
    [col_longtext] NVARCHAR(MAX),
    [col_enum] VARCHAR(255) DEFAULT N'A',
    [col_set] VARCHAR(1024),
    [col_json] NVARCHAR(MAX),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id])
);

SET IDENTITY_INSERT [dbo].[test_string] ON;
INSERT INTO [dbo].[test_string] ([id], [col_char_1], [col_char_50], [col_varchar_10], [col_varchar_255], [col_varchar_2000], [col_tinytext], [col_text], [col_mediumtext], [col_longtext], [col_enum], [col_set], [col_json], [time_stamp]) VALUES
(1, N'A', N'Hello World', N'short', N'这是一段中文VARCHAR(255)测试', N'这是VARCHAR(2000)的长文本测试，包含特殊字符：!@#$%^&*()_+-=[]{}|;:,.<>?/', N'TinyText内容', N'TEXT类型的内容，可以存储较长的文本。This is a text column for testing.', N'MediumText：中等长度文本内容', N'LongText：超长文本内容，用于测试大文本字段的同步', N'A', N'read,write', N'{"name":"test","value":123,"nested":{"key":"val"}}', SYSDATETIME()),
(2, N'B', N'', N'', N'', N'', N'', N'', N'', N'', N'B', N'exec', N'[]', SYSDATETIME()),
(3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, N'C', NULL, NULL, SYSDATETIME()),
(4, N'Z', N'  spaces  ', N'带空格', N'包含\t制表符的文本', N'包含\n换行\n多行文本', N'Tiny', N'包含emoji：测试数据', N'Medium', N'Long', N'D', N'read,write,exec', N'{"arr":[1,2,3],"bool":true,"null_val":null}', SYSDATETIME()),
(5, N'中', N'中文字符测试', N'中文短串', N'日本語テスト Korean 한국어 Mixed 混合', N'Très bien! Ñoño Ünïcödë', N'Tiny中文', N'中文Text内容', N'中文Medium内容', N'中文Long内容，测试各种字符编码的兼容性', N'A', N'read', N'{"unicode":"中文","emoji":"ok"}', SYSDATETIME());
SET IDENTITY_INSERT [dbo].[test_string] OFF;


-- ============================================================
-- 5. test_datetime - 日期时间类型全覆盖表
-- ============================================================
IF OBJECT_ID(N'dbo.test_datetime', N'U') IS NOT NULL DROP TABLE [dbo].[test_datetime];
CREATE TABLE [dbo].[test_datetime] (
    [id] BIGINT IDENTITY(1,1) NOT NULL,
    [col_date] DATE,
    [col_time] TIME,
    [col_datetime] DATETIME2,
    [col_datetime_3] DATETIME2,
    [col_datetime_6] DATETIME2,
    [col_timestamp] DATETIME2,
    [col_year] SMALLINT,
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id])
);

SET IDENTITY_INSERT [dbo].[test_datetime] ON;
INSERT INTO [dbo].[test_datetime] ([id], [col_date], [col_time], [col_datetime], [col_datetime_3], [col_datetime_6], [col_timestamp], [col_year], [time_stamp]) VALUES
(1, N'2026-03-06', N'14:30:00', N'2026-03-06 14:30:00', N'2026-03-06 14:30:00.123', N'2026-03-06 14:30:00.123456', N'2026-03-06 14:30:00', 2026, SYSDATETIME()),
(2, N'2000-01-01', N'00:00:00', N'2000-01-01 00:00:00', N'2000-01-01 00:00:00.000', N'2000-01-01 00:00:00.000000', N'2000-01-01 00:00:00', 2000, SYSDATETIME()),
(3, N'1970-01-01', N'23:59:59', N'1999-12-31 23:59:59', N'1999-12-31 23:59:59.999', N'1999-12-31 23:59:59.999999', N'1970-01-02 00:00:00', 1970, SYSDATETIME()),
(4, N'2099-12-31', N'12:00:00', N'2099-12-31 12:00:00', N'2099-12-31 12:00:00.500', N'2099-12-31 12:00:00.500000', N'2038-01-18 00:00:00', 2099, SYSDATETIME()),
(5, NULL, NULL, NULL, NULL, NULL, NULL, NULL, SYSDATETIME());
SET IDENTITY_INSERT [dbo].[test_datetime] OFF;


-- ============================================================
-- 6. test_binary - 二进制类型表
-- ============================================================
IF OBJECT_ID(N'dbo.test_binary', N'U') IS NOT NULL DROP TABLE [dbo].[test_binary];
CREATE TABLE [dbo].[test_binary] (
    [id] BIGINT IDENTITY(1,1) NOT NULL,
    [col_binary_16] BINARY(16),
    [col_varbinary] VARBINARY(255),
    [col_tinyblob] VARBINARY(255),
    [col_blob] VARBINARY(MAX),
    [col_mediumblob] VARBINARY(MAX),
    [col_longblob] VARBINARY(MAX),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id])
);

SET IDENTITY_INSERT [dbo].[test_binary] ON;
INSERT INTO [dbo].[test_binary] ([id], [col_binary_16], [col_varbinary], [col_tinyblob], [col_blob], [col_mediumblob], [col_longblob], [time_stamp]) VALUES
(1, 0x550E8400E29B41D4A716446655440000, 0xDEADBEEF, 0x48656C6C6F, 0x48656C6C6F20576F726C64, 0xCAFEBABE, 0x0102030405060708090A, SYSDATETIME()),
(2, 0x00000000000000000000000000000000, 0x00, 0x00, 0x00, 0x00, 0x00, SYSDATETIME()),
(3, NULL, NULL, NULL, NULL, NULL, NULL, SYSDATETIME());
SET IDENTITY_INSERT [dbo].[test_binary] OFF;


-- ============================================================
-- 7. test_composite_pk - 复合主键表（测试多列主键同步）
-- ============================================================
IF OBJECT_ID(N'dbo.test_composite_pk', N'U') IS NOT NULL DROP TABLE [dbo].[test_composite_pk];
CREATE TABLE [dbo].[test_composite_pk] (
    [tenant_id] INT NOT NULL,
    [record_id] BIGINT NOT NULL,
    [record_type] NVARCHAR(20) NOT NULL,
    [value] NVARCHAR(500),
    [sort_order] INT DEFAULT 0,
    [create_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([tenant_id], [record_id], [record_type])
);
CREATE INDEX [test_composite_pk_idx_sort] ON [dbo].[test_composite_pk] ([sort_order]);

INSERT INTO [dbo].[test_composite_pk] ([tenant_id], [record_id], [record_type], [value], [sort_order]) VALUES
(1, 1001, N'CONFIG', N'系统配置项A', 1),
(1, 1001, N'PARAM', N'系统参数A', 2),
(1, 1002, N'CONFIG', N'系统配置项B', 3),
(2, 1001, N'CONFIG', N'租户2配置项A', 1),
(2, 1001, N'PARAM', N'租户2参数A', 2),
(2, 2001, N'LOG', N'操作日志记录', 10),
(3, 1001, N'CONFIG', N'租户3配置', 1),
(3, 3001, N'DATA', N'业务数据', 5);


-- ============================================================
-- 8. test_default_values - 默认值测试表
-- ============================================================
IF OBJECT_ID(N'dbo.test_default_values', N'U') IS NOT NULL DROP TABLE [dbo].[test_default_values];
CREATE TABLE [dbo].[test_default_values] (
    [id] BIGINT IDENTITY(1,1) NOT NULL,
    [col_default_str] NVARCHAR(50) NOT NULL DEFAULT N'hello',
    [col_default_int] INT NOT NULL DEFAULT 42,
    [col_default_dec] DECIMAL(8,2) NOT NULL DEFAULT 3.14,
    [col_default_ts] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [col_not_null] NVARCHAR(100) NOT NULL,
    [col_nullable] NVARCHAR(100),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id])
);

INSERT INTO [dbo].[test_default_values] ([col_not_null], [col_nullable]) VALUES
(N'必填值1', N'可选值1'),
(N'必填值2', NULL),
(N'必填值3', N''),
(N'必填值4', N'测试默认值是否正确同步');


-- ============================================================
-- 9. test_wide_table - 宽表（多字段，模拟真实报表场景）
-- ============================================================
IF OBJECT_ID(N'dbo.test_wide_table', N'U') IS NOT NULL DROP TABLE [dbo].[test_wide_table];
CREATE TABLE [dbo].[test_wide_table] (
    [id] NVARCHAR(36) NOT NULL DEFAULT CONVERT(NVARCHAR(36), NEWID()),
    [company] NVARCHAR(100) NOT NULL,
    [department] NVARCHAR(50),
    [employee] NVARCHAR(50) NOT NULL,
    [title] NVARCHAR(50),
    [salary] DECIMAL(10,2),
    [bonus] DECIMAL(10,2) DEFAULT 0.00,
    [tax_rate] DECIMAL(5,4) DEFAULT 0.0000,
    [hire_date] DATE,
    [contract_end] DATE,
    [work_years] SMALLINT DEFAULT 0,
    [level] SMALLINT DEFAULT 1,
    [is_manager] BIT DEFAULT 0,
    [kpi_score] FLOAT,
    [office_addr] NVARCHAR(200),
    [memo] NVARCHAR(MAX),
    [create_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [update_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id]),
    CONSTRAINT [test_wide_table_uk_company_employee] UNIQUE ([company], [employee])
);
CREATE INDEX [test_wide_table_idx_company_dept] ON [dbo].[test_wide_table] ([company], [department]);
CREATE INDEX [test_wide_table_idx_hire_date] ON [dbo].[test_wide_table] ([hire_date]);

INSERT INTO [dbo].[test_wide_table] ([id], [company], [department], [employee], [title], [salary], [bonus], [tax_rate], [hire_date], [contract_end], [work_years], [level], [is_manager], [kpi_score], [office_addr], [memo]) VALUES
(N'00000000-0000-0000-0000-000000000001', N'科技公司A', N'研发部', N'张三', N'高级工程师', 25000.00, 5000.00, 0.2000, N'2020-03-15', N'2027-03-14', 6, 7, 0, 92.5, N'北京总部3层', N'核心骨干'),
(N'00000000-0000-0000-0000-000000000002', N'科技公司A', N'研发部', N'李四', N'技术总监', 45000.00, 15000.00, 0.3000, N'2018-06-01', N'2026-05-31', 8, 9, 1, 95.0, N'北京总部3层', N'部门负责人'),
(N'00000000-0000-0000-0000-000000000003', N'科技公司A', N'产品部', N'王五', N'产品经理', 20000.00, 3000.00, 0.2000, N'2022-01-10', N'2028-01-09', 4, 6, 0, 88.0, N'北京总部5层', NULL),
(N'00000000-0000-0000-0000-000000000004', N'科技公司A', N'市场部', N'赵六', N'市场总监', 35000.00, 10000.00, 0.2500, N'2019-09-01', N'2026-08-31', 7, 8, 1, 90.0, N'上海分部2层', N'华东区负责人'),
(N'00000000-0000-0000-0000-000000000005', N'科技公司B', N'研发部', N'孙七', N'初级工程师', 12000.00, 1000.00, 0.1000, N'2024-07-01', N'2027-06-30', 2, 3, 0, 78.0, N'深圳总部6层', N'应届毕业生'),
(N'00000000-0000-0000-0000-000000000006', N'科技公司B', N'研发部', N'周八', N'架构师', 50000.00, 20000.00, 0.3500, N'2016-03-01', N'2026-02-28', 10, 10, 1, 98.0, N'深圳总部6层', N'CTO候选人'),
(N'00000000-0000-0000-0000-000000000007', N'科技公司B', N'财务部', N'吴九', N'财务主管', 18000.00, 2000.00, 0.1500, N'2021-11-15', N'2027-11-14', 5, 5, 0, 85.0, N'深圳总部4层', NULL),
(N'00000000-0000-0000-0000-000000000008', N'科技公司B', N'人事部', N'郑十', N'HR经理', 22000.00, 4000.00, 0.2000, N'2020-08-20', N'2026-08-19', 6, 6, 1, 87.5, N'深圳总部4层', N'负责招聘'),
(N'00000000-0000-0000-0000-000000000009', N'外企C', N'工程部', N'Tom', N'Senior Dev', 35000.00, 8000.00, 0.2500, N'2019-04-01', N'2026-03-31', 7, 8, 0, 91.0, N'上海CBD', N'Expat'),
(N'00000000-0000-0000-0000-000000000010', N'外企C', N'工程部', N'Jerry', N'Tech Lead', 42000.00, 12000.00, 0.3000, N'2017-10-01', N'2026-09-30', 9, 9, 1, 94.0, N'上海CBD', N'Team of 8');


-- ============================================================
-- 10. test_empty - 空表（测试空表同步）
-- ============================================================
IF OBJECT_ID(N'dbo.test_empty', N'U') IS NOT NULL DROP TABLE [dbo].[test_empty];
CREATE TABLE [dbo].[test_empty] (
    [id] BIGINT IDENTITY(1,1) NOT NULL,
    [name] NVARCHAR(100),
    [value] DECIMAL(10,2),
    [create_time] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    [time_stamp] DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    PRIMARY KEY ([id])
);

-- 不插入数据，用于测试空表同步


-- ============================================================
-- 验证
SELECT N'=== 测试数据准备完成 ===' AS [message];
SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'dbo' AND TABLE_NAME LIKE 'test%' ORDER BY TABLE_NAME;
