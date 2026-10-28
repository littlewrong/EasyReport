-- 先停止后端和同步任务、备份配置库，再 USE 配置库执行；正常升级不执行本脚本。
-- 仅配合恢复到按 catalog_name 有无选择方式的旧后端；保留任务开关、导入策略和进度。
-- 显式外部表任务若保留了隐藏 Catalog 名称，先明确清空其名称，避免旧后端误走 Catalog。
-- 有上述配置时本脚本会拒绝回滚，不自动修改任务；可重复执行，不删除 StarRocks 对象。
SET @er_import_method_db = DATABASE();
SET @er_import_method_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @er_import_method_db AND TABLE_NAME = 'er_data_transfer'
    AND COLUMN_NAME = 'direct_import_method'
);
SET @er_import_method_check = CASE
  WHEN @er_import_method_exists = 1 THEN
    'SELECT COUNT(*) INTO @er_import_method_conflicts FROM er_data_transfer WHERE direct_import_method = ''EXTERNAL_TABLE'' AND NULLIF(TRIM(catalog_name), '''') IS NOT NULL'
  ELSE 'SET @er_import_method_conflicts = 0'
END;
PREPARE er_import_method_stmt FROM @er_import_method_check;
EXECUTE er_import_method_stmt;
DEALLOCATE PREPARE er_import_method_stmt;
SET @er_import_method_ddl = CASE
  WHEN @er_import_method_db IS NULL THEN
    'SELECT ERROR_NO_DATABASE_SELECTED__USE_EASYREPORT_CONFIG_DB_FIRST'
  WHEN NOT EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @er_import_method_db AND TABLE_NAME = 'er_data_transfer'
      AND TABLE_TYPE = 'BASE TABLE'
  ) THEN
    'SELECT ERROR_ER_DATA_TRANSFER_TABLE_NOT_FOUND__CHECK_CONFIG_DB'
  WHEN @er_import_method_conflicts > 0 THEN
    'SELECT ERROR_EXTERNAL_TABLE_RETAINS_CATALOG_NAME__CLEAR_NAME_BEFORE_ROLLBACK'
  WHEN @er_import_method_exists = 0 THEN
    'SELECT ''direct_import_method already absent; no change'' AS migration_result'
  ELSE CONCAT('ALTER TABLE `', REPLACE(@er_import_method_db, '`', '``'),
    '`.`er_data_transfer` DROP COLUMN `direct_import_method`')
END;
PREPARE er_import_method_stmt FROM @er_import_method_ddl;
EXECUTE er_import_method_stmt;
DEALLOCATE PREPARE er_import_method_stmt;
SET @er_import_method_db = NULL;
SET @er_import_method_exists = NULL;
SET @er_import_method_conflicts = NULL;
SET @er_import_method_check = NULL;
SET @er_import_method_ddl = NULL;
