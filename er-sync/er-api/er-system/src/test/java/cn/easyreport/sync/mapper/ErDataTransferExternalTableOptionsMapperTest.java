package cn.easyreport.sync.mapper;

import cn.easyreport.sync.domain.ErDataTransfer;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class ErDataTransferExternalTableOptionsMapperTest
{
    private static final String NAMESPACE = "cn.easyreport.sync.mapper.ErDataTransferMapper.";
    private static Configuration configuration;

    @BeforeClass
    public static void loadMapperWithoutDatabase() throws Exception
    {
        configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ErDataTransfer", ErDataTransfer.class);
        String resource = "mapper/sync/ErDataTransferMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    public void insertsEnabledSwitchUsingMatchingColumnAndValue()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setExternalTableEnabled("1");

        BoundSql bound = statement("insertErDataTransfer", task);

        assertTrue(sql(bound).contains("external_table_enabled"));
        assertEquals(Arrays.asList("externalTableEnabled"), properties(bound));
    }

    @Test
    public void unspecifiedInsertUsesDatabaseDefault()
    {
        BoundSql bound = statement("insertErDataTransfer", new ErDataTransfer());

        assertFalse(sql(bound).contains("external_table_enabled"));
        assertFalse(sql(bound).contains("external_table_threshold_wan"));
        assertFalse(sql(bound).contains("external_table_window_unit"));
        assertFalse(sql(bound).contains("catalog_name"));
        assertTrue(properties(bound).isEmpty());
    }

    @Test
    public void progressOnlyUpdateDoesNotClearSwitch()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(7L);
        task.setSyncProgress("{\"current\":1}");

        BoundSql bound = statement("updateErDataTransfer", task);

        assertFalse(sql(bound).contains("external_table_enabled"));
        assertFalse(sql(bound).contains("external_table_threshold_wan"));
        assertFalse(sql(bound).contains("external_table_window_unit"));
        assertFalse(sql(bound).contains("catalog_name"));
        assertFalse(sql(bound).contains("batch_size"));
        assertEquals(Arrays.asList("syncProgress", "transferId"), properties(bound));
    }

    @Test
    public void explicitlyDisabledSwitchIsPersisted()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(7L);
        task.setExternalTableEnabled("0");

        BoundSql bound = statement("updateErDataTransfer", task);

        assertTrue(sql(bound).contains("external_table_enabled = ?"));
        assertEquals(Arrays.asList("externalTableEnabled", "transferId"), properties(bound));
        assertEquals("0", configuration.newMetaObject(bound.getParameterObject()).getValue("externalTableEnabled"));
    }

    @Test
    public void readsSavedSwitchForListAndDetail()
    {
        for (String name : new String[] {"selectErDataTransferList", "selectErDataTransferByTransferId"})
        {
            MappedStatement select = configuration.getMappedStatement(NAMESPACE + name);
            Object parameter = "selectErDataTransferList".equals(name) ? new ErDataTransfer() : 7L;
            Map<String, String> columns = select.getResultMaps().get(0).getResultMappings().stream()
                    .collect(Collectors.toMap(ResultMapping::getProperty, ResultMapping::getColumn));

            assertTrue(sql(select.getBoundSql(parameter)).contains("t.external_table_enabled"));
            assertEquals("external_table_enabled", columns.get("externalTableEnabled"));
            assertTrue(sql(select.getBoundSql(parameter)).contains("t.catalog_name"));
            assertEquals("catalog_name", columns.get("catalogName"));
            assertTrue(sql(select.getBoundSql(parameter)).contains("t.external_table_threshold_wan"));
            assertEquals("external_table_threshold_wan", columns.get("externalTableThresholdWan"));
            assertTrue(sql(select.getBoundSql(parameter)).contains("t.external_table_window_unit"));
            assertEquals("external_table_window_unit", columns.get("externalTableWindowUnit"));
        }
    }

    @Test
    public void policyInsertUsesMatchingColumnsAndValues()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setExternalTableThresholdWan(75);
        task.setExternalTableWindowUnit("MONTH");
        task.setBatchSize(1000);

        BoundSql bound = statement("insertErDataTransfer", task);

        assertTrue(sql(bound).contains("external_table_threshold_wan, external_table_window_unit, batch_size"));
        assertEquals(Arrays.asList("externalTableThresholdWan", "externalTableWindowUnit", "batchSize"), properties(bound));
    }

    @Test
    public void policyOnlyUpdatePreservesOmittedOptions()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(7L);
        task.setExternalTableThresholdWan(25);

        BoundSql bound = statement("updateErDataTransfer", task);

        assertTrue(sql(bound).contains("external_table_threshold_wan = ?"));
        assertFalse(sql(bound).contains("external_table_window_unit"));
        assertFalse(sql(bound).contains("external_table_enabled"));
        assertFalse(sql(bound).contains("batch_size"));
        assertEquals(Arrays.asList("externalTableThresholdWan", "transferId"), properties(bound));

        task = new ErDataTransfer();
        task.setTransferId(7L);
        task.setExternalTableWindowUnit("DAY");
        bound = statement("updateErDataTransfer", task);
        assertTrue(sql(bound).contains("external_table_window_unit = ?"));
        assertFalse(sql(bound).contains("external_table_threshold_wan"));
        assertEquals(Arrays.asList("externalTableWindowUnit", "transferId"), properties(bound));
    }

    @Test
    public void catalogInsertAndUpdateUseBoundParameters()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setCatalogName("src_mysql");
        BoundSql bound = statement("insertErDataTransfer", task);
        assertTrue(sql(bound).contains("catalog_name"));
        assertFalse(sql(bound).contains("src_mysql"));
        assertEquals(Arrays.asList("catalogName"), properties(bound));

        task.setTransferId(7L);
        bound = statement("updateErDataTransfer", task);
        assertTrue(sql(bound).contains("catalog_name = ?"));
        assertFalse(sql(bound).contains("external_table_enabled"));
        assertEquals(Arrays.asList("catalogName", "transferId"), properties(bound));
    }

    private BoundSql statement(String name, ErDataTransfer task)
    {
        return configuration.getMappedStatement(NAMESPACE + name).getBoundSql(task);
    }

    private String sql(BoundSql bound)
    {
        return bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<String> properties(BoundSql bound)
    {
        return bound.getParameterMappings().stream().map(ParameterMapping::getProperty).collect(Collectors.toList());
    }
}
