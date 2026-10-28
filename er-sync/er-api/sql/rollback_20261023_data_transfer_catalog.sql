-- 先停止当前后端和同步任务，再在 EasyReport 配置库执行；先 USE 你的配置库。
-- 仅配合回退到不引用 catalog_name 的旧版后端；正常升级不需要执行本回滚脚本。
-- MySQL 5.7/8.0：可重复执行，仅删除 catalog_name，丢弃已保存的 Catalog 名称配置。
-- 保留旧外部表开关、整批阈值、时间分片单位、任务进度和业务数据，不删除 StarRocks Catalog。
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
  WHEN NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @er_catalog_db AND TABLE_NAME = 'er_data_transfer'
      AND COLUMN_NAME = 'catalog_name'
  ) THEN
    'SELECT ''catalog_name already absent; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_catalog_db, '`', '``'),
    '`.`er_data_transfer` DROP COLUMN `catalog_name`')
END;
PREPARE er_catalog_stmt FROM @er_catalog_ddl;
EXECUTE er_catalog_stmt;
DEALLOCATE PREPARE er_catalog_stmt;
SET @er_catalog_db = NULL;
SET @er_catalog_ddl = NULL;
