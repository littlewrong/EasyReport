-- 在 EasyReport 配置库执行，先 USE 你的配置库；请勿在源业务库或 StarRocks 执行。
-- MySQL 5.7/8.0：可重复执行；原任务默认关闭，仅新增 external_table_enabled 一列。
-- ALTER TABLE 会隐式提交。无选定库或任务表不存在时，PREPARE 会报错且不执行 DDL。
SET @er_external_table_db = DATABASE();
SET @er_external_table_ddl = CASE
  WHEN @er_external_table_db IS NULL THEN
    'SELECT ERROR_NO_DATABASE_SELECTED__USE_EASYREPORT_CONFIG_DB_FIRST'
  WHEN NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @er_external_table_db AND TABLE_NAME = 'er_data_transfer'
      AND TABLE_TYPE = 'BASE TABLE'
  ) THEN
    'SELECT ERROR_ER_DATA_TRANSFER_TABLE_NOT_FOUND__CHECK_CONFIG_DB'
  WHEN EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_external_table_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'external_table_enabled'
  ) THEN
    'SELECT ''external_table_enabled already exists; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_external_table_db, '`', '``'),
    '`.`er_data_transfer` ADD COLUMN `external_table_enabled` CHAR(1) NOT NULL DEFAULT ''0''',
    ' COMMENT ''外部表模式导入（0关闭 1开启，仅MySQL/TiDB到StarRocks初始化同步）'' AFTER `sync_mode`')
END;
PREPARE er_external_table_stmt FROM @er_external_table_ddl;
EXECUTE er_external_table_stmt;
DEALLOCATE PREPARE er_external_table_stmt;
SET @er_external_table_db = NULL;
SET @er_external_table_ddl = NULL;
