-- 在 EasyReport 配置库执行，先 USE 你的配置库；请勿在源业务库或 StarRocks 执行。
-- 先完成外部表开关、导入策略和 Catalog 名称的升级，再执行本脚本并更新后端。
-- MySQL 5.7/8.0：可重复执行，仅新增导入方式，不修改已有任务及进度。
-- 旧记录保留 NULL：Catalog 名称非空沿用 Catalog，否则沿用 MySQL/TiDB 外部表。
-- ALTER TABLE 会隐式提交。缺少配置库、任务表或前序字段时，PREPARE 报错且不执行 DDL。
SET @er_import_method_db = DATABASE();
SET @er_import_method_ddl = CASE
  WHEN @er_import_method_db IS NULL THEN
    'SELECT ERROR_NO_DATABASE_SELECTED__USE_EASYREPORT_CONFIG_DB_FIRST'
  WHEN NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @er_import_method_db AND TABLE_NAME = 'er_data_transfer'
      AND TABLE_TYPE = 'BASE TABLE'
  ) THEN
    'SELECT ERROR_ER_DATA_TRANSFER_TABLE_NOT_FOUND__CHECK_CONFIG_DB'
  WHEN (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_import_method_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME IN ('external_table_enabled', 'catalog_name', 'external_table_threshold_wan', 'external_table_window_unit')
  ) <> 4 THEN
    'SELECT ERROR_IMPORT_COLUMNS_MISSING__RUN_PREVIOUS_UPGRADE_SCRIPTS_FIRST'
  WHEN EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_import_method_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'direct_import_method'
  ) THEN
    'SELECT ''direct_import_method already exists; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_import_method_db, '`', '``'),
    '`.`er_data_transfer` ADD COLUMN `direct_import_method` VARCHAR(16) NULL DEFAULT NULL COMMENT ''数据湖导入方式（EXTERNAL_TABLE外部表 JDBC_CATALOG已有Catalog）''')
END;
PREPARE er_import_method_stmt FROM @er_import_method_ddl;
EXECUTE er_import_method_stmt;
DEALLOCATE PREPARE er_import_method_stmt;
SET @er_import_method_db = NULL;
SET @er_import_method_ddl = NULL;
