-- 先停止当前后端和同步任务，再在 EasyReport 配置库执行；先 USE 你的配置库。
-- 仅配合回退到不引用本次两项策略列的旧版后端；请勿在源业务库或 StarRocks 执行。
-- MySQL 5.7/8.0：可重复执行，仅删除 external_table_threshold_wan 和 external_table_window_unit。
-- 会丢弃自定义策略配置；旧版本仍按 50 万条、自然年运行。
-- 保留 external_table_enabled、batch_size、任务进度及业务数据；不需要执行 20261022 回滚脚本。
-- ALTER TABLE 会隐式提交。无选定库或任务表不存在时，PREPARE 会报错且不执行 DDL。
SET @er_external_policy_db = DATABASE();
SET @er_external_policy_columns = CONCAT_WS(', ',
  IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_external_policy_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'external_table_threshold_wan'
  ), 'DROP COLUMN `external_table_threshold_wan`', NULL),
  IF(EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_external_policy_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'external_table_window_unit'
  ), 'DROP COLUMN `external_table_window_unit`', NULL)
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
    'SELECT ''external table policy columns already absent; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_external_policy_db, '`', '``'),
    '`.`er_data_transfer` ', @er_external_policy_columns)
END;
PREPARE er_external_policy_stmt FROM @er_external_policy_ddl;
EXECUTE er_external_policy_stmt;
DEALLOCATE PREPARE er_external_policy_stmt;
SET @er_external_policy_db = NULL;
SET @er_external_policy_columns = NULL;
SET @er_external_policy_ddl = NULL;
