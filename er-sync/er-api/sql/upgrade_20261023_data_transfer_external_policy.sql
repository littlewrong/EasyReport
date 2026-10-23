-- 在 EasyReport 配置库执行，先 USE 你的配置库；请勿在源业务库或 StarRocks 执行。
-- MySQL 5.7/8.0：可重复执行，仅新增本次两项外部表导入策略配置。
-- 已有任务默认 50 万条、按自然年；已有列及自定义配置保持不变。
-- 不修改 external_table_enabled、batch_size、任务进度或业务数据。
-- ALTER TABLE 会隐式提交。无选定库或任务表不存在时，PREPARE 会报错且不执行 DDL。
SET @er_external_policy_db = DATABASE();
SET @er_external_policy_columns = CONCAT_WS(', ',
  IF(NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_external_policy_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'external_table_threshold_wan'
  ), 'ADD COLUMN `external_table_threshold_wan` INT NOT NULL DEFAULT 50 COMMENT ''外部表整批导入阈值（万条，少于时整批导入）''', NULL),
  IF(NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_external_policy_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'external_table_window_unit'
  ), 'ADD COLUMN `external_table_window_unit` VARCHAR(8) NOT NULL DEFAULT ''YEAR'' COMMENT ''外部表时间分片单位（YEAR年 MONTH月 DAY日）''', NULL)
);
SET @er_external_policy_ddl = CASE
  WHEN @er_external_policy_db IS NULL THEN
    'SELECT ERROR_NO_DATABASE_SELECTED__USE_EASYREPORT_CONFIG_DB_FIRST'
  WHEN NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @er_external_policy_db AND TABLE_NAME = 'er_data_transfer'
      AND TABLE_TYPE = 'BASE TABLE'
  ) THEN
    'SELECT ERROR_ER_DATA_TRANSFER_TABLE_NOT_FOUND__CHECK_CONFIG_DB'
  WHEN @er_external_policy_columns = '' THEN
    'SELECT ''external table policy columns already exist; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_external_policy_db, '`', '``'),
    '`.`er_data_transfer` ', @er_external_policy_columns)
END;
PREPARE er_external_policy_stmt FROM @er_external_policy_ddl;
EXECUTE er_external_policy_stmt;
DEALLOCATE PREPARE er_external_policy_stmt;
SET @er_external_policy_db = NULL;
SET @er_external_policy_columns = NULL;
SET @er_external_policy_ddl = NULL;
