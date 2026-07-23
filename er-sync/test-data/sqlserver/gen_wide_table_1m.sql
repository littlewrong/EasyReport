-- ============================================================
-- 向 dbo.test_wide_table 批量插入 100 万条测试数据（SQL Server）
-- 使用方法：先执行 sqlserver_test_data.sql，再执行本脚本
-- ============================================================

WITH
E1(N) AS (
    SELECT 1 UNION ALL SELECT 1 UNION ALL SELECT 1 UNION ALL SELECT 1 UNION ALL SELECT 1
    UNION ALL SELECT 1 UNION ALL SELECT 1 UNION ALL SELECT 1 UNION ALL SELECT 1 UNION ALL SELECT 1
),
E2(N) AS (SELECT 1 FROM E1 a CROSS JOIN E1 b),
E4(N) AS (SELECT 1 FROM E2 a CROSS JOIN E2 b),
E6(N) AS (SELECT 1 FROM E4 a CROSS JOIN E2 b),
Nums(i) AS (SELECT TOP (1000000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) FROM E6)
INSERT INTO [dbo].[test_wide_table]
    ([id], [company], [department], [employee], [title], [salary], [bonus], [tax_rate],
     [hire_date], [contract_end], [work_years], [level], [is_manager],
     [kpi_score], [office_addr], [memo], [create_time], [update_time], [time_stamp])
SELECT
    CONVERT(NVARCHAR(36), NEWID()),
    N'公司' + CHOOSE(1 + ((i - 1) % 10), N'Alpha科技', N'Beta制造', N'Gamma贸易', N'Delta金融', N'Epsilon互联', N'Zeta医疗', N'Eta教育', N'Theta物流', N'Iota能源', N'Kappa零售'),
    CHOOSE(1 + ABS(CHECKSUM(NEWID())) % 8, N'研发部', N'产品部', N'市场部', N'销售部', N'财务部', N'人事部', N'运营部', N'法务部'),
    N'员工' + RIGHT(N'000000' + CONVERT(NVARCHAR(20), CEILING(i / 10.0)), 6),
    CHOOSE(1 + ABS(CHECKSUM(NEWID())) % 10, N'初级工程师', N'中级工程师', N'高级工程师', N'架构师', N'产品经理', N'技术总监', N'项目经理', N'数据分析师', N'HR专员', N'财务主管'),
    ROUND(8000 + RAND(CHECKSUM(NEWID())) * 42000, 2),
    ROUND(RAND(CHECKSUM(NEWID())) * 20000, 2),
    ROUND(0.03 + RAND(CHECKSUM(NEWID())) * 0.32, 4),
    DATEADD(DAY, -ABS(CHECKSUM(NEWID())) % 5475, CAST(GETDATE() AS date)),
    DATEADD(DAY, ABS(CHECKSUM(NEWID())) % 1825, CAST(GETDATE() AS date)),
    ABS(CHECKSUM(NEWID())) % 15,
    1 + ABS(CHECKSUM(NEWID())) % 10,
    CASE WHEN ABS(CHECKSUM(NEWID())) % 10 < 2 THEN 1 ELSE 0 END,
    ROUND(60 + RAND(CHECKSUM(NEWID())) * 40, 1),
    CHOOSE(1 + ABS(CHECKSUM(NEWID())) % 8, N'北京', N'上海', N'广州', N'深圳', N'杭州', N'成都', N'武汉', N'西安')
        + CHOOSE(1 + ABS(CHECKSUM(NEWID())) % 3, N'总部', N'分部', N'CBD')
        + CONVERT(NVARCHAR(10), 1 + ABS(CHECKSUM(NEWID())) % 20) + N'层',
    CASE WHEN ABS(CHECKSUM(NEWID())) % 10 < 3 THEN N'备注-' + CONVERT(NVARCHAR(20), i) + N': ' + CHOOSE(1 + ABS(CHECKSUM(NEWID())) % 4, N'核心骨干', N'待晋升', N'绩效观察期', N'外派人员') ELSE NULL END,
    DATEADD(DAY, -ABS(CHECKSUM(NEWID())) % 1095, SYSDATETIME()),
    DATEADD(DAY, -ABS(CHECKSUM(NEWID())) % 1095, SYSDATETIME()),
    DATEADD(DAY, -ABS(CHECKSUM(NEWID())) % 1095, SYSDATETIME())
FROM Nums;

SELECT COUNT(*) AS total_rows FROM [dbo].[test_wide_table];
