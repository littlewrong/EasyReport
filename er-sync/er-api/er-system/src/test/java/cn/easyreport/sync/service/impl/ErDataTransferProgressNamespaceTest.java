package cn.easyreport.sync.service.impl;

import cn.easyreport.sync.domain.ErDataTransfer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

/** Namespace and MAX(timestamp) SQL contracts; no database or progress writes are used. */
public class ErDataTransferProgressNamespaceTest {
    private final ErDataTransferServiceImpl service = new ErDataTransferServiceImpl();

    @Test
    public void sourceSchemaRemainsTheDefaultStarRocksDatabase() throws Exception {
        // DM, Kingbase and PostgreSQL metadata all supply an actual source schema.
        // The default resolver preserves it without accepting connection/catalog state.
        assertEquals("`test`.`orders`", table(new ErDataTransfer(), "STARROCKS", "test", "orders"));
        assertEquals("`public`.`orders`", table(new ErDataTransfer(), "STARROCKS", "public", "orders"));
    }

    @Test
    public void explicitQualifiedTargetTakesPriorityOverTheSourceSchema() throws Exception {
        ErDataTransfer task = new ErDataTransfer();
        task.setTargetTable("manual_db.renamed");
        assertEquals("`manual_db`.`renamed`", table(task, "STARROCKS", "test", "orders"));
        assertEquals("`manual_db`.`renamed`", table(task, "STARROCKS", "archive", "orders"));
    }

    @Test
    public void explicitUnqualifiedTargetKeepsItsExistingUnqualifiedBehavior() throws Exception {
        ErDataTransfer task = new ErDataTransfer();
        task.setTargetTable("renamed");
        assertEquals("`renamed`", table(task, "STARROCKS", "test", "orders"));
    }

    @Test
    public void sameTableNamesAcrossSchemasQueryDifferentStarRocksDatabasesWithoutCatalogReads() throws Exception {
        ErDataTransfer task = new ErDataTransfer();
        List<String> sql = new ArrayList<>();
        Connection connection = connection(sql);
        Method query = ErDataTransferServiceImpl.class.getDeclaredMethod("queryTargetMaxTimestamp",
                Connection.class, String.class, String.class, String.class);
        for (String schema : new String[]{"test", "archive"}) {
            invoke(query, connection, "STARROCKS", table(task, "STARROCKS", schema, "orders"), "time_stamp");
        }
        assertEquals(Arrays.asList("SELECT MAX(`time_stamp`) FROM `test`.`orders`",
                "SELECT MAX(`time_stamp`) FROM `archive`.`orders`"), sql);
    }

    @Test
    public void anAbsentSourceSchemaKeepsAnUnqualifiedTarget() throws Exception {
        for (String schema : new String[]{null, ""}) {
            assertEquals("`orders`", table(new ErDataTransfer(), "STARROCKS", schema, "orders"));
        }
    }

    @Test
    public void otherTargetIdentifierStylesKeepTheSourceSchema() throws Exception {
        for (String target : new String[]{"MYSQL", "TIDB", "STARROCKS", "KINGBASE", "POSTGRESQL", "DM"}) {
            String quote = "KINGBASE".equals(target) || "POSTGRESQL".equals(target) || "DM".equals(target) ? "\"" : "`";
            assertEquals(quote + "test" + quote + "." + quote + "orders" + quote,
                    table(new ErDataTransfer(), target, "test", "orders"));
        }
    }

    private String table(ErDataTransfer task, String targetType, String schema, String table) throws Exception {
        Method method = ErDataTransferServiceImpl.class.getDeclaredMethod("resolveTargetTableForProgressRefresh",
                ErDataTransfer.class, String.class, String.class, String.class);
        return (String) invoke(method, task, targetType, schema, table);
    }

    private Object invoke(Method method, Object... arguments) throws Exception {
        method.setAccessible(true);
        try { return method.invoke(service, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    private static Connection connection(List<String> sql) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (object, method, arguments) -> {
                    if (!"prepareStatement".equals(method.getName())) {
                        throw new AssertionError("Unexpected connection operation, including catalog lookup: " + method.getName());
                    }
                    sql.add((String) arguments[0]);
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (statement, call, values) -> {
                                if ("close".equals(call.getName())) return null;
                                if ("executeQuery".equals(call.getName())) {
                                    boolean[] read = {false};
                                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                                            (rows, operation, parameters) -> {
                                                if ("next".equals(operation.getName())) {
                                                    boolean next = !read[0];
                                                    read[0] = true;
                                                    return next;
                                                }
                                                if ("getObject".equals(operation.getName()) || "close".equals(operation.getName())) return null;
                                                throw new AssertionError(operation.getName());
                                            });
                                }
                                throw new AssertionError(call.getName());
                            });
                });
    }
}
