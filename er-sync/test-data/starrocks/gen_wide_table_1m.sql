-- ============================================================
-- 向 test_wide_table 批量插入 100 万条测试数据（StarRocks）
-- 使用方法：在 synctest 库下执行本脚本
-- 依赖 StarRocks v3.1+ 的 TABLE(generate_series(...)) 表函数
-- ============================================================

USE synctest;

INSERT INTO test_wide_table
    (id, company, department, employee, title, salary, bonus, tax_rate,
     hire_date, contract_end, work_years, level, is_manager,
     kpi_score, office_addr, memo, create_time, update_time, time_stamp)
SELECT
    uuid(),
    concat('公司', element_at(['Alpha科技', 'Beta制造', 'Gamma贸易', 'Delta金融', 'Epsilon互联', 'Zeta医疗', 'Eta教育', 'Theta物流', 'Iota能源', 'Kappa零售'], 1 + ((generate_series - 1) % 10))),
    element_at(['研发部', '产品部', '市场部', '销售部', '财务部', '人事部', '运营部', '法务部'], 1 + floor(rand() * 8)),
    concat('员工', lpad(cast(ceil(generate_series / 10.0) AS string), 6, '0')),
    element_at(['初级工程师', '中级工程师', '高级工程师', '架构师', '产品经理', '技术总监', '项目经理', '数据分析师', 'HR专员', '财务主管'], 1 + floor(rand() * 10)),
    round(8000 + rand() * 42000, 2),
    round(rand() * 20000, 2),
    round(0.03 + rand() * 0.32, 4),
    date_sub(current_date(), interval floor(rand() * 5475) day),
    date_add(current_date(), interval floor(rand() * 1825) day),
    floor(rand() * 15),
    greatest(1, least(10, floor(rand() * 10) + 1)),
    rand() < 0.2,
    round(60 + rand() * 40, 1),
    concat(element_at(['北京', '上海', '广州', '深圳', '杭州', '成都', '武汉', '西安'], 1 + floor(rand() * 8)), element_at(['总部', '分部', 'CBD'], 1 + floor(rand() * 3)), cast(1 + floor(rand() * 20) AS string), '层'),
    if(rand() < 0.3, concat('备注-', cast(generate_series AS string), ': ', element_at(['核心骨干', '待晋升', '绩效观察期', '外派人员'], 1 + floor(rand() * 4))), NULL),
    date_sub(now(), interval floor(rand() * 1095) day),
    date_sub(now(), interval floor(rand() * 1095) day),
    date_sub(now(), interval floor(rand() * 1095) day)
FROM TABLE(generate_series(1, 1000000));

SELECT COUNT(*) AS total_rows FROM test_wide_table;
