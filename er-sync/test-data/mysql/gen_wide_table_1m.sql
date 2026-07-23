-- ============================================================
-- 向 test_wide_table 批量插入 100 万条测试数据
-- 使用方法：在 synctest 库下执行此脚本
--   mysql -u root -p synctest < gen_wide_table_1m.sql
-- 或在客户端工具中切换到 synctest 库后执行
-- ============================================================

USE synctest;

-- 关闭严格模式，让 AUTO_INCREMENT 列可以被省略
SET SESSION sql_mode = 'NO_ENGINE_SUBSTITUTION';

DROP PROCEDURE IF EXISTS gen_wide_table_data;

DELIMITER $$

CREATE PROCEDURE gen_wide_table_data(IN total INT)
BEGIN
    DECLARE i       INT DEFAULT 1;
    DECLARE company VARCHAR(100);
    DECLARE dept    VARCHAR(50);
    DECLARE emp     VARCHAR(50);
    DECLARE title   VARCHAR(50);
    DECLARE salary  DECIMAL(10,2);
    DECLARE bonus   DECIMAL(10,2);
    DECLARE tax     DECIMAL(5,4);
    DECLARE hd      DATE;
    DECLARE ce      DATE;
    DECLARE wy      SMALLINT;
    DECLARE lv      TINYINT;
    DECLARE mgr     TINYINT;   -- 0/1，写入 BIT(1)
    DECLARE kpi     FLOAT;
    DECLARE addr    VARCHAR(200);
    DECLARE memo    TEXT;
    DECLARE ts      TIMESTAMP;

    -- 公司池（10 家，每家最多可容纳 100 万 / 10 = 10 万唯一员工）
    DECLARE company_count  INT DEFAULT 10;
    -- 部门池（8 个）
    DECLARE dept_count     INT DEFAULT 8;
    -- 职位池（10 个）
    DECLARE title_count    INT DEFAULT 10;
    -- 城市池（8 个）
    DECLARE city_count     INT DEFAULT 8;

    SET autocommit = 0;

    WHILE i <= total DO

        -- ---- 公司（按行号轮换，确保同公司内员工名唯一）----
        SET company = CONCAT('公司',
            ELT(1 + (i - 1) MOD company_count,
                'Alpha科技', 'Beta制造', 'Gamma贸易', 'Delta金融',
                'Epsilon互联', 'Zeta医疗', 'Eta教育', 'Theta物流',
                'Iota能源', 'Kappa零售'));

        -- ---- 部门 ----
        SET dept = ELT(1 + FLOOR(RAND() * dept_count),
            '研发部', '产品部', '市场部', '销售部',
            '财务部', '人事部', '运营部', '法务部');

        -- ---- 员工（公司编号 + 序号，保证 company+employee 唯一）----
        -- 公司编号 = (i-1) MOD 10 + 1（范围 1-10），序号 = CEIL(i/10)
        SET emp = CONCAT('员工', LPAD(CEIL(i / company_count), 6, '0'));

        -- ---- 职位 ----
        SET title = ELT(1 + FLOOR(RAND() * title_count),
            '初级工程师', '中级工程师', '高级工程师', '架构师',
            '产品经理', '技术总监', '项目经理', '数据分析师',
            'HR专员', '财务主管');

        -- ---- 薪资、奖金、税率 ----
        SET salary  = ROUND(8000  + RAND() * 42000, 2);   -- 8000-50000
        SET bonus   = ROUND(0     + RAND() * 20000, 2);   -- 0-20000
        SET tax     = ROUND(0.03  + RAND() * 0.32,  4);   -- 0.03-0.35

        -- ---- 日期：入职在最近 15 年内，工龄由入职日期推算 ----
        SET hd  = DATE_SUB(CURDATE(), INTERVAL FLOOR(RAND() * 5475) DAY);  -- 0-15年
        SET ce  = DATE_ADD(hd, INTERVAL (1 + FLOOR(RAND() * 5)) YEAR);    -- 合同 1-5 年
        SET wy  = TIMESTAMPDIFF(YEAR, hd, CURDATE());

        -- ---- 职级（与工龄正相关，加随机扰动）----
        SET lv  = LEAST(10, GREATEST(1, wy + FLOOR(RAND() * 3)));

        -- ---- 是否管理者（职级 >= 7 时 50% 概率，否则 10% 概率）----
        SET mgr = IF(lv >= 7, IF(RAND() < 0.5, 1, 0), IF(RAND() < 0.1, 1, 0));

        -- ---- KPI 得分 ----
        SET kpi  = ROUND(60 + RAND() * 40, 1);   -- 60-100

        -- ---- 办公地点 ----
        SET addr = CONCAT(
            ELT(1 + FLOOR(RAND() * city_count),
                '北京', '上海', '广州', '深圳', '杭州', '成都', '武汉', '西安'),
            ELT(1 + FLOOR(RAND() * 3), '总部', '分部', 'CBD'),
            FLOOR(1 + RAND() * 20), '层');

        -- ---- 备忘录（30% 行有内容，其余 NULL）----
        SET memo = IF(RAND() < 0.3,
            CONCAT('备注-', i, ': ', ELT(1 + FLOOR(RAND() * 4),
                '核心骨干', '待晋升', '绩效观察期', '外派人员')),
            NULL);

        -- ---- 时间戳（过去 3 年内随机，用于增量同步测试）----
        SET ts = DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 1095) DAY);

        INSERT INTO test_wide_table
            (id, company, department, employee, title, salary, bonus, tax_rate,
             hire_date, contract_end, work_years, level, is_manager,
             kpi_score, office_addr, memo, create_time, update_time, time_stamp)
        VALUES
            (UUID(), company, dept, emp, title, salary, bonus, tax,
             hd, ce, wy, lv, mgr,
             kpi, addr, memo, ts, ts, ts);

        -- 每 1000 行提交一次，减少 redo log 压力
        IF i MOD 1000 = 0 THEN
            COMMIT;
        END IF;

        SET i = i + 1;
    END WHILE;

    -- 提交剩余行
    COMMIT;
    SET autocommit = 1;
END$$

DELIMITER ;

-- 执行：插入 100 万行（约需 3-10 分钟，取决于机器性能）
CALL gen_wide_table_data(1000000);

-- 执行后可清理存储过程
DROP PROCEDURE IF EXISTS gen_wide_table_data;

SELECT COUNT(*) AS total_rows FROM test_wide_table;
