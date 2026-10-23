-- 升级应用前，在现有 EasyReport 数据库执行一次；新安装使用 easyreport.sql。
ALTER TABLE `er_data_sync`
  ADD COLUMN `structure_reuse_mode` char(1) NOT NULL DEFAULT '0' COMMENT '分片库架构共用（0关闭 1开启）' AFTER `source_table_pattern`;
