package cn.easyreport.sync.dialect;

import cn.easyreport.sync.model.TableMeta;

public interface SchemaDialect {

    /**
     * Build CREATE TABLE DDL from meta.
     */
    String createTable(TableMeta tableMeta);
}
