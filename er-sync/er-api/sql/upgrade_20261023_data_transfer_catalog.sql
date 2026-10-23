-- 在 EasyReport 配置库执行，先 USE 你的配置库；请勿在源业务库或 StarRocks 执行。
-- MySQL 5.7/8.0：可重复执行，仅增加 catalog_name，不修改旧外部表开关或导入策略。
-- 已有任务保留 NULL，继续兼容旧外部表路径；编辑后启用 Catalog 导入须填写名称。
-- ALTER TABLE 会隐式提交。无选定库或任务表不存在时，PREPARE 会报错且不执行 DDL。
SET @er_catalog_db = DATABASE();
SET @er_catalog_ddl = CASE
  WHEN @er_catalog_db IS NULL THEN
    'SELECT ERROR_NO_DATABASE_SELECTED__USE_EASYREPORT_CONFIG_DB_FIRST'
  WHEN NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @er_catalog_db AND TABLE_NAME = 'er_data_transfer'
      AND TABLE_TYPE = 'BASE TABLE'
  ) THEN
    'SELECT ERROR_ER_DATA_TRANSFER_TABLE_NOT_FOUND__CHECK_CONFIG_DB'
  WHEN EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_catalog_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'catalog_name'
  ) THEN
    'SELECT ''catalog_name already exists; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_catalog_db, '`', '``'),
    '`.`er_data_transfer` ADD COLUMN `catalog_name` VARCHAR(1023) NULL DEFAULT NULL COMMENT ''目标StarRocks已有的JDBC Catalog名称''')
END;
PREPARE er_catalog_stmt FROM @er_catalog_ddl;
EXECUTE er_catalog_stmt;
DEALLOCATE PREPARE er_catalog_stmt;
SET @er_catalog_db = NULL;
SET @er_catalog_ddl = NULL;
