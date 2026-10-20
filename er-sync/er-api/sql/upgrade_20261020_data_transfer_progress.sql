-- 在 EasyReport 配置库执行，不是在 TiDB 业务源库或 StarRocks 目标库执行。
-- 扩容已有的数据同步进度字段，保留已有值；可重复执行。
-- 新后端同时对进度快照限量，避免库表过多导致大 JSON 反复写入。
ALTER TABLE `er_data_transfer`
  MODIFY COLUMN `sync_progress` MEDIUMTEXT CHARACTER SET utf8mb4 NULL COMMENT '同步进度（JSON格式）';
