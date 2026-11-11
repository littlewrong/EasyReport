package cn.easyreport.sync.service.impl;

import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.*;

public class DmTransferProgressTest {
    @Test
    public void refreshUsesSameCasePreservingIdentifiersAsDmTransfer() throws Exception {
        ErDataTransferServiceImpl service = new ErDataTransferServiceImpl();
        Method qualify = ErDataTransferServiceImpl.class.getDeclaredMethod("qualifyTargetTableForProgressRefresh",
            String.class, String.class, String.class);
        qualify.setAccessible(true);
        assertEquals("\"App\".\"Order\"\"Items\"", qualify.invoke(service, "DM", "App", "Order\"Items"));
        assertEquals("\"Orders\"", qualify.invoke(service, "DM", null, "Orders"));
        assertEquals("`App`.`Orders`", qualify.invoke(service, "MYSQL", "App", "Orders"));
        assertEquals("\"App\".\"Orders\"", qualify.invoke(service, "POSTGRESQL", "App", "Orders"));
        assertEquals("\"APP\".\"ORDERS\"", qualify.invoke(service, "ORACLE", "App", "Orders"));
    }
}
