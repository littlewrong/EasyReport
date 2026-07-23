-- ============================================================
-- 向 test_wide_table 批量插入 100 万条测试数据（PostgreSQL）
-- 使用方法：在目标数据库中先执行 postgresql_test_data.sql，再执行本脚本
-- 不依赖额外扩展，id 使用 generate_series 生成确定性唯一值
-- ============================================================

INSERT INTO test_wide_table
    (id, company, department, employee, title, salary, bonus, tax_rate,
     hire_date, contract_end, work_years, "level", is_manager,
     kpi_score, office_addr, memo, create_time, update_time, time_stamp)
SELECT
    '10000000-0000-0000-0000-' || lpad(i::text, 12, '0'),
    '公司' || (ARRAY['Alpha科技', 'Beta制造', 'Gamma贸易', 'Delta金融', 'Epsilon互联', 'Zeta医疗', 'Eta教育', 'Theta物流', 'Iota能源', 'Kappa零售'])[1 + ((i - 1) % 10)],
    (ARRAY['研发部', '产品部', '市场部', '销售部', '财务部', '人事部', '运营部', '法务部'])[1 + floor(random() * 8)::int],
    '员工' || lpad(ceil(i / 10.0)::int::text, 6, '0'),
    (ARRAY['初级工程师', '中级工程师', '高级工程师', '架构师', '产品经理', '技术总监', '项目经理', '数据分析师', 'HR专员', '财务主管'])[1 + floor(random() * 10)::int],
    round((8000 + random() * 42000)::numeric, 2),
    round((random() * 20000)::numeric, 2),
    round((0.03 + random() * 0.32)::numeric, 4),
    CURRENT_DATE - floor(random() * 5475)::int,
    CURRENT_DATE + floor(random() * 1825)::int,
    floor(random() * 15)::int,
    greatest(1, least(10, floor(random() * 10)::int + 1))::smallint,
    random() < 0.2,
    round((60 + random() * 40)::numeric, 1)::real,
    (ARRAY['北京', '上海', '广州', '深圳', '杭州', '成都', '武汉', '西安'])[1 + floor(random() * 8)::int] || (ARRAY['总部', '分部', 'CBD'])[1 + floor(random() * 3)::int] || (1 + floor(random() * 20)::int)::text || '层',
    CASE WHEN random() < 0.3 THEN '备注-' || i::text || ': ' || (ARRAY['核心骨干', '待晋升', '绩效观察期', '外派人员'])[1 + floor(random() * 4)::int] ELSE NULL END,
    CURRENT_TIMESTAMP - (floor(random() * 1095)::int || ' days')::interval,
    CURRENT_TIMESTAMP - (floor(random() * 1095)::int || ' days')::interval,
    CURRENT_TIMESTAMP - (floor(random() * 1095)::int || ' days')::interval
FROM generate_series(1, 1000000) AS s(i);

SELECT COUNT(*) AS total_rows FROM test_wide_table;
