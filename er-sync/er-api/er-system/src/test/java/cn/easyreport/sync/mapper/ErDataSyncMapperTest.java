package cn.easyreport.sync.mapper;

import cn.easyreport.sync.domain.ErDataSync;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class ErDataSyncMapperTest {

    private static final String NAMESPACE = "cn.easyreport.sync.mapper.ErDataSyncMapper.";
    private static Configuration configuration;

    @BeforeClass
    public static void loadMapperWithoutDatabase() throws Exception {
        configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ErDataSync", ErDataSync.class);
        String resource = "mapper/sync/ErDataSyncMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    public void insertsStructureReuseConfigurationWithMatchingParameters() {
        ErDataSync task = shardedTask();

        BoundSql bound = boundSql("insertErDataSync", task);

        assertTrue(sql(bound).contains("structure_reuse_mode"));
        assertEquals(Arrays.asList("structureReuseMode"), properties(bound));
        assertEquals("1", configuration.newMetaObject(bound.getParameterObject()).getValue(properties(bound).get(0)));
    }

    @Test
    public void updatesStructureReuseConfigurationWithMatchingParameters() {
        BoundSql bound = boundSql("updateErDataSync", shardedTask());

        assertTrue(sql(bound).contains("structure_reuse_mode = ?"));
        assertEquals(Arrays.asList("structureReuseMode", "syncId"), properties(bound));
    }

    @Test
    public void progressOnlyUpdateLeavesStructureReuseConfigurationUntouched() {
        ErDataSync progress = new ErDataSync();
        progress.setSyncId(7L);
        progress.setSyncProgress("{\"current\":1,\"total\":10}");

        BoundSql bound = boundSql("updateErDataSync", progress);

        assertTrue(sql(bound).contains("sync_progress = ?"));
        assertFalse(sql(bound).contains("structure_reuse_mode"));
        assertEquals(Arrays.asList("syncProgress", "syncId"), properties(bound));
    }

    @Test
    public void canExplicitlyDisableShardedSchemaCreation() {
        ErDataSync task = new ErDataSync();
        task.setSyncId(7L);
        task.setStructureReuseMode("0");

        BoundSql bound = boundSql("updateErDataSync", task);

        assertTrue(sql(bound).contains("structure_reuse_mode = ?"));
        assertEquals(Arrays.asList("structureReuseMode", "syncId"), properties(bound));
        assertEquals("0", configuration.newMetaObject(bound.getParameterObject()).getValue("structureReuseMode"));
    }

    @Test
    public void selectsAndMapsStructureReuseConfiguration() {
        MappedStatement select = configuration.getMappedStatement(NAMESPACE + "selectErDataSyncBySyncId");
        String query = sql(select.getBoundSql(7L));
        Map<String, String> columns = select.getResultMaps().get(0).getResultMappings().stream()
            .collect(Collectors.toMap(ResultMapping::getProperty, ResultMapping::getColumn));

        assertTrue(query.contains("s.structure_reuse_mode"));
        assertEquals("structure_reuse_mode", columns.get("structureReuseMode"));
    }

    private ErDataSync shardedTask() {
        ErDataSync task = new ErDataSync();
        task.setSyncId(7L);
        task.setStructureReuseMode("1");
        return task;
    }

    private BoundSql boundSql(String statement, ErDataSync task) {
        return configuration.getMappedStatement(NAMESPACE + statement).getBoundSql(task);
    }

    private String sql(BoundSql bound) {
        return bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<String> properties(BoundSql bound) {
        return bound.getParameterMappings().stream().map(ParameterMapping::getProperty).collect(Collectors.toList());
    }
}
