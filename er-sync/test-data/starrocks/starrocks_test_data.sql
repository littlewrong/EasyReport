-- Generated from test-data/mysql/mysql_test_data.sql for StarRocks.
-- ============================================================
-- 1. test_user - 用户表（常规业务表，覆盖主流字段类型）
-- ============================================================
DROP TABLE IF EXISTS test_user;
CREATE TABLE test_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username STRING NOT NULL,
    password STRING NOT NULL,
    email STRING,
    phone STRING,
    age SMALLINT DEFAULT 0,
    gender SMALLINT DEFAULT 0,
    balance DECIMAL(12,2) DEFAULT 0.00,
    score FLOAT DEFAULT 0,
    rating DOUBLE DEFAULT 0.0,
    is_active BOOLEAN DEFAULT TRUE,
    avatar STRING,
    birthday DATE,
    login_time DATETIME,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark STRING,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_user (username, password, email, phone, age, gender, balance, score, rating, is_active, avatar, birthday, login_time, remark) VALUES
('alice', 'e10adc3949ba59abbe56e057f20f883e', 'alice@test.com', '13800001001', 28, 2, 15000.50, 88.5, 4.8, TRUE, '/avatar/alice.jpg', '1998-03-15', '2026-03-01 09:30:00', '管理员用户'),
('bob', 'e10adc3949ba59abbe56e057f20f883e', 'bob@test.com', '13800001002', 35, 1, 8200.00, 72.0, 4.2, TRUE, '/avatar/bob.jpg', '1991-07-22', '2026-03-02 14:20:00', '普通用户'),
('charlie', 'e10adc3949ba59abbe56e057f20f883e', 'charlie@test.com', '13800001003', 42, 1, 320.75, 95.3, 4.9, TRUE, NULL, '1984-11-08', '2026-02-28 18:00:00', NULL),
('diana', 'e10adc3949ba59abbe56e057f20f883e', 'diana@test.com', NULL, 22, 2, 0.00, 10.0, 3.0, FALSE, '/avatar/diana.jpg', '2004-01-30', NULL, '已停用账户'),
('eric', 'e10adc3949ba59abbe56e057f20f883e', NULL, '13800001005', 30, 1, 99999.99, 100.0, 5.0, TRUE, '/avatar/eric.jpg', '1996-06-18', '2026-03-05 08:00:00', '测试包含''单引号和"双引号"的备注'),
('fiona', 'e10adc3949ba59abbe56e057f20f883e', 'fiona@test.com', '13800001006', 0, 0, 500.00, 0.0, 0.0, TRUE, NULL, NULL, NULL, ''),
('grace', 'e10adc3949ba59abbe56e057f20f883e', 'grace@test.com', '13800001007', 55, 2, 128000.00, 88.8, 4.5, TRUE, '/avatar/grace.jpg', '1971-12-25', '2026-03-06 10:00:00', '高净值用户'),
('henry', 'e10adc3949ba59abbe56e057f20f883e', 'henry@test.com', '13800001008', 18, 1, 50.00, 5.5, 1.0, TRUE, NULL, '2008-09-01', '2026-01-01 00:00:00', '新注册用户'),
('ivy', 'e10adc3949ba59abbe56e057f20f883e', 'ivy@test.com', '13800001009', 33, 2, 7777.77, 66.6, 3.8, TRUE, '/avatar/ivy.jpg', '1993-04-10', '2026-03-04 16:45:00', '测试中文备注：这是一段中文说明\n包含换行符'),
('jack', 'e10adc3949ba59abbe56e057f20f883e', 'jack@test.com', '13800001010', 45, 1, 25000.00, 55.0, 4.0, FALSE, NULL, '1981-08-20', '2025-12-31 23:59:59', '停用用户');


-- ============================================================
-- 2. test_order - 订单表（大数值、复合索引、NULL较多）
-- ============================================================
DROP TABLE IF EXISTS test_order;
CREATE TABLE test_order (
    order_id BIGINT NOT NULL AUTO_INCREMENT,
    order_no STRING NOT NULL,
    user_id BIGINT NOT NULL,
    product_name STRING NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price DECIMAL(10,2) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    discount DECIMAL(5,4) DEFAULT 1.0000,
    status TINYINT NOT NULL DEFAULT 0,
    pay_time DATETIME,
    ship_time DATETIME,
    finish_time DATETIME,
    address STRING,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (order_id)
DISTRIBUTED BY HASH(order_id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_order (order_no, user_id, product_name, quantity, unit_price, total_amount, discount, status, pay_time, ship_time, finish_time, address) VALUES
('ORD20260301001', 1, '机械键盘 Cherry MX', 1, 899.00, 899.00, 1.0000, 3, '2026-03-01 10:00:00', '2026-03-02 08:00:00', '2026-03-05 14:00:00', '北京市朝阳区xxx路1号'),
('ORD20260301002', 1, '27寸4K显示器', 1, 2999.00, 2999.00, 0.9500, 3, '2026-03-01 10:05:00', '2026-03-02 08:00:00', '2026-03-06 10:00:00', '北京市朝阳区xxx路1号'),
('ORD20260301003', 2, 'USB-C扩展坞', 2, 199.00, 398.00, 1.0000, 2, '2026-03-01 11:00:00', '2026-03-03 09:00:00', NULL, '上海市浦东新区yyy路2号'),
('ORD20260301004', 3, '无线鼠标', 1, 149.00, 149.00, 0.8000, 1, '2026-03-01 14:30:00', NULL, NULL, '广州市天河区zzz路3号'),
('ORD20260302001', 4, 'MacBook Pro 14寸 M3', 1, 14999.00, 14999.00, 1.0000, 0, NULL, NULL, NULL, NULL),
('ORD20260302002', 5, 'AirPods Pro 2', 1, 1799.00, 1799.00, 0.9000, 4, NULL, NULL, NULL, '深圳市南山区aaa路5号'),
('ORD20260302003', 1, '数据线Type-C 2m', 5, 29.90, 149.50, 1.0000, 3, '2026-03-02 09:00:00', '2026-03-02 14:00:00', '2026-03-04 16:00:00', '北京市朝阳区xxx路1号'),
('ORD20260303001', 6, '办公椅人体工学', 1, 3599.00, 3599.00, 0.8500, 2, '2026-03-03 08:00:00', '2026-03-04 10:00:00', NULL, '杭州市西湖区bbb路6号'),
('ORD20260303002', 7, '机械硬盘 4TB', 2, 599.00, 1198.00, 0.9800, 1, '2026-03-03 16:00:00', NULL, NULL, '成都市锦江区ccc路7号'),
('ORD20260303003', 2, '显示器支架', 1, 259.00, 259.00, 1.0000, 0, NULL, NULL, NULL, NULL),
('ORD20260304001', 8, '蓝牙键盘', 1, 399.00, 399.00, 1.0000, 3, '2026-03-04 07:30:00', '2026-03-04 15:00:00', '2026-03-06 09:00:00', '武汉市武昌区ddd路8号'),
('ORD20260304002', 9, '笔记本内存条 DDR5 16GB', 2, 450.00, 900.00, 0.9500, 2, '2026-03-04 11:00:00', '2026-03-05 08:00:00', NULL, '南京市鼓楼区eee路9号'),
('ORD20260305001', 10, '固态硬盘 NVMe 2TB', 1, 1299.00, 1299.00, 0.9000, 1, '2026-03-05 13:00:00', NULL, NULL, '西安市雁塔区fff路10号'),
('ORD20260305002', 3, '散热器 360水冷', 1, 799.00, 799.00, 1.0000, 4, NULL, NULL, NULL, '广州市天河区zzz路3号'),
('ORD20260306001', 5, '电竞显示器 27寸 240Hz', 1, 4299.00, 4299.00, 0.9200, 0, NULL, NULL, NULL, '深圳市南山区aaa路5号');


-- ============================================================
-- 3. test_numeric - 数值类型全覆盖表
-- ============================================================
DROP TABLE IF EXISTS test_numeric;
CREATE TABLE test_numeric (
    id BIGINT NOT NULL AUTO_INCREMENT,
    col_tinyint TINYINT,
    col_tinyint_u SMALLINT,
    col_smallint SMALLINT,
    col_mediumint INT,
    col_int INT,
    col_int_u BIGINT,
    col_bigint BIGINT,
    col_float FLOAT,
    col_float_p FLOAT,
    col_double DOUBLE,
    col_decimal_5_2 DECIMAL(5,2),
    col_decimal_18_6 DECIMAL(18,6),
    col_decimal_38_10 DECIMAL(38,10),
    col_bit BOOLEAN,
    col_bit8 INT,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_numeric (id, col_tinyint, col_tinyint_u, col_smallint, col_mediumint, col_int, col_int_u, col_bigint, col_float, col_float_p, col_double, col_decimal_5_2, col_decimal_18_6, col_decimal_38_10, col_bit, col_bit8, time_stamp) VALUES
(1, 127, 255, 32767, 8388607, 2147483647, 4294967295, 9223372036854775807, 3.14, 1234567.890, 3.141592653589793, 999.99, 123456789012.345678, 12345678901234567890.1234567890, TRUE, 255, CURRENT_TIMESTAMP),
(2, -128, 0, -32768, -8388608, -2147483648, 0, -9223372036854775808, -3.14, -1234567.890, -3.141592653589793, -999.99, -123456789012.345678, -12345678901234567890.1234567890, FALSE, 0, CURRENT_TIMESTAMP),
(3, 0, 128, 0, 0, 0, 2147483648, 0, 0.0, 0.000, 0.0, 0.00, 0.000000, 0.0000000000, FALSE, 170, CURRENT_TIMESTAMP),
(4, 1, 1, 1, 1, 1, 1, 1, 1.0, 1.000, 1.0, 1.00, 1.000000, 1.0000000000, TRUE, 1, CURRENT_TIMESTAMP),
(5, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP);


-- ============================================================
-- 4. test_string - 字符串和文本类型全覆盖表
-- ============================================================
DROP TABLE IF EXISTS test_string;
CREATE TABLE test_string (
    id BIGINT NOT NULL AUTO_INCREMENT,
    col_char_1 STRING,
    col_char_50 STRING,
    col_varchar_10 STRING,
    col_varchar_255 STRING,
    col_varchar_2000 STRING,
    col_tinytext STRING,
    col_text STRING,
    col_mediumtext STRING,
    col_longtext STRING,
    col_enum VARCHAR(255) DEFAULT 'A',
    col_set VARCHAR(1024),
    col_json JSON,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_string (id, col_char_1, col_char_50, col_varchar_10, col_varchar_255, col_varchar_2000, col_tinytext, col_text, col_mediumtext, col_longtext, col_enum, col_set, col_json, time_stamp) VALUES
(1, 'A', 'Hello World', 'short', '这是一段中文VARCHAR(255)测试', '这是VARCHAR(2000)的长文本测试，包含特殊字符：!@#$%^&*()_+-=[]{}|;:,.<>?/', 'TinyText内容', 'TEXT类型的内容，可以存储较长的文本。This is a text column for testing.', 'MediumText：中等长度文本内容', 'LongText：超长文本内容，用于测试大文本字段的同步', 'A', 'read,write', parse_json('{"name":"test","value":123,"nested":{"key":"val"}}'), CURRENT_TIMESTAMP),
(2, 'B', '', '', '', '', '', '', '', '', 'B', 'exec', parse_json('[]'), CURRENT_TIMESTAMP),
(3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'C', NULL, NULL, CURRENT_TIMESTAMP),
(4, 'Z', '  spaces  ', '带空格', '包含\t制表符的文本', '包含\n换行\n多行文本', 'Tiny', '包含emoji：测试数据', 'Medium', 'Long', 'D', 'read,write,exec', parse_json('{"arr":[1,2,3],"bool":true,"null_val":null}'), CURRENT_TIMESTAMP),
(5, '中', '中文字符测试', '中文短串', '日本語テスト Korean 한국어 Mixed 混合', 'Très bien! Ñoño Ünïcödë', 'Tiny中文', '中文Text内容', '中文Medium内容', '中文Long内容，测试各种字符编码的兼容性', 'A', 'read', parse_json('{"unicode":"中文","emoji":"ok"}'), CURRENT_TIMESTAMP);


-- ============================================================
-- 5. test_datetime - 日期时间类型全覆盖表
-- ============================================================
DROP TABLE IF EXISTS test_datetime;
CREATE TABLE test_datetime (
    id BIGINT NOT NULL AUTO_INCREMENT,
    col_date DATE,
    col_time STRING,
    col_datetime DATETIME,
    col_datetime_3 DATETIME,
    col_datetime_6 DATETIME,
    col_timestamp DATETIME,
    col_year SMALLINT,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_datetime (id, col_date, col_time, col_datetime, col_datetime_3, col_datetime_6, col_timestamp, col_year, time_stamp) VALUES
(1, '2026-03-06', '14:30:00', '2026-03-06 14:30:00', '2026-03-06 14:30:00.123', '2026-03-06 14:30:00.123456', '2026-03-06 14:30:00', 2026, CURRENT_TIMESTAMP),
(2, '2000-01-01', '00:00:00', '2000-01-01 00:00:00', '2000-01-01 00:00:00.000', '2000-01-01 00:00:00.000000', '2000-01-01 00:00:00', 2000, CURRENT_TIMESTAMP),
(3, '1970-01-01', '23:59:59', '1999-12-31 23:59:59', '1999-12-31 23:59:59.999', '1999-12-31 23:59:59.999999', '1970-01-02 00:00:00', 1970, CURRENT_TIMESTAMP),
(4, '2099-12-31', '12:00:00', '2099-12-31 12:00:00', '2099-12-31 12:00:00.500', '2099-12-31 12:00:00.500000', '2038-01-18 00:00:00', 2099, CURRENT_TIMESTAMP),
(5, NULL, NULL, NULL, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP);


-- ============================================================
-- 6. test_binary - 二进制类型表
-- ============================================================
DROP TABLE IF EXISTS test_binary;
CREATE TABLE test_binary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    col_binary_16 STRING,
    col_varbinary STRING,
    col_tinyblob STRING,
    col_blob STRING,
    col_mediumblob STRING,
    col_longblob STRING,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_binary (id, col_binary_16, col_varbinary, col_tinyblob, col_blob, col_mediumblob, col_longblob, time_stamp) VALUES
(1, '550E8400E29B41D4A716446655440000', 'DEADBEEF', '48656C6C6F', '48656C6C6F20576F726C64', 'CAFEBABE', '0102030405060708090A', CURRENT_TIMESTAMP),
(2, '00000000000000000000000000000000', '00', '00', '00', '00', '00', CURRENT_TIMESTAMP),
(3, NULL, NULL, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP);


-- ============================================================
-- 7. test_composite_pk - 复合主键表（测试多列主键同步）
-- ============================================================
DROP TABLE IF EXISTS test_composite_pk;
CREATE TABLE test_composite_pk (
    tenant_id INT NOT NULL,
    record_id BIGINT NOT NULL,
    record_type VARCHAR(20) NOT NULL,
    value STRING,
    sort_order INT DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (tenant_id, record_id, record_type)
DISTRIBUTED BY HASH(tenant_id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_composite_pk (tenant_id, record_id, record_type, value, sort_order) VALUES
(1, 1001, 'CONFIG', '系统配置项A', 1),
(1, 1001, 'PARAM', '系统参数A', 2),
(1, 1002, 'CONFIG', '系统配置项B', 3),
(2, 1001, 'CONFIG', '租户2配置项A', 1),
(2, 1001, 'PARAM', '租户2参数A', 2),
(2, 2001, 'LOG', '操作日志记录', 10),
(3, 1001, 'CONFIG', '租户3配置', 1),
(3, 3001, 'DATA', '业务数据', 5);


-- ============================================================
-- 8. test_default_values - 默认值测试表
-- ============================================================
DROP TABLE IF EXISTS test_default_values;
CREATE TABLE test_default_values (
    id BIGINT NOT NULL AUTO_INCREMENT,
    col_default_str STRING NOT NULL DEFAULT 'hello',
    col_default_int INT NOT NULL DEFAULT 42,
    col_default_dec DECIMAL(8,2) NOT NULL DEFAULT 3.14,
    col_default_ts DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    col_not_null STRING NOT NULL,
    col_nullable STRING,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_default_values (col_not_null, col_nullable) VALUES
('必填值1', '可选值1'),
('必填值2', NULL),
('必填值3', ''),
('必填值4', '测试默认值是否正确同步');


-- ============================================================
-- 9. test_wide_table - 宽表（多字段，模拟真实报表场景）
-- ============================================================
DROP TABLE IF EXISTS test_wide_table;
CREATE TABLE test_wide_table (
    id VARCHAR(36) NOT NULL,
    company STRING NOT NULL,
    department STRING,
    employee STRING NOT NULL,
    title STRING,
    salary DECIMAL(10,2),
    bonus DECIMAL(10,2) DEFAULT 0.00,
    tax_rate DECIMAL(5,4) DEFAULT 0.0000,
    hire_date DATE,
    contract_end DATE,
    work_years SMALLINT DEFAULT 0,
    level TINYINT DEFAULT 1,
    is_manager BOOLEAN DEFAULT FALSE,
    kpi_score FLOAT,
    office_addr STRING,
    memo STRING,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

INSERT INTO test_wide_table (id, company, department, employee, title, salary, bonus, tax_rate, hire_date, contract_end, work_years, level, is_manager, kpi_score, office_addr, memo) VALUES
('00000000-0000-0000-0000-000000000001', '科技公司A', '研发部', '张三', '高级工程师', 25000.00, 5000.00, 0.2000, '2020-03-15', '2027-03-14', 6, 7, FALSE, 92.5, '北京总部3层', '核心骨干'),
('00000000-0000-0000-0000-000000000002', '科技公司A', '研发部', '李四', '技术总监', 45000.00, 15000.00, 0.3000, '2018-06-01', '2026-05-31', 8, 9, TRUE, 95.0, '北京总部3层', '部门负责人'),
('00000000-0000-0000-0000-000000000003', '科技公司A', '产品部', '王五', '产品经理', 20000.00, 3000.00, 0.2000, '2022-01-10', '2028-01-09', 4, 6, FALSE, 88.0, '北京总部5层', NULL),
('00000000-0000-0000-0000-000000000004', '科技公司A', '市场部', '赵六', '市场总监', 35000.00, 10000.00, 0.2500, '2019-09-01', '2026-08-31', 7, 8, TRUE, 90.0, '上海分部2层', '华东区负责人'),
('00000000-0000-0000-0000-000000000005', '科技公司B', '研发部', '孙七', '初级工程师', 12000.00, 1000.00, 0.1000, '2024-07-01', '2027-06-30', 2, 3, FALSE, 78.0, '深圳总部6层', '应届毕业生'),
('00000000-0000-0000-0000-000000000006', '科技公司B', '研发部', '周八', '架构师', 50000.00, 20000.00, 0.3500, '2016-03-01', '2026-02-28', 10, 10, TRUE, 98.0, '深圳总部6层', 'CTO候选人'),
('00000000-0000-0000-0000-000000000007', '科技公司B', '财务部', '吴九', '财务主管', 18000.00, 2000.00, 0.1500, '2021-11-15', '2027-11-14', 5, 5, FALSE, 85.0, '深圳总部4层', NULL),
('00000000-0000-0000-0000-000000000008', '科技公司B', '人事部', '郑十', 'HR经理', 22000.00, 4000.00, 0.2000, '2020-08-20', '2026-08-19', 6, 6, TRUE, 87.5, '深圳总部4层', '负责招聘'),
('00000000-0000-0000-0000-000000000009', '外企C', '工程部', 'Tom', 'Senior Dev', 35000.00, 8000.00, 0.2500, '2019-04-01', '2026-03-31', 7, 8, FALSE, 91.0, '上海CBD', 'Expat'),
('00000000-0000-0000-0000-000000000010', '外企C', '工程部', 'Jerry', 'Tech Lead', 42000.00, 12000.00, 0.3000, '2017-10-01', '2026-09-30', 9, 9, TRUE, 94.0, '上海CBD', 'Team of 8');


-- ============================================================
-- 10. test_empty - 空表（测试空表同步）
-- ============================================================
DROP TABLE IF EXISTS test_empty;
CREATE TABLE test_empty (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name STRING,
    value DECIMAL(10,2),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    time_stamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=OLAP
PRIMARY KEY (id)
DISTRIBUTED BY HASH(id) BUCKETS 3
PROPERTIES ("replication_num" = "1");

-- 不插入数据，用于测试空表同步


-- ============================================================
-- 验证
SELECT '=== 测试数据准备完成 ===' AS message;
SHOW TABLES LIKE 'test%';
