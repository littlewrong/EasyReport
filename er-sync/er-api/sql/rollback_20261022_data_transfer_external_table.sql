-- 先停止当前后端和同步任务，再在 EasyReport 配置库执行；先 USE 你的配置库。
-- 仅配合回退到不引用 external_table_enabled 的旧版后端；请勿在源业务库或 StarRocks 执行。
-- MySQL 5.7/8.0：可重复执行，仅删除新增的 external_table_enabled 列（会丢弃开关配置）。
-- 不删除任务、进度或业务数据，不触及 structure_reuse_mode 或历史 Stream Load 字段。
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
  WHEN NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_external_table_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'external_table_enabled'
  ) THEN
    'SELECT ''external_table_enabled already absent; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_external_table_db, '`', '``'),
    '`.`er_data_transfer` DROP COLUMN `external_table_enabled`')
END;
PREPARE er_external_table_stmt FROM @er_external_table_ddl;
EXECUTE er_external_table_stmt;
DEALLOCATE PREPARE er_external_table_stmt;
SET @er_external_table_db = NULL;
SET @er_external_table_ddl = NULL;
